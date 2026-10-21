package com.hubidubitask.core.agent.types

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentField
import com.hubidubitask.core.agent.AgentType
import com.hubidubitask.core.agent.OpenAiClient
import com.hubidubitask.core.i18n.t

/** Any OpenAI-compatible server, at the address the user types. It answers with the first model it lists. */
object OpenAiAgentType : AgentType {
    override val id = "openai"
    override val label = "OpenAI"
    override val fields get() = listOf(AgentField("endpoint", t("Server address")))
    // Kept where the apps kept a server's settings before there were types.
    override val scope = "server"

    override fun ready(values: Map<String, String>) = values["endpoint"].orEmpty().isNotBlank()
    override fun client(config: AgentConfig) = OpenAiClient(config)
}
