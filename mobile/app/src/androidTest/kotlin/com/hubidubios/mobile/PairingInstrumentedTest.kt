package com.hubidubios.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubidubios.mobile.net.PairedServer
import com.hubidubios.mobile.pairing.Pairing
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end pairing on a real device/emulator (PLAN.md Phase 7 acceptance:
 * "assert pairing completes end to end"). Driven by tests/smoke/
 * emulator_pairing.sh, which starts hubidubios-sync on the host and passes the QR
 * payload fields as instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class PairingInstrumentedTest {

    @Test
    fun pairsWithHostAndPersistsToken() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val qrJson = JSONObject()
            .put("host", args.getString("host")!!)
            .put("port", args.getString("port")!!.toInt())
            .put("cert_fingerprint", args.getString("fingerprint")!!)
            .put("one_time_token", args.getString("token")!!)
            .toString()

        val payload = Pairing.parseQr(qrJson)
        val server = Pairing.redeem(payload, "ci-emulator")
        PairedServer.save(ctx, server)

        assertTrue(server.deviceToken.isNotEmpty())
        // Persisted config round-trips (token sealed in Keystore).
        val loaded = PairedServer.load(ctx)
        assertTrue(loaded != null && loaded.host == payload.host)
    }
}
