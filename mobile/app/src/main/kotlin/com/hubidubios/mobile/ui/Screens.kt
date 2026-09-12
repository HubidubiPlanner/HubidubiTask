package com.hubidubios.mobile.ui

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
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hubidubios.core.EntityRecord
import com.hubidubios.mobile.capture.AudioRecorder
import com.hubidubios.mobile.pairing.PairingActivity
import java.io.File
import kotlinx.coroutines.launch

private fun EntityRecord.text() = fields["text"] ?: fields["title"] ?: id

private fun shortStamp(): String =
    java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date())

@Composable
fun HomeScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val context = LocalContext.current
    var textMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var showImageChoice by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<AudioRecorder?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) vm.noteImage(bytes, mime, "Photo " + shortStamp())
        }
    }
    val cameraFile = remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile.value
        if (ok && f != null && f.exists())
            vm.noteImage(f.readBytes(), "image/jpeg", "Photo " + shortStamp())
    }
    fun launchCamera() {
        val f = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        cameraFile.value = f
        takePhoto.launch(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f))
    }

    fun startRecording() {
        val f = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        runCatching { AudioRecorder(f).also { it.start(); recorder.value = it } }
            .onSuccess { recording = true }
    }
    fun stopRecording() {
        val f = recorder.value?.stop(); recorder.value = null; recording = false
        if (f != null) vm.noteAudio(f.readBytes(), "audio/mp4", "Voice memo " + shortStamp())
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("HubidubiOS", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        StatusDot(state.paired, if (state.paired) "sync paired" else "local only")
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundCaptureButton("Text", Icons.Filled.Edit) { textMode = !textMode; showImageChoice = false }
            RoundCaptureButton("Image", Icons.Filled.Image) { showImageChoice = !showImageChoice; textMode = false }
            RoundCaptureButton(if (recording) "Stop" else "Audio", Icons.Filled.Mic) {
                if (recording) stopRecording()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) startRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (recording) Text("● recording… tap Stop", Modifier.padding(top = 8.dp))
        if (showImageChoice) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    showImageChoice = false
                    pickImage.launch(PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Gallery") }
                OutlinedButton(onClick = { showImageChoice = false; launchCamera() }) { Text("Camera") }
            }
        }
        if (textMode) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("Text note") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = {
                if (text.isNotBlank()) { vm.noteText(text.trim()); text = ""; textMode = false }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save note") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Notes (${state.notes.size})", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.notes) { note ->
                NoteRow(note, vm)
                Divider()
            }
        }
    }
}

@Composable
private fun RoundCaptureButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(72.dp), shape = CircleShape) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label)
    }
}

@Composable
private fun NoteRow(note: EntityRecord, vm: HubidubiOsViewModel) {
    val kind = note.fields["kind"] ?: "text"
    val title = note.fields["title"] ?: "(untitled)"
    val sha = note.fields["media_sha256"]
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        when {
            kind == "image" && sha != null -> {
                val bmp = remember(sha) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(sha) {
                    vm.mediaFile(sha)?.let { bmp.value = BitmapFactory.decodeFile(it.path) }
                }
                bmp.value?.let {
                    Image(it.asImageBitmap(), contentDescription = title,
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp))
                } ?: Text("image…", Modifier.padding(top = 4.dp))
            }
            kind == "audio" && sha != null -> {
                val scope = rememberCoroutineScope()
                OutlinedButton(onClick = {
                    scope.launch {
                        vm.mediaFile(sha)?.let { f ->
                            runCatching {
                                MediaPlayer().apply {
                                    setDataSource(f.path); prepare(); start()
                                    setOnCompletionListener { release() }
                                }
                            }
                        }
                    }
                }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    Text("  Play")
                }
            }
            else -> note.fields["body"]?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ClarifyRow(item: EntityRecord, vm: HubidubiOsViewModel) {
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
fun NextScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
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
fun ReviewScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
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
fun ChatScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
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
fun SettingsScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
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
        // `hubidubios-sync pair`, or scan its QR).
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
            "On the workstation run:  hubidubios-sync pair\n" +
                "Then paste the payload line here, or scan its QR.",
        )
    }
}
