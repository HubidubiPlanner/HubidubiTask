package com.prodos.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prodos.core.EntityRecord
import com.prodos.mobile.pairing.PairingActivity
import kotlinx.coroutines.launch

private fun EntityRecord.text() = fields["text"] ?: fields["title"] ?: id

@Composable
fun HomeScreen(state: ProdosViewModel.UiState, vm: ProdosViewModel) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("prodos", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        StatusDot(state.paired, if (state.paired) "sync paired" else "local only")
        StatusDot(state.inference.connected,
            if (state.inference.connected)
                "inference: " + (state.inference.models.firstOrNull() ?: "connected")
            else "inference offline")
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Capture") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (text.isNotBlank()) { vm.capture(text.trim()); text = "" } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Capture") }

        Spacer(Modifier.height(16.dp))
        Text("Inbox (${state.inbox.size})", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.inbox) { item ->
                ClarifyRow(item, vm)
                Divider()
            }
        }
    }
}

@Composable
private fun ClarifyRow(item: EntityRecord, vm: ProdosViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(item.text())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }) { Text("Clarify") }
        }
        if (expanded) {
            Row(Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("next_action", "project", "waiting_for", "someday", "trash")
                    .forEach { kind ->
                        FilterChip(
                            selected = false,
                            onClick = { vm.fileClarify(item.id, kind, item.text(), null) },
                            label = { Text(kind.replace("_", " ")) },
                        )
                    }
            }
        }
    }
}

@Composable
fun NextScreen(state: ProdosViewModel.UiState, vm: ProdosViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Next actions (${state.nextActions.size})", fontWeight = FontWeight.Bold)
        LazyColumn {
            items(state.nextActions) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(a.text())
                        val ctx = a.fields["context"]; val due = a.fields["due"]
                        if (ctx != null || due != null) {
                            Text(listOfNotNull(ctx, due?.let { "due $it" }).joinToString("  "))
                        }
                    }
                    OutlinedButton(onClick = { vm.markDone(a.id) }) { Text("Done") }
                }
                Divider()
            }
        }
    }
}

@Composable
fun ReviewScreen(state: ProdosViewModel.UiState, vm: ProdosViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Weekly review", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Inbox: ${state.inbox.size} to process")
        Spacer(Modifier.height(8.dp))
        Text("Projects with no next action", fontWeight = FontWeight.Bold)
        if (state.staleProjects.isEmpty()) {
            Text("none — good")
        } else {
            LazyColumn {
                items(state.staleProjects) { p ->
                    Text("⚠ ${p.text()}", modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
fun ChatScreen(state: ProdosViewModel.UiState, vm: ProdosViewModel) {
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val online = state.paired && state.inference.connected
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Agent chat", fontWeight = FontWeight.Bold)
        if (!online) {
            Text("Online only — needs the workstation and a connected inference server.")
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Ask") }, enabled = online,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = online && query.isNotBlank(),
            onClick = { scope.launch { answer = "…"; answer = vm.ask(query.trim()) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Send") }
        Spacer(Modifier.height(16.dp))
        if (answer.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Text(answer, Modifier.padding(12.dp))
        }
    }
}

@Composable
fun SettingsScreen(state: ProdosViewModel.UiState, vm: ProdosViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        StatusDot(state.paired, if (state.paired) "Paired with workstation" else "Not paired")
        StatusDot(state.inference.connected,
            "Inference: " + (state.inference.server ?: "unknown"))
        Spacer(Modifier.height(16.dp))

        // Pair / re-pair with a workstation. This is the entry point the UI was
        // missing: it launches the pairing screen (paste the payload from
        // `prodos-sync pair`, or scan its QR).
        Button(
            onClick = {
                context.startActivity(Intent(context, PairingActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.paired) "Re-pair workstation" else "Pair workstation") }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.syncNow() },
            enabled = state.paired && !state.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.syncing) "Syncing…" else "Sync now") }

        Spacer(Modifier.height(12.dp))
        Text(
            "On the workstation run:  prodos-sync pair\n" +
                "Then paste the payload line here, or scan its QR.",
        )
    }
}
