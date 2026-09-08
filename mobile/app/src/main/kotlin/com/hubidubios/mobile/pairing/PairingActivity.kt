package com.hubidubios.mobile.pairing

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hubidubios.mobile.net.Discovery
import com.hubidubios.mobile.net.PairedServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pairing screen. Two inputs, both funnel through [Pairing.redeem]:
 *  - Paste the full payload line printed by `hubidubios-sync pair` — the reliable
 *    path: {host, port, cert_fingerprint, one_time_token}. (Scanning its QR
 *    yields the same JSON.)
 *  - Paste just the short one-time token: we resolve host/port/fingerprint
 *    from mDNS (_hubidubios._tcp). This only works when the workstation advertises
 *    its real cert fingerprint over mDNS; otherwise paste the full payload.
 *
 * Manual entry is always present so pairing works without a camera and on
 * networks where mDNS is blocked.
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val help = TextView(this).apply {
            text = "On the workstation run:  hubidubios-sync pair\n" +
                "Paste the payload line below (or just the one-time token)."
        }
        val payload = EditText(this).apply { hint = "Pairing payload or one-time token" }
        val name = EditText(this).apply {
            hint = "Device name"
            setText(Build.MODEL)
        }
        val pair = Button(this).apply { text = "Pair" }

        pair.setOnClickListener {
            val input = payload.text.toString().trim()
            val deviceName = name.text.toString().ifBlank { "phone" }
            pair.isEnabled = false
            Thread {
                val result = runCatching {
                    val qr = resolvePayload(input)
                    val server = Pairing.redeem(qr, deviceName)
                    PairedServer.save(applicationContext, server)
                    server
                }
                runOnUiThread {
                    pair.isEnabled = true
                    result.onSuccess {
                        Toast.makeText(this, "Paired with ${it.host}", Toast.LENGTH_LONG).show()
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "Pairing failed: ${it.message}", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }.start()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(help); addView(payload); addView(name); addView(pair)
        })
    }

    /** Full JSON payload -> parse directly. Otherwise treat the text as a bare
     *  one-time token and discover host/port/fingerprint over mDNS. */
    private fun resolvePayload(input: String): Pairing.QrPayload {
        if (input.startsWith("{")) return Pairing.parseQr(input)
        require(input.isNotBlank()) {
            "paste the payload line from `hubidubios-sync pair`, or the one-time token"
        }
        val found = discover()
            ?: error("couldn't find the workstation on this network — " +
                "paste the full payload line from `hubidubios-sync pair` instead")
        val fp = found.fingerprint
            ?: error("workstation didn't advertise its cert fingerprint — " +
                "paste the full payload line from `hubidubios-sync pair` instead")
        return Pairing.QrPayload(
            host = found.host, port = found.port,
            certFingerprint = fp, oneTimeToken = input,
        )
    }

    /** Block briefly for the first mDNS hit (best-effort). */
    private fun discover(): Discovery.Found? {
        val disco = Discovery(applicationContext)
        val latch = CountDownLatch(1)
        val hit = AtomicReference<Discovery.Found?>(null)
        disco.start { f ->
            if (hit.compareAndSet(null, f)) latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        disco.stop()
        return hit.get()
    }
}
