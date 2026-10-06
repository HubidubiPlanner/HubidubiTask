package com.hubidubitask.desktop.harness

import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentError
import com.hubidubitask.core.agent.Message
import com.hubidubitask.desktop.PeerServer
import com.hubidubitask.desktop.Store
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A paired phone asking the computer's Harness, end to end: the phone's own
 * client (shared OpenAiClient, pinned to the computer's certificate, with its
 * pairing token) against the real peer server, with a stand-in for `claude`.
 */
class PeerHarnessTest {
    private val dir = Files.createTempDirectory("peerharness").toFile()
    private val store = Store(dir)
    private val seen = File(dir, "seen.txt")
    private val claude = File(dir, "claude").apply {
        writeText("#!/bin/sh\ncat > /dev/null\nprintf '%s\\n' \"\$@\" > '${seen.path}'\n" +
            "printf '{\"is_error\":false,\"result\":\"From the computer\"}'\n")
        setExecutable(true)
    }
    private val server = PeerServer(store, "Test computer", Harness(Items(store), dir, claude.path)) {}
        .also { it.start() }

    init { store.addPeer(Store.Peer("phone-1", "Pixel", "tok-123", "2026-10-06T00:00:00Z", null)) }

    @AfterTest fun stop() = server.stop()

    private fun phone(token: String, tools: Boolean = true) = AgentConfig(
        endpoint = "https://127.0.0.1:${server.port}/peer", protocol = "harness", apiKey = token,
        tlsFingerprint = server.fingerprint, tools = tools)

    @Test fun `a paired phone's chat runs Claude Code here, with tools`() {
        val cfg = phone("tok-123")
        assertEquals("From the computer", Agent.clientFor(cfg).complete(listOf(Message("user", "What next?"))))
        val args = seen.readLines()
        assertEquals("Read,Glob,Grep,WebFetch,Task", args[args.indexOf("--tools") + 1])
        assertTrue(Agent.clientFor(cfg).health().connected)
    }

    @Test fun `a phone's feed gets no tools`() {
        Agent.clientFor(phone("tok-123", tools = false)).complete(listOf(Message("user", "x")))
        val args = seen.readLines()
        assertEquals("", args[args.indexOf("--tools") + 1])
    }

    @Test fun `an unpaired caller is refused`() {
        assertFailsWith<AgentError> { Agent.clientFor(phone("not-a-token")).complete(listOf(Message("user", "x"))) }
    }
}
