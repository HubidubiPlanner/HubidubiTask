package com.hubidubios.mobile.pairing

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hubidubios.mobile.net.Discovery
import com.hubidubios.mobile.net.PairedServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pairing screen. Three inputs, all funnelling through [Pairing.redeem]:
 *  - **Scan QR** — the primary path. `hubidubios-sync pair` prints the payload as
 *    a terminal QR; [ScanActivity] decodes it to the same JSON and pairs straight
 *    away, so nothing has to be typed.
 *  - Paste the full payload line: {host, port, cert_fingerprint, one_time_token}.
 *  - Paste just the short one-time token: we resolve host/port/fingerprint
 *    from mDNS (_hubidubios._tcp). This only works when the workstation advertises
 *    its real cert fingerprint over mDNS; otherwise scan, or paste the full payload.
 *
 * Manual entry is always present so pairing works without a camera and on
 * networks where mDNS is blocked.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var payload: EditText
    private lateinit var name: EditText
    private lateinit var pair: Button

    private val scan = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD)
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            payload.setText(text)
            // A scan is an unambiguous "pair with this" — don't make the user
            // tap again.
            submit(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val help = TextView(this).apply {
            text = "On the workstation run:  hubidubios-sync pair\n" +
                "Scan the QR it prints — or paste the payload line below."
        }
        val scanButton = Button(this).apply { text = "Scan QR" }
        payload = EditText(this).apply { hint = "Pairing payload or one-time token" }
        name = EditText(this).apply {
            hint = "Device name"
            setText(Build.MODEL)
        }
        pair = Button(this).apply { text = "Pair" }

        scanButton.setOnClickListener {
            scan.launch(Intent(this, ScanActivity::class.java))
        }
        pair.setOnClickListener { submit(payload.text.toString().trim()) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(help); addView(scanButton); addView(payload); addView(name); addView(pair)
        })
    }

    /** Redeem [input] (scanned or typed) against the workstation, off the UI thread. */
    private fun submit(input: String) {
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
