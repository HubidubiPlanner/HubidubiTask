package com.hubidubitask.core.agent

import com.hubidubitask.core.agent.AgentProbe.Result
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Which agent listens at an address, found from the address alone. */
class AgentProbeTest {
    private val stubs = mutableListOf<Stub>()
    @AfterTest fun stop() = stubs.forEach { it.close() }
    private fun stub(routes: (Stub.Call) -> Pair<Int, String>?) = Stub(routes).also { stubs += it }

    private fun bearer(token: String, c: Stub.Call) = c.auth == "Bearer $token"
    private fun basic(user: String, pw: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray())

    private val openclaw = { token: String? -> stub { c -> when (c.path) {
        "/health" -> 200 to """{"ok":true,"status":"live"}"""
        "/v1/models" -> if (token == null || bearer(token, c)) 200 to """{"data":[{"id":"openclaw"}]}""" else 401 to "{}"
        else -> null } } }

    private fun detect(s: Stub, token: String?) = AgentProbe.detect("127.0.0.1", s.port, token)

    @Test fun `OpenClaw is found by its health, and its token checked`() {
        val s = openclaw("tok")
        val found = assertIs<Result.Found>(detect(s, "tok"))
        assertEquals(AgentKind.OPENCLAW, found.kind)
        assertEquals("openclaw", found.model)
        assertEquals("http://127.0.0.1:${s.port}", found.endpoint, "plain HTTP inside the home network")
        assertEquals("OpenClaw", found.label)
        assertEquals(Result.NeedsToken(AgentKind.OPENCLAW, s.base, refused = false), detect(s, null))
        assertEquals(Result.NeedsToken(AgentKind.OPENCLAW, s.base, refused = true), detect(s, "wrong"))
    }

    @Test fun `Hermes is told apart from OpenClaw`() {
        val s = stub { c -> when (c.path) {
            "/health" -> 200 to """{"status":"ok"}"""
            "/v1/models" -> if (bearer("key", c)) 200 to """{"data":[{"id":"hermes-agent"}]}""" else 401 to "{}"
            else -> null } }
        val found = assertIs<Result.Found>(detect(s, "key"))
        assertEquals(AgentKind.HERMES, found.kind); assertEquals("hermes-agent", found.model)
    }

    @Test fun `opencode and Kilo answer on their own path, with a password`() {
        val opencode = stub { c -> when {
            c.path == "/global/health" && c.auth == basic("opencode", "pw") -> 200 to """{"healthy":true,"version":"1.19.0"}"""
            c.path == "/global/health" -> 401 to "{}"
            else -> null } }
        assertEquals(AgentKind.OPENCODE, assertIs<Result.Found>(detect(opencode, "pw")).kind)
        assertEquals(Result.NeedsToken(AgentKind.OPENCODE, opencode.base, refused = false), detect(opencode, null))
        assertEquals(Result.NeedsToken(AgentKind.OPENCODE, opencode.base, refused = true), detect(opencode, "nope"))

        val kilo = stub { c -> when {
            c.path == "/global/health" && c.auth == basic("kilo", "pw") -> 200 to """{"healthy":true}"""
            c.path == "/global/health" -> 401 to "{}"
            c.path == "/indexing/status" && c.auth == basic("kilo", "pw") -> 200 to "{}"
            else -> null } }
        assertEquals(AgentKind.KILO, assertIs<Result.Found>(detect(kilo, "pw")).kind)

        val open = stub { c -> if (c.path == "/global/health") 200 to """{"healthy":true}""" else null }
        assertEquals(AgentKind.OPENCODE, assertIs<Result.Found>(detect(open, null)).kind)
    }

    @Test fun `any other OpenAI-shaped server is found by its models`() {
        val s = stub { c -> if (c.path == "/v1/models") 200 to """{"data":[{"id":"qwen3.6-35b"}]}""" else null }
        val found = assertIs<Result.Found>(detect(s, null))
        assertEquals(AgentKind.OPENAI, found.kind); assertEquals("qwen3.6-35b", found.model)
        assertEquals("qwen3.6-35b", found.label)
    }

    @Test fun `nothing there, or something else, is not an agent`() {
        val web = stub { null }
        assertEquals(Result.NotFound, detect(web, null))
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", closed, null))
    }

    @Test fun `without a port, each kind's usual one is tried`() {
        val s = openclaw(null)
        val closed = ServerSocket(0).use { it.localPort }
        val found = assertIs<Result.Found>(AgentProbe.detect("127.0.0.1", null, null, ports = listOf(closed, s.port)))
        assertEquals(s.port, AgentLink.split(found.endpoint).second.toInt())
    }

    @Test fun `plain HTTP only inside the home network`() {
        val s = openclaw(null)
        assertEquals(Result.NotFound, AgentProbe.detect("127.0.0.1", s.port, null, plainAllowed = false))
        assertTrue(AgentLink.isPrivate("192.168.1.62")); assertTrue(AgentLink.isPrivate("10.0.0.2"))
        assertTrue(AgentLink.isPrivate("100.101.102.103")); assertTrue(AgentLink.isPrivate("fd00::5"))
        assertTrue(AgentLink.isPrivate("127.0.0.1"))
        assertFalse(AgentLink.isPrivate("8.8.8.8")); assertFalse(AgentLink.isPrivate("100.128.0.1"))
    }

    @Test fun `an IP and a port make the address, and come back out of it`() {
        assertEquals("192.168.1.50" to 18789, AgentLink.parse(" 192.168.1.50 ", "18789"))
        assertEquals("192.168.1.50" to 9000, AgentLink.parse("192.168.1.50:9000", ""))
        assertEquals("agent.local" to null, AgentLink.parse("https://agent.local/", ""))
        assertEquals("fd00::5" to 4096, AgentLink.parse("[fd00::5]:4096", ""))
        assertEquals("fd00::5" to null, AgentLink.parse("fd00::5", "x"))
        assertEquals(null, AgentLink.parse("  ", "8765"))
        assertEquals("http://[fd00::5]:4096", AgentLink.endpoint("http", "fd00::5", 4096))
        assertEquals("https://192.168.1.50", AgentLink.endpoint("https", "192.168.1.50", null))
        assertEquals("192.168.1.50" to "9000", AgentLink.split("http://192.168.1.50:9000"))
        assertEquals("fd00::5" to "4096", AgentLink.split("https://[fd00::5]:4096"))
        assertEquals("192.168.1.50" to "", AgentLink.split("https://192.168.1.50"))
        assertEquals("" to "", AgentLink.split(null))
    }
}
