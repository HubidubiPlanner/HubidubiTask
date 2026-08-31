package com.prodos.mobile.pairing

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prodos.mobile.net.PairedServer

/**
 * Pairing screen. The primary path is scanning the workstation's QR with the
 * camera (ZXing decode of the {host,port,cert_fingerprint,one_time_token}
 * payload); manual entry is the always-present fallback for networks where the
 * QR is inconvenient. Both funnel through [Pairing.redeem].
 *
 * The camera preview loop is wired on-device; this screen keeps the manual
 * fallback so pairing is testable and usable without a camera.
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = EditText(this).apply { hint = "Paste QR JSON payload" }
        val name = EditText(this).apply {
            hint = "Device name"
            setText(Build.MODEL)
        }
        val pair = Button(this).apply { text = "Pair" }

        pair.setOnClickListener {
            Thread {
                val result = runCatching {
                    val qr = Pairing.parseQr(payload.text.toString())
                    val server = Pairing.redeem(qr, name.text.toString().ifBlank { "phone" })
                    PairedServer.save(applicationContext, server)
                    server
                }
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this, "Paired with ${it.host}", Toast.LENGTH_LONG).show()
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "Pairing failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(payload); addView(name); addView(pair)
        })
    }
}
