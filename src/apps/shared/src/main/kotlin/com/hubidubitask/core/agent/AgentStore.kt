package com.hubidubitask.core.agent

import com.hubidubitask.core.i18n.t

/**
 * Where each app keeps its AI settings: plain values, and secrets in the
 * system's secret store (the Android Keystore; the Keychain or Secret
 * Service on a computer). The app hands over its own.
 */
interface AgentPrefs {
    fun get(key: String): String?
    fun set(key: String, value: String?)
    fun secret(name: String): String?
    fun setSecret(name: String, value: String)
}

/**
 * The AI settings, the same on the phone and the computer: which [AgentType]
 * is chosen, and per type ([AgentType.scope]) its address, pinned
 * certificate, kind, model and secret.
 *
 * [names] maps a setting ("protocol", or "<scope>.<endpoint|fp|kind|model|secret>")
 * to the name each app has always stored it under ([AgentNames]), so
 * nothing kept before is lost. A new type needs no entry there: its
 * settings are kept as "agent_<scope>_<what>".
 */
class AgentStore(private val prefs: AgentPrefs, private val names: Map<String, String>) {

    private fun name(key: String) = names[key] ?: "agent_" + key.replace('.', '_')
    private fun get(key: String) = prefs.get(name(key))?.takeIf { it.isNotEmpty() }
    private fun set(key: String, value: String?) = prefs.set(name(key), value)
    private fun secret(key: String) = runCatching { prefs.secret(name(key)) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The chosen type; one this version doesn't offer reads as Claude. */
    fun type(): AgentType = AgentTypes.of(get("protocol"))

    /** The chosen agent, as the apps ask it. */
    fun load(): AgentConfig {
        val type = type()
        val s = type.scope
        return AgentConfig(
            endpoint = type.endpoint ?: get("$s.endpoint").orEmpty(),
            protocol = type.id,
            model = get("$s.model") ?: type.defaultModel,
            apiKey = secret("$s.secret"),
            tlsFingerprint = if (type.sdk) null else get("$s.fp"),
            language = get("language") ?: "auto",
            kind = get("$s.kind"),
        )
    }

    /** The key Claude is asked with, wherever the choice is: feeds use it. */
    fun claudeKey(): String? = secret("${com.hubidubitask.core.agent.types.ClaudeAgentType.scope}.secret")

    /** What [type]'s fields in Settings show, from what is kept. */
    fun form(type: AgentType): Map<String, String> {
        val s = type.scope
        val (host, port) = AgentLink.split(get("$s.endpoint").orEmpty())
        return type.fields.associate { f ->
            f.key to when (f.key) {
                "endpoint" -> get("$s.endpoint").orEmpty()
                "apiKey", "token" -> secret("$s.secret").orEmpty()
                "host" -> host
                "port" -> port
                else -> get("$s.${f.key}").orEmpty()
            }
        }
    }

    /**
     * Keep [type] as the choice, with what was typed ([values]). A pin
     * belongs to the address it was taken from: a new address starts without
     * one, and only a check that reaches it pins it ([pin]).
     */
    fun keep(type: AgentType, values: Map<String, String>) {
        val s = type.scope
        for (f in type.fields) {
            val v = values[f.key] ?: continue
            when (f.key) {
                "endpoint" -> {
                    if (get("$s.endpoint").orEmpty() != v.trim()) set("$s.fp", null)
                    set("$s.endpoint", v.trim())
                }
                "apiKey", "token" -> prefs.setSecret(name("$s.secret"), v.trim())
            }
        }
        set("protocol", type.id)
    }

    /**
     * Find the user's agent at [host] and [port] (blank: each kind's usual
     * one) and make it the choice. What was typed is kept either way, so the
     * form shows it again; the certificate it showed is pinned from now on.
     */
    fun connectHome(type: AgentType, host: String, port: String, token: String): AgentProbe.Result {
        val s = type.scope
        val typed = AgentLink.parse(host, port) ?: return AgentProbe.Result.NotFound
        val found = AgentProbe.detect(typed.first, typed.second, token.trim().ifBlank { null })
        fun save(endpoint: String, kind: String?, model: String, fp: String?) {
            set("$s.endpoint", endpoint); set("$s.kind", kind); set("$s.model", model); set("$s.fp", fp)
            prefs.setSecret(name("$s.secret"), token.trim())
        }
        when (found) {
            is AgentProbe.Result.Found -> save(found.endpoint, found.kind.id, found.model, found.fingerprint)
            is AgentProbe.Result.NeedsToken -> save(found.endpoint, found.kind.id, "", null)
            AgentProbe.Result.NotFound -> save(AgentLink.endpoint("https", typed.first, typed.second), null, "", null)
        }
        set("protocol", type.id)
        return found
    }

    /** What a change in Settings came to: the line to show, whether it answers, the fields as found. */
    data class Outcome(val message: String?, val connected: Boolean, val values: Map<String, String>? = null)

    /** Whether keeping [values] for [type] is followed by a check ([setUp]). */
    fun willCheck(type: AgentType, values: Map<String, String>) = type.ready(values)

    /**
     * Keep [values] for [type] and, when they are enough, check that it
     * answers with [check] (which pins a home server's certificate). Saving
     * is not the same as working: an address nothing answers on looks just
     * like a good one until a question fails, so the outcome says which.
     * Network; off the main thread.
     */
    fun setUp(type: AgentType, values: Map<String, String>, check: () -> Health): Outcome {
        if (type.found) {
            val host = values["host"].orEmpty()
            if (host.isBlank()) return Outcome(null, false)
            val found = connectHome(type, host, values["port"].orEmpty(), values["token"].orEmpty())
            val h = check()
            return when (found) {
                is AgentProbe.Result.Found -> Outcome(
                    if (h.connected) t("Connected to %s", found.label)
                    else t("Saved, but the agent at %s did not answer: %s", found.label, h.error ?: t("no reply")),
                    h.connected, values + ("port" to AgentLink.split(found.endpoint).second))
                is AgentProbe.Result.NeedsToken -> Outcome(
                    if (found.refused) t("%s refused the token", found.label) else t("%s needs its token", found.label),
                    false)
                AgentProbe.Result.NotFound -> Outcome(
                    t("Saved, but the agent at %s did not answer: %s", host.trim(), t("no reply")), false)
            }
        }
        keep(type, values)
        if (!type.ready(values)) return Outcome(null, false)
        val endpoint = type.endpoint ?: values["endpoint"].orEmpty().trim()
        val h = check()
        return Outcome(if (h.connected) t("Connected to %s", h.server ?: endpoint) else type.silent(h, endpoint),
            h.connected)
    }

    /**
     * Trust on first answer: keep [fingerprint] as the pin for [cfg]'s
     * address. Only while [cfg] is still the setting and holds no pin, never
     * for Claude, and only in the home network; a public address is held to
     * the system's certificate authorities instead. Resolves the host, so
     * call it off the main thread.
     */
    fun pin(cfg: AgentConfig, fingerprint: String, isPrivate: (String) -> Boolean = AgentLink::isPrivate) {
        val now = load()
        if (now.protocol != cfg.protocol || now.endpoint != cfg.endpoint || now.tlsFingerprint != null) return
        val type = AgentTypes.of(cfg.protocol)
        if (type.sdk || !isPrivate(AgentLink.split(cfg.endpoint).first)) return
        set("${type.scope}.fp", fingerprint)
    }
}

/** The names each app has always kept its AI settings under. */
object AgentNames {
    val PHONE = mapOf(
        "protocol" to "protocol", "language" to "language",
        "claude.secret" to "claude_api_key",
        "server.endpoint" to "endpoint", "server.fp" to "tls_fingerprint", "server.secret" to "agent_api_key",
        "harness.endpoint" to "harness_endpoint", "harness.fp" to "harness_fp", "harness.kind" to "harness_kind",
        "harness.model" to "harness_model", "harness.secret" to "harness_token",
    )
    val COMPUTER = mapOf(
        "protocol" to "agent_protocol",
        "claude.secret" to "claude-api-key",
        "server.endpoint" to "agent_endpoint", "server.fp" to "agent_fp",
        "harness.endpoint" to "harness_endpoint", "harness.fp" to "harness_fp", "harness.kind" to "harness_kind",
        "harness.model" to "harness_model", "harness.secret" to "harness-token",
    )
}
