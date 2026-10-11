package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import org.json.JSONObject
import java.time.LocalDate
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** opencode's server, and Kilo's: a session per question, deleted after. */
class OpencodeAgentTest {
    private var answer = """{"info":{"structured_output":{"answer":"Book the flights today.",
        "actions":[{"op":"add_task","text":"Book flights","project":"p_lis","due":"2026-10-12"}]}},"parts":[]}"""
    private val stub = Stub { c ->
        when {
            c.method == "POST" && c.path == "/session" -> 200 to """{"id":"s1"}"""
            c.method == "POST" && c.path == "/session/s1/message" -> 200 to answer
            c.method == "DELETE" && c.path == "/session/s1" -> 200 to "true"
            c.path == "/global/health" -> 200 to """{"healthy":true,"version":"1.19.0"}"""
            else -> null
        }
    }
    @AfterTest fun stop() = stub.close()

    private val applied = mutableListOf<AgentAction>()
    private val items = object : AgentItems {
        override fun records() = listOf(EntityRecord("p_lis", "project", "2026-10-11T00:00:00Z", mapOf("title" to "Trip to Lisbon")))
        override fun apply(actions: List<AgentAction>) { applied += actions }
    }
    private fun cfg(kind: AgentKind = AgentKind.OPENCODE) =
        AgentConfig(endpoint = stub.base, protocol = "harness", kind = kind.id, apiKey = "pw")
    private fun ask(c: AgentConfig = cfg()) = Agent.ask(Agent.clientFor(c), c, "What now?", items, LocalDate.of(2026, 10, 11))

    @Test fun `a session is made, asked with the schema, and deleted`() {
        assertEquals("Book the flights today.", ask())
        assertEquals(listOf("POST /session", "POST /session/s1/message", "DELETE /session/s1"),
            stub.calls.map { "${it.method} ${it.path}" })
        val basic = "Basic " + Base64.getEncoder().encodeToString("opencode:pw".toByteArray())
        assertTrue(stub.calls.all { it.auth == basic })
        val body = JSONObject(stub.calls[1].body)
        assertTrue("\"answer\"" in body.getString("system"))
        assertEquals("json_schema", body.getJSONObject("format").getString("type"))
        assertEquals(AgentActions.SCHEMA.toString(), body.getJSONObject("format").getJSONObject("schema").toString())
        val text = body.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue("## Project: Trip to Lisbon (id p_lis)" in text && text.endsWith("Question:\nWhat now?"))
        assertEquals(listOf(AgentAction("add_task", "Book flights", "p_lis", "2026-10-12")), applied)
    }

    @Test fun `without a structured answer, its text is read like any other`() {
        answer = """{"info":{},"parts":[{"type":"reasoning","text":"hm"},{"type":"text","text":"Pack light.\n```json\n{\"actions\":[{\"op\":\"capture\",\"text\":\"Adapter\"}]}\n```"}]}"""
        assertEquals("Pack light.", ask())
        assertEquals(listOf(AgentAction("capture", "Adapter")), applied)
    }

    @Test fun `its error reaches the person, and the session still goes`() {
        answer = """{"info":{"error":{"name":"StructuredOutputError","data":{"message":"The model did not follow the schema."}}},"parts":[]}"""
        assertEquals("error: The model did not follow the schema.", ask())
        assertTrue(stub.calls.any { it.method == "DELETE" })
    }

    @Test fun `Kilo signs in as kilo, and health names it with its version`() {
        val c = cfg(AgentKind.KILO)
        ask(c)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("kilo:pw".toByteArray()), stub.calls.first().auth)
        val h = Agent.clientFor(c).health()
        assertTrue(h.connected); assertEquals("Kilo 1.19.0", h.server)
    }
}
