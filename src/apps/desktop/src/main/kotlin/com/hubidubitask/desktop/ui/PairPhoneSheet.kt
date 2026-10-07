package com.hubidubitask.desktop.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubidubitask.core.i18n.t

/**
 * Pair a phone. Whichever device you start on shows the code; you type it on
 * the other. Here: "Show a code" puts one on screen for the phone, or "Enter the
 * phone's code" takes the one the phone is showing. Either way the Mac then
 * waits for the phone to connect, and the sheet closes itself once it has.
 */
@Composable
fun PairPhoneSheet(open: Boolean, vm: HubidubiTaskViewModel, state: HubidubiTaskViewModel.UiState,
                   onDismiss: () -> Unit) {
    if (!open) return
    val c = HubidubiTaskTheme.colors
    var mode by remember { mutableStateOf<String?>(null) }   // "show" | "enter"
    var code by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    val before = remember { state.peers.map { it.id }.toSet() }
    val newcomer = state.peers.firstOrNull { it.id !in before }

    fun close() { vm.stopPairing(); onDismiss() }

    LaunchedEffect(newcomer) { if (newcomer != null) waiting = false }

    AppSheet(::close) {
        when {
            newcomer != null -> {
                SheetTitle(t("Paired"), overline = t("Phone"))
                Text(t("%s and this computer now keep each other up to date — every minute while the app is open on the phone, on the same Wi-Fi.", newcomer.name),
                    style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(t("Done"), ::close, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
            mode == null -> {
                SheetTitle(t("Pair a phone"))
                Text(t("The device you start on shows a code; type it on the other."),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.padding(bottom = 12.dp))
                ActionRow(Icons.Outlined.Pin, t("Show a code here"), meta = t("type it on the phone")) {
                    shown = vm.startPairing(); mode = "show"; waiting = true
                }
                ActionRow(Icons.Outlined.Keyboard, t("Enter the phone's code"), last = true) { mode = "enter" }
            }
            mode == "show" -> {
                SheetTitle(t("Type this on the phone"), overline = t("Settings → Pair with a computer"))
                Text(shown.orEmpty(), fontSize = 40.sp, fontWeight = FontWeight.Medium,
                    color = c.ink, modifier = Modifier.fillMaxWidth().background(c.raised)
                        .border(1.dp, c.line).padding(vertical = 22.dp, horizontal = 20.dp))
                Waiting(vm)
            }
            else -> {
                SheetTitle(t("Enter the phone's code"), overline = t("Shown on the phone"))
                OutlinedTextField(value = code, onValueChange = { code = it.uppercase() },
                    singleLine = true, enabled = !waiting, placeholder = { Text("ABCD-EFGH") },
                    textStyle = MaterialTheme.typography.titleMedium,
                    colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth())
                if (!waiting) PrimaryButton(t("Pair"), { vm.acceptCode(code); waiting = true },
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    enabled = code.filter { it.isLetterOrDigit() }.length == 8)
                else Waiting(vm)
            }
        }
    }
}

@Composable
private fun Waiting(vm: HubidubiTaskViewModel) {
    val c = HubidubiTaskTheme.colors
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(c.accent)
            Text(t("Waiting for the phone… (valid 10 minutes)"), style = MaterialTheme.typography.bodySmall,
                color = c.muted)
        }
        val addrs = vm.server.addresses()
        if (addrs.isNotEmpty())
            Text(t("If the phone doesn't find this computer, type its address there: %s", addrs.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
