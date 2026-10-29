package com.hubidubitask.core.agent

import com.hubidubitask.core.agent.types.ClaudeAgentType
import com.hubidubitask.core.agent.types.HomeAgentType
import com.hubidubitask.core.agent.types.OpenAiAgentType
import com.hubidubitask.core.i18n.t

/**
 * A choice under AI Agent in Settings, on the phone and the computer alike
 * ([AgentTypes.all], in that order). Settings shows its [fields], keeps what
 * is typed ([AgentStore]) and checks that it answers.
 *
 * Every client but Claude's SDK speaks through [Agent.http]: one TLS policy
 * (a public host needs a certificate an authority vouches for; a home one is
 * pinned on first use; plain http only at home). Feeds use the chosen one
 * only when it can't run commands ([com.hubidubitask.core.feed.FeedWriter]).
 *
 * Texts shown to the user are getters that translate (t); a brand name stays
 * as it is.
 */
interface AgentType {
    /** Stored as the chosen protocol; never changes once shipped. */
    val id: String
    /** Its name in the dropdown. */
    val label: String
    /**
     * What Settings asks for, untitled: each placeholder says what goes in.
     * The keys "endpoint" (a server's address) and "apiKey" (kept in the
     * system's secret store) are kept for any type; others only by a type
     * that is found by address ([found]).
     */
    val fields: List<AgentField>
    /** Where its settings are kept ([AgentStore]); its id for any new type. */
    val scope: String get() = id
    /** The address of a service with one well-known address; null when the user types one. */
    val endpoint: String? get() = null
    /** The model asked for; blank lets an OpenAI-shaped server answer with its first. */
    val defaultModel: String get() = ""
    /** True only for Claude, reached through Anthropic's SDK rather than [Agent.http]. */
    val sdk: Boolean get() = false
    /** True for the user's own agent, which Settings finds from an address ([AgentStore.connectHome]). */
    val found: Boolean get() = false

    /** Whether [values] (by field key) are enough to try it; less is kept, not checked. */
    fun ready(values: Map<String, String>): Boolean

    fun client(config: AgentConfig): ModelClient

    /** What Settings says when a check found nothing answering. */
    fun silent(health: Health, endpoint: String): String =
        if (this.endpoint != null) t("Saved, but %s did not answer: %s", label, health.error ?: t("no reply"))
        else t("Saved, but nothing answered at %s — check the address and that the server is running there.", endpoint)
}

/**
 * One field of an [AgentType] in Settings. Fields with the same [row] sit
 * side by side, sized by [weight]; [secret] hides what is typed; [digits]
 * keeps only digits.
 */
data class AgentField(
    val key: String,
    val placeholder: String,
    val secret: Boolean = false,
    val digits: Boolean = false,
    val row: Int = 0,
    val weight: Float = 1f,
)

/** Every AI choice, in the order Settings offers them; the first is the default. */
object AgentTypes {
    val all: List<AgentType> = listOf(ClaudeAgentType, OpenAiAgentType, HomeAgentType)

    /** The type stored as [id]; one this version doesn't offer reads as the first, Claude. */
    fun of(id: String?): AgentType = all.firstOrNull { it.id == id } ?: all.first()
}
