package com.hubidubitask.core.agent

import org.json.JSONObject
import java.util.Base64

/**
 * How a kind of agent is told apart without its token: a GET on [path] that
 * answers 2xx with a reply [matches] accepts.
 */
class Signature(val path: String, val matches: (JSONObject?) -> Boolean)

/**
 * The agents the user may already run at home that listen on the network by
 * themselves, so nothing of ours is installed there. Each is recognised from
 * its address alone (see [AgentProbe]). [port] is where it listens unless told
 * otherwise (tried in this order when no port is typed); [user] marks the ones
 * that take a password as Basic auth.
 *
 * A new kind that speaks the OpenAI chat shape needs only an entry here with
 * its [signature]: [AgentProbe] tries every signature before taking a server
 * for a plain OpenAI-shaped one, and [HomeAgent] asks it through [OpenAiAgent].
 * OpenClaw, Hermes, opencode and Kilo are told apart by AgentProbe itself.
 *
 * Others could join the same way: goose ("goose serve", ACP over HTTP, 3284),
 * Qwen Code ("qwen serve", 4170), Codex's app-server (WebSocket).
 */
enum class AgentKind(val id: String, val label: String, val port: Int?, private val user: String?,
                     val signature: Signature? = null) {
    OPENCLAW("openclaw", "OpenClaw", 18789, null),
    HERMES("hermes", "Hermes", 8642, null),
    OPENCODE("opencode", "opencode", 4096, "opencode"),
    KILO("kilo", "Kilo", null, "kilo"),
    /** Ollama lists its models at /api/tags, with no token; it chats at /v1 like OpenAI. */
    OLLAMA("ollama", "Ollama", 11434, null, Signature("/api/tags") { it?.optJSONArray("models") != null }),
    /** Any other server speaking the OpenAI chat shape. Keep it last. */
    OPENAI("openai", "", null, null);

    /**
     * Whether it may act on what it reads: run commands, change files, go on
     * the web. Ollama only answers; a server the app can't tell is taken to.
     */
    val runsCommands: Boolean get() = this != OLLAMA

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
        val DEFAULT_PORTS: List<Int> get() = entries.mapNotNull { it.port }
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
