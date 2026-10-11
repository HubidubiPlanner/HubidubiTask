package com.hubidubitask.core.agent

import java.util.Base64

/**
 * The agents the user may already run at home that listen on the network by
 * themselves, so nothing of ours is installed there. Each is recognised from
 * its address alone (see [AgentProbe]). [port] is where it listens unless told
 * otherwise; [user] marks the ones that take a password as Basic auth.
 *
 * Others could join the same way: goose ("goose serve", ACP over HTTP, 3284),
 * Qwen Code ("qwen serve", 4170), Codex's app-server (WebSocket).
 */
enum class AgentKind(val id: String, val label: String, val port: Int?, private val user: String?) {
    OPENCLAW("openclaw", "OpenClaw", 18789, null),
    HERMES("hermes", "Hermes", 8642, null),
    OPENCODE("opencode", "opencode", 4096, "opencode"),
    KILO("kilo", "Kilo", null, "kilo"),
    /** Any other server speaking the OpenAI chat shape. */
    OPENAI("openai", "", null, null);

    /** Whether it can be held to a JSON schema, so changes need no fenced block. */
    val structured: Boolean get() = this == OPENCODE || this == KILO

    /** The Authorization header for [token]: Basic for a password, Bearer for a token. */
    fun authorization(token: String?): String? = token?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (user != null) "Basic " + Base64.getEncoder().encodeToString("$user:$it".toByteArray())
        else "Bearer $it"
    }

    companion object {
        fun of(id: String?): AgentKind = entries.firstOrNull { it.id == id } ?: OPENAI

        /** Tried in this order when no port was typed. */
        val DEFAULT_PORTS = listOf(18789, 8642, 4096)
    }
}

/** One question for the user's agent: how to answer, their items, and the question. */
data class AgentPrompt(val instructions: String, val context: String, val question: String)

/** The user's own agent. [turn] only asks; what it changes is applied by the caller. */
interface HomeAgentClient : ModelClient {
    val kind: AgentKind
    fun turn(prompt: AgentPrompt): AgentReply

    override fun complete(messages: List<Message>): String = turn(AgentPrompt(
        messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }, "",
        messages.filter { it.role != "system" }.joinToString("\n\n") { it.content })).text
}

object HomeAgent {
    fun clientFor(config: AgentConfig): HomeAgentClient = when (AgentKind.of(config.kind)) {
        AgentKind.OPENCODE, AgentKind.KILO -> OpencodeAgent(config)
        else -> OpenAiAgent(config)
    }
}
