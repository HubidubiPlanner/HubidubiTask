package com.hubidubios.mobile.net

import android.content.Context
import com.hubidubios.core.agent.AgentConfig
import com.hubidubios.mobile.security.Keystore

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
            // Claude's key is a paid credential, so it lives in the Keystore,
            // not in plain preferences beside the other agents' settings.
            apiKey = if (protocol == "claude") claudeKey(context) else p.getString("api_key", null),
            tlsFingerprint = p.getString("tls_fingerprint", null),
            language = p.getString("language", null) ?: "auto",
        )
    }

    fun save(context: Context, c: AgentConfig) {
        if (c.protocol == "claude") c.apiKey?.let { Keystore.putSecret(context, CLAUDE_KEY, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", c.endpoint).putString("protocol", c.protocol)
            .putString("model", c.model)
            .apply { if (c.protocol != "claude") c.apiKey?.let { putString("api_key", it) } }
            .putString("tls_fingerprint", c.tlsFingerprint).putString("language", c.language)
            .apply()
    }

    private const val CLAUDE_KEY = "claude_api_key"

    fun claudeKey(context: Context): String? =
        runCatching { Keystore.getSecret(context, CLAUDE_KEY) }.getOrNull()
}
