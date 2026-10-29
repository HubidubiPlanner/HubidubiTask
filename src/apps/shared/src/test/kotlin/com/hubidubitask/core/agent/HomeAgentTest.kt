package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import org.json.JSONObject
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The user's own agent in the OpenAI shape (OpenClaw, Hermes, any other). */
class HomeAgentTest {
    private var content = "Pick the cheaper host.\n\n```json\n" +
        """{"actions":[{"op":"add_task","text":"Compare prices","project":"Website"},{"op":"mark_done","id":"t_host"}]}""" +
        "\n```"
    private var status = 200
    private val stub = Stub { c ->
        when (c.path) {
            "/v1/chat/completions" -> status to (if (status == 200)
                JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                    JSONObject().put("role", "assistant").put("content", content)))).toString()
                else """{"error":{"message":"The gateway is asleep."}}""")
            "/v1/models" -> 200 to """{"data":[{"id":"hermes-agent"}]}"""
            else -> null
        }
    }
    @AfterTest fun stop() = stub.close()

    private val applied = mutableListOf<List<AgentAction>>()
    private val items = object : AgentItems {
        override fun records() = listOf(
            EntityRecord("p_web", "project", "2026-10-11T00:00:00Z", mapOf("title" to "Website")),
            EntityRecord("t_host", "next_action", "2026-10-11T00:00:00Z", mapOf("text" to "Pick a host")))
        override fun apply(actions: List<AgentAction>) { applied += actions }
    }
    private val today = LocalDate.of(2026, 10, 11)
    private fun cfg(kind: AgentKind = AgentKind.OPENCLAW, model: String = "openclaw") =
        AgentConfig(endpoint = stub.base, protocol = "harness", kind = kind.id, model = model, apiKey = "s3cret")
    private fun ask(c: AgentConfig = cfg()) = Agent.ask(Agent.clientFor(c), c, "What next?", items, today)
    private fun chats() = stub.calls.filter { it.path == "/v1/chat/completions" }

    @Test fun `one user message carries how to answer, the items and the question`() {
        assertEquals("Pick the cheaper host.", ask())
        val call = chats().single()
        assertEquals("Bearer s3cret", call.auth)
        val body = JSONObject(call.body)
        assertEquals("openclaw", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val text = messages.getJSONObject(0).getString("content")
        assertTrue("fenced json" in text && "## Project: Website (id p_web)" in text && text.endsWith("Question:\nWhat next?"))
        assertFalse(body.has("hubidubitask_tools") || body.has("hubidubitask_items"))
    }

    @Test fun `its changes are checked and applied once`() {
        ask()
        assertEquals(listOf(listOf(AgentAction("add_task", "Compare prices", "p_web"),
            AgentAction("mark_done", id = "t_host"))), applied)
    }

    @Test fun `a reply that only changed things is not asked again`() {
        content = "<think>quietly</think>\n```json\n{\"actions\":[{\"op\":\"capture\",\"text\":\"Milk\"}]}\n```"
        assertEquals("+ Milk", ask())
        assertEquals(1, chats().size)
        assertEquals(listOf(listOf(AgentAction("capture", "Milk"))), applied)
    }

    @Test fun `a reply with nothing in it is asked for once more`() {
        content = "<think>and never finished"
        assertTrue(ask().startsWith("error: "))
        assertEquals(2, chats().size)
        assertTrue(applied.isEmpty())
    }

    @Test fun `the agent's own words reach the person when it fails`() {
        status = 503
        assertEquals("error: The gateway is asleep.", ask())
    }

    @Test fun `without a model name, the first the agent lists is used`() {
        ask(cfg(AgentKind.HERMES, model = ""))
        assertEquals("hermes-agent", JSONObject(chats().single().body).getString("model"))
        assertEquals("Hermes", Agent.clientFor(cfg(AgentKind.HERMES)).health().server)
        assertEquals("Ollama", Agent.clientFor(cfg(AgentKind.OLLAMA, "")).health().server)
        assertEquals("hermes-agent", Agent.clientFor(cfg(AgentKind.OPENAI, "")).health().server)
    }

    @Test fun `only Ollama is taken to just answer, any other agent may run commands`() {
        assertEquals(listOf(AgentKind.OLLAMA), AgentKind.entries.filter { !it.runsCommands })
    }
}
