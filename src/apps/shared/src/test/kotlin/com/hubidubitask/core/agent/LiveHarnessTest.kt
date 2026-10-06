package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Against a real Harness (src/harness), as the desktop app asks it. Runs only
 * with HARNESS_LIVE=https://ip:port set. The items live in this test alone.
 */
class LiveHarnessTest {
    @Test fun `pinned, with items, and its changes come back`() {
        val endpoint = System.getenv("HARNESS_LIVE") ?: return
        val health = Agent.clientFor(AgentConfig(endpoint, protocol = "harness")).health()
        assertTrue(health.connected, "not connected: ${health.error}")
        assertNotNull(health.fingerprint, "a certificate to pin")

        val applied = mutableListOf<HarnessAction>()
        val items = object : HarnessItems {
            override fun records() = listOf(
                EntityRecord("p_home", "project", "2026-10-06T00:00:00Z", mapOf("title" to "Dom")),
                EntityRecord("t_fence", "next_action", "2026-10-06T00:00:00Z",
                    mapOf("text" to "Paint the fence", "parent" to "p_home")))
            override fun apply(actions: List<HarnessAction>) { applied += actions }
        }
        val cfg = AgentConfig(endpoint, protocol = "harness", tlsFingerprint = health.fingerprint)
        val answer = Agent.ask(Agent.clientFor(cfg, items), cfg,
            "I painted the fence. Mark that task done, then tell me what is left in Dom.")
        println("answer: $answer\nactions: $applied")
        assertFalse(answer.startsWith("error:"), answer)
        assertTrue(applied.contains(HarnessAction("mark_done", id = "t_fence")), applied.toString())

        // A feed: no tools, no items, plain text back.
        val feed = AgentConfig(endpoint, protocol = "harness", tlsFingerprint = health.fingerprint, tools = false)
        val line = Agent.clientFor(feed, items).complete(listOf(Message("user", "Say only the word: ready")))
        assertTrue(line.contains("ready", ignoreCase = true), line)
    }
}
