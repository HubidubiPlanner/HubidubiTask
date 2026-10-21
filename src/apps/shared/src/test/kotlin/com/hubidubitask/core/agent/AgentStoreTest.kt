package com.hubidubitask.core.agent

import com.hubidubitask.core.agent.types.ClaudeAgentType
import com.hubidubitask.core.agent.types.HomeAgentType
import com.hubidubitask.core.agent.types.OpenAiAgentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AI settings: what each app always kept stays where it was, and the rules hold for every type. */
class AgentStoreTest {
    class Prefs(val values: MutableMap<String, String> = HashMap(), val secrets: MutableMap<String, String> = HashMap()) : AgentPrefs {
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun secret(name: String) = secrets[name]
        override fun setSecret(name: String, value: String) { secrets[name] = value }
    }

    @Test fun `what the phone kept before reads the same`() {
        val p = Prefs(mutableMapOf("protocol" to "harness", "harness_endpoint" to "https://192.168.1.50:18789",
            "harness_kind" to "openclaw", "harness_model" to "openclaw", "harness_fp" to "ab12",
            "endpoint" to "https://192.168.1.50:8080", "tls_fingerprint" to "cd34", "language" to "auto", "model" to "fast"),
            mutableMapOf("harness_token" to "tok", "claude_api_key" to "sk-ant-1", "agent_api_key" to "sk-2"))
        val s = AgentStore(p, AgentNames.PHONE)
        assertEquals(AgentConfig("https://192.168.1.50:18789", "harness", "openclaw", "tok", "ab12", "auto", "openclaw"), s.load())
        assertEquals(mapOf("host" to "192.168.1.50", "port" to "18789", "token" to "tok"), s.form(HomeAgentType))
        assertEquals(mapOf("apiKey" to "sk-ant-1"), s.form(ClaudeAgentType))
        assertEquals(mapOf("endpoint" to "https://192.168.1.50:8080"), s.form(OpenAiAgentType))
        assertEquals("sk-ant-1", s.claudeKey())
        p.values["protocol"] = "openai"
        // An old model name is not sent to a server that picks its own.
        assertEquals(AgentConfig("https://192.168.1.50:8080", "openai", "", "sk-2", "cd34", "auto"), s.load())
    }

    @Test fun `what the computer kept before reads the same`() {
        val p = Prefs(mutableMapOf("agent_protocol" to "openai", "agent_endpoint" to "https://10.0.0.5", "agent_fp" to "ef"),
            mutableMapOf("claude-api-key" to "sk-ant-9", "harness-token" to "t"))
        val s = AgentStore(p, AgentNames.COMPUTER)
        assertEquals(AgentConfig("https://10.0.0.5", "openai", "", null, "ef"), s.load())
        assertEquals("sk-ant-9", s.claudeKey())
        p.values["agent_protocol"] = "claude"
        assertEquals(AgentConfig("", "claude", AgentConfig.CLAUDE_MODEL, "sk-ant-9", null), s.load())
    }

    @Test fun `a choice the apps no longer offer reads as Claude`() {
        val s = AgentStore(Prefs(mutableMapOf("protocol" to "retired")), AgentNames.PHONE)
        assertEquals("claude", s.load().protocol)
        assertEquals(ClaudeAgentType, s.type())
    }

    @Test fun `switching types keeps each one's secret and address`() {
        val p = Prefs()
        val s = AgentStore(p, AgentNames.PHONE)
        s.keep(ClaudeAgentType, mapOf("apiKey" to " sk-ant-x "))
        s.keep(OpenAiAgentType, mapOf("endpoint" to "https://192.168.1.50:8080"))
        assertEquals("openai", s.load().protocol)
        assertEquals("sk-ant-x", s.claudeKey())
        s.keep(ClaudeAgentType, mapOf("apiKey" to "sk-ant-x"))
        assertEquals(mapOf("endpoint" to "https://192.168.1.50:8080"), s.form(OpenAiAgentType))
        assertEquals("claude", p.values["protocol"])
    }

    @Test fun `a new address drops the pin, and only a home address is pinned`() {
        val p = Prefs()
        val s = AgentStore(p, AgentNames.COMPUTER)
        s.keep(OpenAiAgentType, mapOf("endpoint" to "https://192.168.1.50:8080"))
        s.pin(s.load(), "aa", isPrivate = { true })
        assertEquals("aa", s.load().tlsFingerprint)
        s.keep(OpenAiAgentType, mapOf("endpoint" to "https://192.168.1.50:8080"))
        assertEquals("aa", s.load().tlsFingerprint, "the same address keeps its pin")
        s.keep(OpenAiAgentType, mapOf("endpoint" to "https://192.168.1.51:8080"))
        assertNull(s.load().tlsFingerprint, "a new address starts without one")
        s.pin(s.load(), "bb", isPrivate = { false })
        assertNull(s.load().tlsFingerprint, "a public address is never pinned")
        s.keep(ClaudeAgentType, mapOf("apiKey" to "k"))
        s.pin(s.load(), "cc", isPrivate = { true })
        assertNull(s.load().tlsFingerprint, "Claude is never pinned")
    }

    @Test fun `a new type keeps its settings under names of its own`() {
        val extra = object : AgentType {
            override val id = "example"
            override val label = "Example"
            override val fields = listOf(AgentField("apiKey", "key", secret = true))
            override val endpoint = "https://api.example.com"
            override fun ready(values: Map<String, String>) = values["apiKey"].orEmpty().isNotBlank()
            override fun client(config: AgentConfig): ModelClient = OpenAiClient(config)
        }
        val p = Prefs()
        val s = AgentStore(p, AgentNames.PHONE)
        s.keep(extra, mapOf("apiKey" to "k1"))
        assertEquals("k1", p.secrets["agent_example_secret"])
        assertEquals("example", p.values["protocol"])
        assertTrue(s.willCheck(extra, mapOf("apiKey" to "k1")))
    }

    @Test fun `every type has its own id, a name and fields`() {
        val ids = AgentTypes.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(listOf("claude", "openai", "harness"), ids)
        for (type in AgentTypes.all) {
            assertTrue(type.label.isNotBlank(), type.id)
            assertTrue(type.fields.isNotEmpty(), type.id)
            assertEquals(type.fields.map { it.key }.toSet().size, type.fields.size, type.id)
        }
    }
}
