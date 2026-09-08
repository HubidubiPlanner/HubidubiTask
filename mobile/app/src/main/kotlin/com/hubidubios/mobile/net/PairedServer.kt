package com.hubidubios.mobile.net

import android.content.Context
import com.hubidubios.mobile.security.Keystore
import org.json.JSONObject

/**
 * The paired workstation: host, port, pinned cert fingerprint, and the device
 * token (sealed in Keystore). Produced by pairing (QR payload
 * {host, port, cert_fingerprint, one_time_token} -> device token).
 */
data class PairedServer(
    val host: String,
    val port: Int,
    val certFingerprint: String, // "sha256:<hex>"
    val deviceToken: String,
) {
    val baseUrl: String get() = "https://$host:$port"

    companion object {
        private const val CONFIG = "hubidubios-server"
        private const val TOKEN = "device-token"

        fun load(context: Context): PairedServer? {
            val prefs = context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE)
            val json = prefs.getString("config", null) ?: return null
            val token = Keystore.getSecret(context, TOKEN) ?: return null
            val o = JSONObject(json)
            return PairedServer(
                host = o.getString("host"),
                port = o.getInt("port"),
                certFingerprint = o.getString("cert_fingerprint"),
                deviceToken = token,
            )
        }

        fun save(context: Context, server: PairedServer) {
            val o = JSONObject()
                .put("host", server.host)
                .put("port", server.port)
                .put("cert_fingerprint", server.certFingerprint)
            context.getSharedPreferences(CONFIG, Context.MODE_PRIVATE)
                .edit().putString("config", o.toString()).apply()
            Keystore.putSecret(context, TOKEN, server.deviceToken)
        }
    }
}
