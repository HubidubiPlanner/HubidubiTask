package com.hubidubitask.core.agent

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the apps ask the harness for: tools for chat and reviews, none for feeds. */
class HarnessRequestTest {
    private var body: JSONObject? = null
    private var auth: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { ex ->
            body = JSONObject(ex.requestBody.readBytes().decodeToString())
            auth = ex.requestHeaders.getFirst("Authorization")
            val out = """{"choices":[{"message":{"role":"assistant","content":"Ship the site"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun stop() = server.stop(0)

    @Test fun `chat with the harness asks for tools and says so in its instructions`() {
        val cfg = AgentConfig(endpoint = base, protocol = "harness", apiKey = "tok")
        assertEquals("Ship the site", Agent.ask(Agent.clientFor(cfg), cfg, "What next?"))
        assertTrue(body!!.getBoolean("hubidubios_tools"))
        assertEquals("Bearer tok", auth)
        val system = body!!.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("with your tools"))
    }

    @Test fun `a feed's request asks for no tools`() {
        val cfg = AgentConfig(endpoint = base, protocol = "harness", apiKey = "tok", tools = false)
        Agent.clientFor(cfg).complete(listOf(Message("user", "x")))
        assertFalse(body!!.getBoolean("hubidubios_tools"))
    }

    @Test fun `other servers never see the field`() {
        val cfg = AgentConfig(endpoint = base, protocol = "openai")
        Agent.clientFor(cfg).complete(listOf(Message("user", "x")))
        assertFalse(body!!.has("hubidubios_tools"))
    }
}
