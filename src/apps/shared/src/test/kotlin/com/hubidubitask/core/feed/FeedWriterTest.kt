package com.hubidubitask.core.feed

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentKind
import com.hubidubitask.core.agent.ClaudeClient
import com.hubidubitask.core.agent.OpenAiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Who writes a feed's task: the chosen AI, unless it can run commands. */
class FeedWriterTest {
    private val claude = AgentConfig(endpoint = "", protocol = "claude", apiKey = "sk-ant-chosen")
    private val openai = AgentConfig(endpoint = "http://192.168.0.40:8000", protocol = "openai", model = "qwen")
    private fun home(kind: AgentKind) =
        AgentConfig(endpoint = "http://192.168.0.40:${kind.port ?: 4097}", protocol = "harness", kind = kind.id,
            apiKey = "home-token")

    @Test fun `Claude chosen writes with Claude and its key`() {
        assertEquals(claude, FeedWriter.configFor(claude, "sk-ant-other"))
        assertTrue(FeedWriter.clientFor(claude, null) is ClaudeClient)
        assertEquals("Claude", FeedWriter.label(claude))
    }

    @Test fun `an OpenAI-compatible server chosen writes as a plain chat`() {
        assertEquals(openai, FeedWriter.configFor(openai, "sk-ant"))
        assertTrue(FeedWriter.clientFor(openai, "sk-ant") is OpenAiClient)
        assertEquals("OpenAI", FeedWriter.label(openai))
    }

    @Test fun `Ollama at home only answers, so it writes, as a plain chat at its address`() {
        val ollama = home(AgentKind.OLLAMA)
        assertEquals(ollama, FeedWriter.configFor(ollama, "sk-ant"))
        assertTrue(FeedWriter.clientFor(ollama, "sk-ant") is OpenAiClient)
        assertEquals("Ollama", FeedWriter.label(ollama))
    }

    @Test fun `an agent that can run commands never reads a feed - Claude does, with the Claude key`() {
        for (kind in listOf(AgentKind.OPENCLAW, AgentKind.HERMES, AgentKind.OPENCODE, AgentKind.KILO, AgentKind.OPENAI)) {
            val c = FeedWriter.configFor(home(kind), "sk-ant-feeds")
            assertEquals("claude", c.protocol, kind.id)
            assertEquals("sk-ant-feeds", c.apiKey, kind.id)
            assertTrue(FeedWriter.clientFor(home(kind), "sk-ant-feeds") is ClaudeClient, kind.id)
            assertEquals("Claude", FeedWriter.label(home(kind)), kind.id)
        }
    }

    @Test fun `a home agent of no known kind counts as one that can run commands`() {
        val unknown = AgentConfig(endpoint = "http://192.168.0.40:9000", protocol = "harness", kind = null)
        assertEquals("claude", FeedWriter.configFor(unknown, "k").protocol)
    }
}
