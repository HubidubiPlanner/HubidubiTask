package com.hubidubitask.mobile

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Starts before anything else in the app: every TLS connection (Claude,
 * feeds, imports, the paired computer) goes through the bundled Conscrypt,
 * which offers the post-quantum hybrid key exchange X25519MLKEM768 by
 * default; the phone's own TLS may not yet.
 *
 * It goes in right after Android's network-security provider (AndroidNSSP),
 * so certificates are still checked under network_security_config.xml (the
 * system's authorities only), while connections are made by Conscrypt.
 */
class HubidubiTaskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installTls()
    }

    companion object {
        fun installTls() {
            if (Security.getProvider("Conscrypt") != null) return
            val providers = Security.getProviders()
            val nssp = providers.indexOfFirst { it.name == "AndroidNSSP" }
            // insertProviderAt counts from 1.
            Security.insertProviderAt(Conscrypt.newProvider(), if (nssp >= 0) nssp + 2 else 1)
        }
    }
}
