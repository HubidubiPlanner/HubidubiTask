package com.hubidubitask.mobile.ui

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
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.peer.PeerPairing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pair this phone with the Mac app. The device you start on shows the code;
 * you type it on the other: "Show a code here" (type it on the Mac), or "Enter
 * the Mac's code". Either way the phone then goes looking for the Mac and keeps
 * trying until it takes the code.
 */
@Composable
fun PairMacSheet(open: Boolean, vm: HubidubiTaskViewModel, onDismiss: () -> Unit) {
    if (!open) return
    val c = HubidubiTaskTheme.colors
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }   // "show" | "enter"
    var shown by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

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
                SheetTitle(if (result!!.first) t("Paired") else t("Not paired"), overline = t("Computer"))
                Text(result!!.second, style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(t("Done"), ::close, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
            mode == null -> {
                SheetTitle(t("Pair with a computer"))
                Text(t("Open HubidubiTask on the computer, same Wi-Fi. The device you start on shows a code; type it on the other."), style = MaterialTheme.typography.bodyMedium,
                    color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
                ActionRow(Icons.Outlined.Pin, t("Show a code here"), meta = t("type it on the computer")) {
                    shown = PeerPairing.newCode(); mode = "show"; start(shown, here = true)
                }
                ActionRow(Icons.Outlined.Keyboard, t("Enter the computer's code"), last = true) { mode = "enter" }
            }
            else -> {
                if (mode == "show") {
                    SheetTitle(t("Type this on the computer"), overline = t("Settings → Pair a phone → Enter the phone's code"))
                    Text(shown, fontSize = 34.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line)
                            .padding(vertical = 18.dp, horizontal = 16.dp))
                } else {
                    SheetTitle(t("Enter the computer's code"), overline = t("Shown on the computer"))
                    OutlinedTextField(value = code, onValueChange = { code = it.uppercase() },
                        singleLine = true, enabled = job == null, placeholder = { Text("ABCD-EFGH") },
                        textStyle = MaterialTheme.typography.titleMedium,
                        colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = address, onValueChange = { address = it },
                    singleLine = true, enabled = job == null || mode == "show",
                    label = { Text(t("Computer's address (only if it isn't found)")) },
                    placeholder = { Text("192.168.1.62:5244") },
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                if (mode == "enter" && job == null) {
                    PrimaryButton(t("Pair"), { start(code, here = false) },
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        enabled = code.filter { it.isLetterOrDigit() }.length == 8)
                } else {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(c.accent)
                        Text(t("Looking for the computer… (the code works for 10 minutes)"),
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
            }
        }
    }
}
