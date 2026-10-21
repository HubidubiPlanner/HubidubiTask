package com.hubidubitask.mobile.net

import android.content.Context
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentNames
import com.hubidubitask.core.agent.AgentPrefs
import com.hubidubitask.core.agent.AgentStore
import com.hubidubitask.mobile.security.Keystore

/**
 * Where this phone's agent settings are kept: plain values in its own
 * preferences, every key and token in the Android Keystore. The rules are
 * core's [AgentStore], the same as on the computer.
 */
object AgentSettings {
    private const val PREFS = "agent"
    /** The OpenAI-shaped server's key, in the Keystore. */
    private const val API_KEY = "agent_api_key"
    /** Where that key lay in plain preferences before it moved to the Keystore. */
    private const val PLAIN_API_KEY = "api_key"

    fun store(context: Context): AgentStore = AgentStore(Prefs(context.applicationContext), AgentNames.PHONE)

    fun load(context: Context): AgentConfig = store(context).load()

    fun claudeKey(context: Context): String? = store(context).claudeKey()

    fun pin(context: Context, cfg: AgentConfig, fingerprint: String) = store(context).pin(cfg, fingerprint)

    private class Prefs(private val context: Context) : AgentPrefs {
        private val p get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun get(key: String): String? = p.getString(key, null)

        override fun set(key: String, value: String?) =
            p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

        override fun secret(name: String): String? {
            // A key an older version kept in plain preferences moves into the Keystore on first read.
            if (name == API_KEY) p.getString(PLAIN_API_KEY, null)?.let { plain ->
                if (runCatching { Keystore.putSecret(context, API_KEY, plain) }.isFailure) return plain
                p.edit().remove(PLAIN_API_KEY).apply()
            }
            return runCatching { Keystore.getSecret(context, name) }.getOrNull()
        }

        override fun setSecret(name: String, value: String) = Keystore.putSecret(context, name, value)
    }
}
