package com.hubidubitask.core.agent

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ClaudeClient against a stand-in for Anthropic's API on localhost. */
class ClaudeClientTest {
    private var status = 200
    private var reply = message("end_turn", "Buy milk first.")
    private var lastBody: JSONObject? = null
    private var lastBeta: String? = null
    private var lastKey: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { ex ->
            lastBody = JSONObject(ex.requestBody.readBytes().decodeToString())
            lastBeta = ex.requestHeaders.getFirst("anthropic-beta")
            lastKey = ex.requestHeaders.getFirst("x-api-key")
            val out = (if (status == 200) reply else error(status)).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        createContext("/v1/models/") { ex ->
            val out = (if (status == 200) """{"type":"model","id":"claude-opus-5-5",
                "display_name":"Claude Opus 5.5","created_at":"2026-08-01T00:00:00Z"}""" else error(status)).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    private fun client(key: String? = "sk-ant-test") =
        ClaudeClient(AgentConfig(endpoint = "", protocol = "claude", apiKey = key), base)

    @Test fun `asks Claude Opus 5_5 with the system prompt, low effort and the default fallback`() {
        val answer = client().complete(listOf(
            Message("system", "Be brief."), Message("system", "Open: buy milk"),
            Message("user", "What first?")))
        assertEquals("Buy milk first.", answer)
        val body = lastBody!!
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("Be brief.\n\nOpen: buy milk", body.getString("system"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals(1, body.getJSONArray("messages").length())
        assertTrue(lastBeta!!.contains("server-side-fallback-2026-07-01"))
        assertEquals("sk-ant-test", lastKey)
    }

    @Test fun `a refusal is reported, not shown as an answer`() {
        reply = message("refusal", "")
        val e = assertFailsWith<AgentError> { client().complete(listOf(Message("user", "x"))) }
        assertTrue(e.message!!.contains("declined"))
    }

    @Test fun `a refused key says to check the key`() {
        status = 401
        val e = assertFailsWith<AgentError> { client().complete(listOf(Message("user", "x"))) }
        assertTrue(e.message!!.contains("API key"))
    }

    @Test fun `health is connected when the model exists, and asks for a key when there is none`() {
        assertTrue(client().health().connected)
        val none = client(key = null).health()
        assertFalse(none.connected)
        assertEquals("no API key", none.error)
    }

    @Test fun `Settings offers Claude, OpenAI and Agent, in that order, Claude by default`() {
        assertEquals(listOf("claude" to "Claude", "openai" to "OpenAI", "harness" to "Agent"),
            AgentConfig.PROTOCOLS)
        assertEquals(AgentConfig.PROTOCOLS.first().first, AgentConfig.DEFAULT_PROTOCOL)
        assertEquals("claude", AgentConfig(endpoint = "").protocol)
        assertTrue(Agent.clientFor(AgentConfig(endpoint = "", protocol = "claude")) is ClaudeClient)
    }

    private fun message(stop: String, text: String) = """{"id":"msg_1","type":"message",
        "role":"assistant","model":"claude-opus-5-5","stop_reason":"$stop","stop_sequence":null,
        "content":[{"type":"text","text":"$text","citations":null}],
        "usage":{"input_tokens":10,"output_tokens":5}}"""

    private fun error(code: Int) = """{"type":"error","error":{"type":"${
        if (code == 401) "authentication_error" else "api_error"}","message":"nope"}}"""
}
