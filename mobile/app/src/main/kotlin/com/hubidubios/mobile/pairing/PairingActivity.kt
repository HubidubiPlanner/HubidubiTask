package com.hubidubios.mobile.pairing

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubidubios.mobile.ui.HubidubiOsTheme
import com.hubidubios.mobile.ui.Mono
import com.hubidubios.mobile.ui.PrimaryButton
import com.hubidubios.mobile.ui.SecondaryButton
import com.hubidubios.mobile.ui.appFieldColors
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
class PairingActivity : ComponentActivity() {

    private var payload by mutableStateOf("")
    private var name by mutableStateOf(Build.MODEL)
    private var busy by mutableStateOf(false)

    private val scan = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data?.getStringExtra(ScanActivity.EXTRA_PAYLOAD)
        if (res.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            payload = text
            // A scan is an unambiguous "pair with this" — don't make the user
            // tap again.
            submit(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HubidubiOsTheme {
                PairingScreen(
                    payload = payload, onPayload = { payload = it },
                    name = name, onName = { name = it },
                    busy = busy,
                    onBack = { finish() },
                    onScan = { scan.launch(Intent(this, ScanActivity::class.java)) },
                    onPair = { submit(payload.trim()) },
                )
            }
        }
    }

    /** Redeem [input] (scanned or typed) against the workstation, off the UI thread. */
    private fun submit(input: String) {
        val deviceName = name.ifBlank { "phone" }
        busy = true
        Thread {
            val result = runCatching {
                val qr = resolvePayload(input)
                val server = Pairing.redeem(qr, deviceName)
                PairedServer.save(applicationContext, server)
                server
            }
            runOnUiThread {
                busy = false
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

/**
 * Three numbered steps, in the order they happen: run the command, scan what it
 * prints, or paste it when there is no camera. Scanning is the filled button
 * because it is the path that needs no typing.
 */
@Composable
private fun PairingScreen(
    payload: String, onPayload: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    busy: Boolean,
    onBack: () -> Unit, onScan: () -> Unit, onPair: () -> Unit,
) {
    val c = HubidubiOsTheme.colors
    Surface(color = c.paper, contentColor = c.ink) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 12.dp).size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                Text("Pair workstation", style = MaterialTheme.typography.headlineLarge)
                Text("The phone syncs only with a workstation it has paired with.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 26.dp))
                Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    Step(1, "On the workstation, run") {
                        Text("hubidubios-sync pair", fontFamily = Mono, color = c.well,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth().background(c.ink)
                                .padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                    Step(2, "Scan the QR it prints") {
                        PrimaryButton("Scan QR", onScan, Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.QrCodeScanner, enabled = !busy)
                    }
                    Step(3, "Or paste the payload") {
                        Field("Pairing payload or one-time token", payload, onPayload, mono = true)
                        Spacer(Modifier.height(14.dp))
                        Field("Device name", name, onName)
                    }
                }
            }
            SecondaryButton(if (busy) "Pairing…" else "Pair", onPair,
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
                icon = Icons.Outlined.Link, enabled = !busy && payload.isNotBlank())
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: @Composable () -> Unit) {
    val c = HubidubiOsTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).background(c.frame, CircleShape), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = c.paper, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            body()
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 14.sp)
                        else MaterialTheme.typography.bodyMedium,
            colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth(),
        )
    }
}
