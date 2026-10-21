package com.hubidubitask.mobile.net

import android.content.Context
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentLink
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
        val protocol = AgentConfig.knownProtocol(p.getString("protocol", null))
        val harness = protocol == "harness"
        return AgentConfig(
            endpoint = if (harness) harnessEndpoint(context)
                       else p.getString("endpoint", null).orEmpty(),
            protocol = protocol,
            // Only the user's own agent names a model, the one found there; the
            // others choose their own (Claude the app's, an OpenAI-shaped server its first).
            model = if (harness) p.getString(HARNESS_MODEL, null).orEmpty()
                    else AgentConfig.defaultModel(protocol),
            // Every key and the agent's token open paid or powerful things, so
            // they live in the Keystore, not in plain preferences.
            apiKey = when (protocol) {
                "claude" -> claudeKey(context)
                "harness" -> harnessToken(context)
                else -> apiKey(context)
            },
            tlsFingerprint = if (harness) harnessFingerprint(context)
                             else p.getString(TLS_FP, null),
            language = p.getString("language", null) ?: "auto",
            kind = if (harness) p.getString(HARNESS_KIND, null) else null,
        )
    }

    fun save(context: Context, c: AgentConfig) {
        val own = c.protocol == "claude" || c.protocol == "harness"
        if (c.protocol == "claude") c.apiKey?.let { Keystore.putSecret(context, CLAUDE_KEY, it) }
        if (!own) c.apiKey?.let { Keystore.putSecret(context, API_KEY, it) }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A pin belongs to the address it was taken from: a new address starts
        // without one, and only a check that reaches it pins it (see pin).
        val moved = !own && p.getString("endpoint", null) != c.endpoint
        p.edit()
            // Claude and the user's agent keep their own address and secret
            // (see saveHarness), so switching back and forth loses neither.
            .apply { if (!own) putString("endpoint", c.endpoint) }
            .putString("protocol", c.protocol)
            .apply { if (moved) remove(TLS_FP) }
            .putString("language", c.language)
            .apply()
    }

    private const val CLAUDE_KEY = "claude_api_key"
    /** The OpenAI-shaped server's key, in the Keystore. */
    private const val API_KEY = "agent_api_key"
    /** Where that key lay in plain preferences before it moved to the Keystore. */
    private const val PLAIN_API_KEY = "api_key"
    private const val TLS_FP = "tls_fingerprint"
    private const val HARNESS_ENDPOINT = "harness_endpoint"
    private const val HARNESS_FP = "harness_fp"
    private const val HARNESS_KIND = "harness_kind"
    private const val HARNESS_MODEL = "harness_model"
    private const val HARNESS_TOKEN = "harness_token"

    /**
     * The OpenAI-shaped server's key. One an older version kept in plain
     * preferences moves into the Keystore on first read.
     */
    private fun apiKey(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(PLAIN_API_KEY, null)?.let { plain ->
            if (runCatching { Keystore.putSecret(context, API_KEY, plain) }.isFailure) return plain
            p.edit().remove(PLAIN_API_KEY).apply()
        }
        return runCatching { Keystore.getSecret(context, API_KEY) }.getOrNull()
    }

    /**
     * The user's own agent on a computer at home ("scheme://ip:port"), blank
     * until Settings found one.
     */
    fun harnessEndpoint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_ENDPOINT, null).orEmpty()

    fun harnessFingerprint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_FP, null)

    /**
     * Trust on first answer: the certificate a server in the home network
     * showed, pinned from then on. Only while [cfg] is still the setting and
     * holds no pin; a public address is held to the system's certificate
     * authorities instead. Resolves the host, so call it off the main thread.
     */
    fun pin(context: Context, cfg: AgentConfig, fingerprint: String) {
        val now = load(context)
        if (now.protocol != cfg.protocol || now.endpoint != cfg.endpoint || now.tlsFingerprint != null) return
        if (cfg.protocol == "claude" || !AgentLink.isPrivate(AgentLink.split(cfg.endpoint).first)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(if (cfg.protocol == "harness") HARNESS_FP else TLS_FP, fingerprint).apply()
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

    fun claudeKey(context: Context): String? =
        runCatching { Keystore.getSecret(context, CLAUDE_KEY) }.getOrNull()
}
