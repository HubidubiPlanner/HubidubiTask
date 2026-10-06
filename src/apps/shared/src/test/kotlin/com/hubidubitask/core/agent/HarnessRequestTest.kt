package com.hubidubitask.core.agent

import com.hubidubitask.core.EntityRecord
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the apps ask the harness for: tools and items for chat and reviews, none for feeds. */
class HarnessRequestTest {
    private var body: JSONObject? = null
    private var reply = """{"choices":[{"message":{"role":"assistant","content":"Ship the site"}}],
        "hubidubitask_actions":[{"op":"add_task","text":"Compare prices","project":"p_web","due":null},
                                {"op":"mark_done","id":"t_host"},{"nope":1}]}"""
    private var status = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { ex ->
            body = JSONObject(ex.requestBody.readBytes().decodeToString())
            val out = reply.toByteArray()
            ex.sendResponseHeaders(status, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    private val applied = mutableListOf<HarnessAction>()
    private val items = object : HarnessItems {
        override fun records() = listOf(
            EntityRecord("p_web", "project", "2026-10-06T00:00:00Z", mapOf("title" to "Website")),
            EntityRecord("t_host", "next_action", "2026-10-06T00:00:00Z", mapOf("text" to "Pick a host", "due" to null)),
            EntityRecord("f_1", "feed", "2026-10-06T00:00:00Z", mapOf("url" to "https://example.com")),
            EntityRecord("t_gone", "next_action", "2026-10-06T00:00:00Z", deleted = true))
        override fun apply(actions: List<HarnessAction>) { applied += actions }
    }

    @AfterTest fun stop() = server.stop(0)

    @Test fun `chat sends the items, asks for tools, and applies what the harness changed`() {
        val cfg = AgentConfig(endpoint = base, protocol = "harness")
        assertEquals("Ship the site", Agent.ask(Agent.clientFor(cfg, items), cfg, "What next?"))
        assertTrue(body!!.getBoolean("hubidubitask_tools"))
        val sent = body!!.getJSONArray("hubidubitask_items")
        assertEquals(listOf("p_web", "t_host"), (0 until sent.length()).map { sent.getJSONObject(it).getString("id") })
        assertFalse(sent.getJSONObject(1).getJSONObject("fields").has("due"), "no nulls on the wire")
        val system = body!!.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("with your tools"))
        assertEquals(listOf(HarnessAction("add_task", text = "Compare prices", project = "p_web"),
            HarnessAction("mark_done", id = "t_host")), applied)
    }

    @Test fun `a feed's request asks for no tools and carries no items`() {
        val cfg = AgentConfig(endpoint = base, protocol = "harness", tools = false)
        Agent.clientFor(cfg, items).complete(listOf(Message("user", "x")))
        assertFalse(body!!.getBoolean("hubidubitask_tools"))
        assertFalse(body!!.has("hubidubitask_items"))
    }

    @Test fun `other servers never see the fields`() {
        val cfg = AgentConfig(endpoint = base, protocol = "openai")
        Agent.clientFor(cfg, items).complete(listOf(Message("user", "x")))
        assertFalse(body!!.has("hubidubitask_tools"))
        assertFalse(body!!.has("hubidubitask_items"))
        assertTrue(applied.isEmpty())
    }

    @Test fun `the harness's own words reach the person when it fails`() {
        status = 502; reply = """{"error":{"message":"Claude Code is not installed on the harness computer."}}"""
        val cfg = AgentConfig(endpoint = base, protocol = "harness")
        assertEquals("error: Claude Code is not installed on the harness computer.",
            Agent.ask(Agent.clientFor(cfg, items), cfg, "What next?"))
    }

    @Test fun `an IP and a port make the address, and come back out of it`() {
        assertEquals("https://192.168.1.50:8765", HarnessLink.endpoint(" 192.168.1.50 ", "8765"))
        assertEquals("https://192.168.1.50:9000", HarnessLink.endpoint("192.168.1.50:9000", "8765"))
        assertEquals("https://agent.local:8765", HarnessLink.endpoint("https://agent.local/", ""))
        assertEquals("https://[fd00::5]:8765", HarnessLink.endpoint("fd00::5", "8765"))
        assertEquals("", HarnessLink.endpoint("  ", "8765"))
        assertEquals("192.168.1.50" to "9000", HarnessLink.split("https://192.168.1.50:9000"))
        assertEquals("fd00::5" to "8765", HarnessLink.split("https://[fd00::5]:8765"))
        assertEquals("" to "8765", HarnessLink.split(null))
    }
}
