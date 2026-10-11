package com.hubidubitask.mobile.net

import android.content.Context
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.mobile.security.Keystore

/**
 * Where this phone's agent lives. Kept on the phone: the app asks the agent
 * directly, so the setting belongs here rather than on a workstation that
 * would have to be on for a question to be answered.
 */
object AgentSettings {
    private const val PREFS = "agent"

    fun load(context: Context): AgentConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val protocol = p.getString("protocol", null) ?: AgentConfig.DEFAULT_PROTOCOL
        val harness = protocol == "harness"
        return AgentConfig(
            endpoint = if (harness) harnessEndpoint(context)
                       else p.getString("endpoint", null) ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = protocol,
            model = if (harness) p.getString(HARNESS_MODEL, null).orEmpty()
                    else p.getString("model", null)?.takeIf { it.isNotBlank() }
                        ?: AgentConfig.defaultModel(protocol),
            // Claude's key and the agent's token open paid or powerful things,
            // so they live in the Keystore, not in plain preferences.
            apiKey = when (protocol) {
                "claude" -> claudeKey(context)
                "harness" -> harnessToken(context)
                else -> p.getString("api_key", null)
            },
            tlsFingerprint = if (harness) harnessFingerprint(context)
                             else p.getString("tls_fingerprint", null),
            language = p.getString("language", null) ?: "auto",
            kind = if (harness) p.getString(HARNESS_KIND, null) else null,
        )
    }

    fun save(context: Context, c: AgentConfig) {
        val own = c.protocol == "claude" || c.protocol == "harness"
        if (c.protocol == "claude") c.apiKey?.let { Keystore.putSecret(context, CLAUDE_KEY, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // Claude and the user's agent keep their own address and secret
            // (see saveHarness), so switching back and forth loses neither.
            .apply { if (!own) putString("endpoint", c.endpoint) }
            .putString("protocol", c.protocol)
            .apply { if (c.protocol != "harness") putString("model", c.model) }
            .apply { if (!own) c.apiKey?.let { putString("api_key", it) } }
            .apply { if (!own) putString("tls_fingerprint", c.tlsFingerprint) }
            .putString("language", c.language)
            .apply()
    }

    private const val CLAUDE_KEY = "claude_api_key"
    private const val HARNESS_ENDPOINT = "harness_endpoint"
    private const val HARNESS_FP = "harness_fp"
    private const val HARNESS_KIND = "harness_kind"
    private const val HARNESS_MODEL = "harness_model"
    private const val HARNESS_TOKEN = "harness_token"

    /**
     * The user's own agent on a computer at home ("scheme://ip:port"), blank
     * until Settings found one.
     */
    fun harnessEndpoint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_ENDPOINT, null).orEmpty()

    fun harnessFingerprint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_FP, null)

    /** Trust on first answer: the agent's self-signed certificate, pinned from then on. */
    fun pinHarness(context: Context, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HARNESS_FP, fingerprint).apply()
    }

    /**
     * The user's agent as Settings found it: where, which one, its model, the
     * certificate it showed (pinned from now on) and its token.
     */
    fun saveHarness(context: Context, endpoint: String, kind: String?, model: String,
                    fingerprint: String?, token: String) {
        Keystore.putSecret(context, HARNESS_TOKEN, token.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(HARNESS_ENDPOINT, endpoint).putString(HARNESS_KIND, kind)
            .putString(HARNESS_MODEL, model).putString(HARNESS_FP, fingerprint)
            .apply()
    }

    fun harnessToken(context: Context): String? =
        runCatching { Keystore.getSecret(context, HARNESS_TOKEN) }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * What a feed writes its task with: Claude. Never the user's own agent: a
     * feed's text is a stranger's web page, and that agent can run commands.
     */
    fun feedConfig(context: Context): AgentConfig =
        AgentConfig(endpoint = "", protocol = "claude", apiKey = claudeKey(context))

    fun claudeKey(context: Context): String? =
        runCatching { Keystore.getSecret(context, CLAUDE_KEY) }.getOrNull()
}
