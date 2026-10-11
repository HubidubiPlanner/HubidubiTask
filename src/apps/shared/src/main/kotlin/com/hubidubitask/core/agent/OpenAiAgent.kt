package com.hubidubitask.core.agent

/**
 * A user's agent that speaks the OpenAI chat shape: OpenClaw's gateway,
 * Hermes Agent's API server, or any other. The agent keeps its own system
 * prompt and tools, so the question goes as one user message carrying how to
 * answer, the items, and the question; changes come back in a fenced block.
 */
class OpenAiAgent(private val config: AgentConfig) : HomeAgentClient {
    override val kind = AgentKind.of(config.kind)

    // Without a model name, the first one the agent lists is used.
    @Volatile private var client = OpenAiClient(config)
    @Volatile private var named = config.model.isNotBlank()

    override fun turn(prompt: AgentPrompt): AgentReply {
        if (!named) {
            client.health().models.firstOrNull()?.let { client = OpenAiClient(config.copy(model = it)) }
            named = true
        }
        val text = listOf(prompt.instructions, prompt.context, "Question:\n" + prompt.question)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return AgentActions.fromText(client.complete(listOf(Message("user", text))))
    }

    override fun health(): Health = client.health().let { h ->
        h.copy(server = kind.label.ifEmpty { h.models.firstOrNull() ?: h.server.orEmpty() })
    }
}
