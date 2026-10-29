package com.hubidubitask.core.feed

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentKind
import com.hubidubitask.core.agent.AgentTypes
import com.hubidubitask.core.agent.ClaudeClient
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.agent.OpenAiClient
import com.hubidubitask.core.agent.types.ClaudeAgentType
import com.hubidubitask.core.agent.types.HomeAgentType
import com.hubidubitask.core.agent.types.OpenAiAgentType

/**
 * Who writes a feed's task: the AI chosen in Settings, unless it may act on
 * what it reads. A feed's text is a stranger's web page, and the user's own
 * agent (OpenClaw, Hermes, opencode, Kilo, or a server the app can't tell)
 * can run commands on their computer; with one of those chosen, Claude
 * writes it, with the user's Claude key. Claude, an OpenAI-compatible server
 * and Ollama only answer.
 */
object FeedWriter {

    /** The settings the task is written with. */
    fun configFor(chosen: AgentConfig, claudeKey: String?): AgentConfig =
        if (answersOnly(chosen)) chosen else FeedRunner.feedConfig(claudeKey)

    /**
     * Its client. An OpenAI-compatible server and Ollama are asked as a
     * plain chat, so nothing in the reply is ever taken as a change.
     */
    fun clientFor(chosen: AgentConfig, claudeKey: String?): ModelClient {
        val c = configFor(chosen, claudeKey)
        return if (c.protocol == ClaudeAgentType.id) ClaudeClient(c) else OpenAiClient(c)
    }

    /** Its name, for the feed's sheet. */
    fun label(chosen: AgentConfig): String = when {
        !answersOnly(chosen) -> ClaudeAgentType.label
        chosen.protocol == HomeAgentType.id -> AgentKind.of(chosen.kind).label
        else -> AgentTypes.of(chosen.protocol).label
    }

    private fun answersOnly(c: AgentConfig) = when (c.protocol) {
        ClaudeAgentType.id, OpenAiAgentType.id -> true
        HomeAgentType.id -> !AgentKind.of(c.kind).runsCommands
        else -> false
    }
}
