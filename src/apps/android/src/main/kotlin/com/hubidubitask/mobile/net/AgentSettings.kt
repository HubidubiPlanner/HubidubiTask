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
        val protocol = p.getString("protocol", null) ?: "selfhostedai"
        val harness = protocol == "harness"
        return AgentConfig(
            endpoint = if (harness) harnessEndpoint(context)
                       else p.getString("endpoint", null) ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = protocol,
            model = if (harness) AgentConfig.HARNESS_MODEL
                    else p.getString("model", null)?.takeIf { it.isNotBlank() }
                        ?: AgentConfig.defaultModel(protocol),
            // Claude's key is a paid credential, so it lives in the Keystore,
            // not in plain preferences beside the other agents' settings.
            apiKey = when (protocol) {
                "claude" -> claudeKey(context)
                "harness" -> null
                else -> p.getString("api_key", null)
            },
            tlsFingerprint = if (harness) harnessFingerprint(context)
                             else p.getString("tls_fingerprint", null),
            language = p.getString("language", null) ?: "auto",
        )
    }

    fun save(context: Context, c: AgentConfig) {
        val own = c.protocol == "claude" || c.protocol == "harness"
        if (c.protocol == "claude") c.apiKey?.let { Keystore.putSecret(context, CLAUDE_KEY, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // Claude and the harness keep their own address and secret, so a
            // feed can use either whichever agent reviews use.
            .apply { if (!own) putString("endpoint", c.endpoint) }
            // Saving the harness trusts the certificate found there now: the
            // next answer from it is pinned (see pinHarness).
            .apply { if (c.protocol == "harness") putString(HARNESS_ENDPOINT, c.endpoint).remove(HARNESS_FP) }
            .putString("protocol", c.protocol)
            .putString("model", c.model)
            .apply { if (!own) c.apiKey?.let { putString("api_key", it) } }
            .apply { if (!own) putString("tls_fingerprint", c.tlsFingerprint) }
            .putString("language", c.language)
            .apply()
    }

    private const val CLAUDE_KEY = "claude_api_key"
    private const val HARNESS_ENDPOINT = "harness_endpoint"
    private const val HARNESS_FP = "harness_fp"

    /**
     * The Harness: Claude Code on a computer in the home network, at the IP
     * and port typed in Settings ("https://ip:port"), blank until then.
     */
    fun harnessEndpoint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_ENDPOINT, null).orEmpty()

    fun harnessFingerprint(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HARNESS_FP, null)

    /** Trust on first answer: the harness's self-signed certificate, pinned from then on. */
    fun pinHarness(context: Context, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(HARNESS_FP, fingerprint).apply()
    }

    /** The agent a feed writes its task with: "claude" or "harness". Never with
     *  tools: a feed's text comes from a stranger's web page. */
    fun configFor(context: Context, model: String): AgentConfig = if (model == "harness")
        AgentConfig(endpoint = harnessEndpoint(context), protocol = "harness",
            tlsFingerprint = harnessFingerprint(context), tools = false)
    else AgentConfig(endpoint = "", protocol = "claude", apiKey = claudeKey(context))

    fun claudeKey(context: Context): String? =
        runCatching { Keystore.getSecret(context, CLAUDE_KEY) }.getOrNull()
}
