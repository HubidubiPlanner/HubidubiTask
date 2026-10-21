package com.hubidubitask.core.agent.types

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentField
import com.hubidubitask.core.agent.AgentType
import com.hubidubitask.core.agent.ClaudeClient
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.i18n.t

/** Claude, through Anthropic's API, with the user's own API key. */
object ClaudeAgentType : AgentType {
    override val id = "claude"
    override val label = "Claude"
    override val fields = listOf(AgentField("apiKey", "sk-ant-…", secret = true))
    override val endpoint = ""
    override val defaultModel = AgentConfig.CLAUDE_MODEL
    override val sdk = true

    override fun ready(values: Map<String, String>) = values["apiKey"].orEmpty().isNotBlank()
    override fun client(config: AgentConfig) = ClaudeClient(config)
    override fun silent(health: Health, endpoint: String) =
        t("Saved, but Claude did not answer: %s", health.error ?: t("no reply"))
}
