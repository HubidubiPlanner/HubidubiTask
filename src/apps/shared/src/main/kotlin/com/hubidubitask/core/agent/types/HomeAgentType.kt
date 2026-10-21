package com.hubidubitask.core.agent.types

import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentField
import com.hubidubitask.core.agent.AgentType
import com.hubidubitask.core.agent.HomeAgent
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.i18n.t

/**
 * The agent the user already runs on a computer at home (OpenClaw, Hermes,
 * opencode, Kilo, any OpenAI-shaped one; see AgentKind): its IP, an optional
 * port and its token. Which one listens there is found, not asked
 * (AgentProbe), and it is asked with the user's items and may change them
 * (AgentActions). Stored as "harness", its name before it was called Agent.
 */
object HomeAgentType : AgentType {
    override val id = "harness"
    override val label get() = t("Agent")
    override val fields get() = listOf(
        AgentField("host", t("IP address"), row = 0, weight = 2f),
        AgentField("port", t("Port"), digits = true, row = 0, weight = 1f),
        AgentField("token", t("Token or password"), secret = true, row = 1),
    )
    override val found = true

    override fun ready(values: Map<String, String>) = values["host"].orEmpty().isNotBlank()
    override fun client(config: AgentConfig): ModelClient = HomeAgent.clientFor(config)
}
