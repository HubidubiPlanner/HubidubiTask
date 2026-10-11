package com.hubidubitask.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentAction
import com.hubidubitask.core.agent.AgentItems
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone asking a real Harness on the home network, with items held only
 * in this test, so nothing of the user's is sent or changed. Skipped unless
 * given an address:
 *
 *   adb shell am instrument -w -e harness https://192.168.1.50:8765 \
 *     <app id>.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LiveHarnessTest {
    private val endpoint: String? = InstrumentationRegistry.getArguments().getString("harness")

    @Test fun askTheHarnessFromThePhone() {
        assumeTrue("pass -e harness https://ip:port", endpoint != null)
        val health = Agent.clientFor(AgentConfig(endpoint!!, protocol = "harness")).health()
        assertTrue(health.error ?: "", health.connected)
        assertNotNull("a TLS certificate to pin", health.fingerprint)

        val applied = mutableListOf<AgentAction>()
        val items = object : AgentItems {
            override fun records() = listOf(
                EntityRecord("p_web", "project", "2026-10-06T00:00:00Z", mapOf("title" to "Website")),
                EntityRecord("t_host", "next_action", "2026-10-06T00:00:00Z",
                    mapOf("text" to "Pick a hosting provider", "parent" to "p_web",
                        "due" to java.time.LocalDate.now().toString())))
            override fun apply(actions: List<AgentAction>) { applied += actions }
        }
        val cfg = AgentConfig(endpoint, protocol = "harness", tlsFingerprint = health.fingerprint)
        val answer = Agent.ask(Agent.clientFor(cfg, items), cfg,
            "Which task in my Website project is due today? Then add the task " +
                "'Compare hosting prices' to the Website project.")
        assertFalse(answer, answer.startsWith("error:"))
        assertTrue(answer, answer.contains("hosting", ignoreCase = true))
        assertTrue(applied.toString(), applied.any { it.op == "add_task" && it.project == "p_web" })

        val wrongPin = AgentConfig(endpoint, protocol = "harness", tlsFingerprint = "00".repeat(32))
        assertFalse("a different certificate is refused", Agent.clientFor(wrongPin).health().connected)
    }
}
