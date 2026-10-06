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
            endpoint = if (protocol == "harness") harnessEndpoint(context).orEmpty()
                       else p.getString("endpoint", null) ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = protocol,
            model = p.getString("model", null)?.takeIf { it.isNotBlank() }
                ?: AgentConfig.defaultModel(protocol),
            // Claude's key is a paid credential, so it lives in the Keystore,
            // not in plain preferences beside the other agents' settings.
            apiKey = when (protocol) {
                "claude" -> claudeKey(context)
                "harness" -> harnessToken(context)
                else -> p.getString("api_key", null)
            },
            tlsFingerprint = p.getString("tls_fingerprint", null),
            language = p.getString("language", null) ?: "auto",
        )
    }

    fun save(context: Context, c: AgentConfig) {
        val own = c.protocol == "claude" || c.protocol == "harness"
        if (c.protocol == "claude") c.apiKey?.let { Keystore.putSecret(context, CLAUDE_KEY, it) }
        if (c.protocol == "harness") c.apiKey?.let { Keystore.putSecret(context, HARNESS_TOKEN, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // Claude and the harness keep their own address and secret, so a
            // feed can use either whichever agent chat is set to.
            .apply { if (c.protocol == "harness") putString("harness_endpoint", c.endpoint)
                     else if (!own) putString("endpoint", c.endpoint) }
            .putString("protocol", c.protocol)
            .putString("model", c.model)
            .apply { if (!own) c.apiKey?.let { putString("api_key", it) } }
            .putString("tls_fingerprint", c.tlsFingerprint).putString("language", c.language)
            .apply()
    }

    private const val CLAUDE_KEY = "claude_api_key"
    private const val HARNESS_TOKEN = "harness_token"

    fun harnessEndpoint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("harness_endpoint", null)

    fun harnessToken(context: Context): String? =
        runCatching { Keystore.getSecret(context, HARNESS_TOKEN) }.getOrNull()

    /** The agent a feed writes its task with: "claude" or "harness". Never with
     *  tools: a feed's text comes from a stranger's web page. */
    fun configFor(context: Context, model: String): AgentConfig = if (model == "harness")
        AgentConfig(endpoint = harnessEndpoint(context).orEmpty(), protocol = "harness",
            apiKey = harnessToken(context), tools = false)
    else AgentConfig(endpoint = "", protocol = "claude", apiKey = claudeKey(context))

    fun claudeKey(context: Context): String? =
        runCatching { Keystore.getSecret(context, CLAUDE_KEY) }.getOrNull()
}
