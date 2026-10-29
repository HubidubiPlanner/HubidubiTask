package com.hubidubitask.core.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A photo read by each kind of agent: the image reaches it, and only words come back. */
class PhotoReadTest {
    private var content = "Whiteboard\n\n1. Ship the widget\n2. Write the notes"
    private val stub = Stub { c ->
        when {
            c.path == "/v1/chat/completions" -> 200 to JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()
            c.path == "/v1/models" -> 200 to """{"data":[{"id":"vision-model"}]}"""
            c.method == "POST" && c.path == "/session" -> 200 to """{"id":"s1"}"""
            c.method == "POST" && c.path == "/session/s1/message" -> 200 to JSONObject()
                .put("info", JSONObject())
                .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", content))).toString()
            c.method == "DELETE" && c.path == "/session/s1" -> 200 to "true"
            else -> null
        }
    }
    @AfterTest fun stop() = stub.close()

    private val photo = Picture(byteArrayOf(9, 8, 7), "image/jpeg")
    private fun read(c: AgentConfig) = Agent.readPhoto(Agent.clientFor(c), c, photo.bytes, photo.mime, "English")

    @Test fun `an OpenAI-compatible server gets the image as an image_url part`() {
        val note = read(AgentConfig(endpoint = stub.base, protocol = "openai", model = "vision-model"))
        assertEquals("Whiteboard", note.title)
        assertEquals("1. Ship the widget\n2. Write the notes", note.body)
        val messages = JSONObject(stub.calls.single { it.path == "/v1/chat/completions" }.body).getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals(photo.dataUrl, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun `the user's own OpenAI-shaped agent gets the image as it is, and no items`() {
        val c = AgentConfig(endpoint = stub.base, protocol = "harness", kind = AgentKind.OPENCLAW.id, apiKey = "s3cret")
        assertEquals("Whiteboard", read(c).title)
        val call = stub.calls.single { it.path == "/v1/chat/completions" }
        assertEquals("Bearer s3cret", call.auth)
        val messages = JSONObject(call.body).getJSONArray("messages")
        assertEquals("vision-model", JSONObject(call.body).getString("model"))
        assertEquals(photo.dataUrl, messages.getJSONObject(1).getJSONArray("content")
            .getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue("## Project" !in call.body)
    }

    @Test fun `opencode gets the image as a file part, without a schema, and the session goes`() {
        val c = AgentConfig(endpoint = stub.base, protocol = "harness", kind = AgentKind.OPENCODE.id, apiKey = "pw")
        assertEquals("Whiteboard", read(c).title)
        assertEquals(listOf("POST /session", "POST /session/s1/message", "DELETE /session/s1"),
            stub.calls.map { "${it.method} ${it.path}" })
        val body = JSONObject(stub.calls[1].body)
        assertTrue(!body.has("format"))
        assertTrue("photo into a note" in body.getString("system"))
        val file = body.getJSONArray("parts").getJSONObject(1)
        assertEquals("file", file.getString("type"))
        assertEquals("image/jpeg", file.getString("mime"))
        assertEquals(photo.dataUrl, file.getString("url"))
    }

    @Test fun `a reply with no words is an error, so the photo can be kept instead`() {
        content = "<think>looking</think>"
        assertFailsWith<AgentError> { read(AgentConfig(endpoint = stub.base, protocol = "openai", model = "vision-model")) }
    }
}
