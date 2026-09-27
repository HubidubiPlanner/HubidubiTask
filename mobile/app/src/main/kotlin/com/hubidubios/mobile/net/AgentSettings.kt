package com.hubidubios.mobile.net

import android.content.Context
import com.hubidubios.core.agent.AgentConfig

/**
 * Where this phone's agent lives. Kept on the phone: the app asks the agent
 * directly, so the setting belongs here rather than on a workstation that
 * would have to be on for a question to be answered.
 */
object AgentSettings {
    private const val PREFS = "agent"

    fun load(context: Context): AgentConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val protocol = p.getString("protocol", null) ?: "selfhostedai"
        return AgentConfig(
            endpoint = p.getString("endpoint", null) ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = protocol,
            model = p.getString("model", null)?.takeIf { it.isNotBlank() }
                ?: AgentConfig.defaultModel(protocol),
            apiKey = p.getString("api_key", null),
            tlsFingerprint = p.getString("tls_fingerprint", null),
            language = p.getString("language", null) ?: "auto",
        )
    }

    fun save(context: Context, c: AgentConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", c.endpoint).putString("protocol", c.protocol)
            .putString("model", c.model).putString("api_key", c.apiKey)
            .putString("tls_fingerprint", c.tlsFingerprint).putString("language", c.language)
            .apply()
    }
}
