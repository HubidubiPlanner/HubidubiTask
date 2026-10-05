package com.hubidubios.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubidubios.core.peer.PeerPairing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pair this phone with the Mac app. The device you start on shows the code;
 * you type it on the other: "Show a code here" (type it on the Mac), or "Enter
 * the Mac's code". Either way the phone then goes looking for the Mac and keeps
 * trying until it takes the code.
 */
@Composable
fun PairMacSheet(open: Boolean, vm: HubidubiOsViewModel, onDismiss: () -> Unit) {
    if (!open) return
    val c = HubidubiOsTheme.colors
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }   // "show" | "enter"
    var shown by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun close() { job?.cancel(); onDismiss() }
    fun start(c: String, here: Boolean) {
        job = scope.launch {
            result = vm.pairMac(c, address.takeIf { it.isNotBlank() }, shownHere = here)
            job = null
        }
    }

    AppSheet(::close) {
        when {
            result != null -> {
                SheetTitle(if (result!!.startsWith("Paired")) "Paired" else "Not paired", overline = "Mac")
                Text(result!!, style = MaterialTheme.typography.bodyMedium)
                PrimaryButton("Done", ::close, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
            mode == null -> {
                SheetTitle("Pair with a Mac")
                Text("Open HubidubiOS on the Mac, same Wi-Fi. The device you start on shows a " +
                    "code; type it on the other.", style = MaterialTheme.typography.bodyMedium,
                    color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
                ActionRow(Icons.Outlined.Pin, "Show a code here", meta = "type it on the Mac") {
                    shown = PeerPairing.newCode(); mode = "show"; start(shown, here = true)
                }
                ActionRow(Icons.Outlined.Keyboard, "Enter the Mac's code", last = true) { mode = "enter" }
            }
            else -> {
                if (mode == "show") {
                    SheetTitle("Type this on the Mac", overline = "Settings → Pair a phone → Enter the phone's code")
                    Text(shown, fontSize = 34.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line)
                            .padding(vertical = 18.dp, horizontal = 16.dp))
                } else {
                    SheetTitle("Enter the Mac's code", overline = "Shown on the Mac")
                    OutlinedTextField(value = code, onValueChange = { code = it.uppercase() },
                        singleLine = true, enabled = job == null, placeholder = { Text("ABCD-EFGH") },
                        textStyle = MaterialTheme.typography.titleMedium,
                        colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = address, onValueChange = { address = it },
                    singleLine = true, enabled = job == null || mode == "show",
                    label = { Text("Mac address (only if it isn't found)") },
                    placeholder = { Text("192.168.1.62:5244") },
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                if (mode == "enter" && job == null) {
                    PrimaryButton("Pair", { start(code, here = false) },
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        enabled = code.filter { it.isLetterOrDigit() }.length == 8)
                } else {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(c.accent)
                        Text("Looking for the Mac… (the code works for 10 minutes)",
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
            }
        }
    }
}
