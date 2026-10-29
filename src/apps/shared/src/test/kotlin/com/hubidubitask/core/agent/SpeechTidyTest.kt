package com.hubidubitask.core.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A voice note's words tidied by each kind of agent: only the words go, and only words come back. */
class SpeechTidyTest {
    private val heard = "jutro rano zadzwonić do księgowej w sprawie faktury za październik potem wysłać umowę"
    private var content = "Faktura i umowa\n\nJutro rano zadzwonić do księgowej w sprawie faktury za październik. " +
        "Potem wysłać umowę."
    private val stub = Stub { c ->
        when (c.path) {
            "/v1/chat/completions" -> 200 to JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()
            "/v1/models" -> 200 to """{"data":[{"id":"some-model"}]}"""
            else -> null
        }
    }
    @AfterTest fun stop() = stub.close()

    private fun tidy(c: AgentConfig) = Agent.tidySpeech(Agent.clientFor(c), heard)
    private val openai get() = AgentConfig(endpoint = stub.base, protocol = "openai", model = "some-model")

    @Test fun `an OpenAI-compatible server gets the rules and then the words`() {
        val note = tidy(openai)
        assertEquals("Faktura i umowa", note.title)
        assertTrue(note.body.startsWith("Jutro rano zadzwonić"))
        val messages = JSONObject(stub.calls.single { it.path == "/v1/chat/completions" }.body).getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue("language it was spoken in" in messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(heard, messages.getJSONObject(1).getString("content"))
    }

    @Test fun `the user's own agent gets the words, and none of their items`() {
        val c = AgentConfig(endpoint = stub.base, protocol = "harness", kind = AgentKind.OPENCLAW.id, apiKey = "s3cret")
        assertEquals("Faktura i umowa", tidy(c).title)
        val body = stub.calls.single { it.path == "/v1/chat/completions" }.body
        assertTrue(heard in body)
        assertTrue("## Project" !in body)
    }

    @Test fun `a reply much shorter than what was said is not taken`() {
        content = "Faktura\n\nZadzwonić."
        assertFailsWith<AgentError> { tidy(openai) }
    }

    @Test fun `no text back is an error, after one retry`() {
        content = ""
        assertFailsWith<AgentError> { tidy(openai) }
        assertEquals(2, stub.calls.count { it.path == "/v1/chat/completions" })
    }
}
