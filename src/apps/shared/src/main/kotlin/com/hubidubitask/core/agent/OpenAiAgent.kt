package com.hubidubitask.core.agent

import com.hubidubitask.core.i18n.t

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

    private fun name() {
        if (!named) {
            client.health().models.firstOrNull()?.let { client = OpenAiClient(config.copy(model = it)) }
            named = true
        }
    }

    /**
     * A turn carrying a photo goes as it is, in the OpenAI shape, so the
     * image reaches the model; a plain question keeps the usual turn. Its
     * reply is only read: no changes come back from it.
     */
    override fun complete(messages: List<Message>): String {
        if (messages.none { it.images.isNotEmpty() }) return super.complete(messages)
        name()
        return client.complete(messages)
    }

    override fun turn(prompt: AgentPrompt): AgentReply {
        name()
        val text = listOf(prompt.instructions, prompt.context, "Question:\n" + prompt.question)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        return AgentActions.fromText(client.complete(listOf(Message("user", text))))
    }

    override fun health(): Health = client.health().let { h ->
        val server = kind.label.ifEmpty { h.models.firstOrNull() ?: h.server.orEmpty() }
        // Answering but with no model to ask (an Ollama with none pulled yet) can't answer a review.
        if (h.connected && !named && h.models.none { it.isNotBlank() })
            h.copy(connected = false, server = server, error = t("it has no model yet"))
        else h.copy(server = server)
    }
}
