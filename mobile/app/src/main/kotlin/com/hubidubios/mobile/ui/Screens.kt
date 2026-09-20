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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
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
import com.hubidubios.mobile.tree.ProjectTree
import java.io.File
import kotlinx.coroutines.launch

private fun EntityRecord.text() = fields["text"] ?: fields["title"] ?: id

private fun shortStamp(): String =
    java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US).format(java.util.Date())

/** A capture waiting for the user to say whether it is a task or a note.
 *
 *  Nothing is filed until that is answered: a photo means different things in
 *  the inbox ("do something about this") and in notes ("keep this"), and only
 *  the user knows which. `bytes == null` is a plain text capture. */
private data class PendingCapture(
    val kind: String,          // "text" | "image" | "audio"
    val text: String,
    val bytes: ByteArray? = null,
    val mime: String = "",
)

@Composable
fun HomeScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val context = LocalContext.current
    var textMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var showImageChoice by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<AudioRecorder?>(null) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null)
                pending = PendingCapture("image", "Photo " + shortStamp(), bytes, mime)
        }
    }
    val cameraFile = remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile.value
        if (ok && f != null && f.exists())
            pending = PendingCapture("image", "Photo " + shortStamp(),
                f.readBytes(), "image/jpeg")
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
        if (f != null)
            pending = PendingCapture("audio", "Voice memo " + shortStamp(),
                f.readBytes(), "audio/mp4")
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }

    // Ask before filing: the same photo is a task ("do something about this") or
    // a note ("keep this"), and only the user knows which.
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Task or note?") },
            text = {
                Text(
                    when (p.kind) {
                        "image" -> "Photo — file it in the inbox to act on, or keep it as a note."
                        "audio" -> "Voice memo — file it in the inbox to act on, or keep it as a note."
                        else -> p.text
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    when (p.kind) {
                        "image" -> vm.captureImage(p.bytes!!, p.mime, p.text)
                        "audio" -> vm.captureAudio(p.bytes!!, p.mime, p.text)
                        else -> vm.capture(p.text)
                    }
                    pending = null
                }) { Text("Task") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    when (p.kind) {
                        "image" -> vm.noteImage(p.bytes!!, p.mime, p.text)
                        "audio" -> vm.noteAudio(p.bytes!!, p.mime, p.text)
                        else -> vm.noteText(p.text)
                    }
                    pending = null
                }) { Text("Note") }
            },
        )
    }

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
                if (text.isNotBlank()) {
                    pending = PendingCapture("text", text.trim()); text = ""; textMode = false
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save") }
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
    val body = note.fields["body"].orEmpty()
    val sha = note.fields["media_sha256"]
    // Text is the point of this app; a photo or voice memo is an attachment on
    // an item, not the item itself. So the words lead and the attachment is a
    // chip you open deliberately — never a 200dp image shoving the note off
    // the screen.
    var open by remember(note.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        if (body.isNotBlank()) {
            Text(body, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (sha != null && kind != "text") {
            val label = if (kind == "image") "Photo" else "Voice memo"
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Filled.AttachFile, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Text("  $label", style = MaterialTheme.typography.bodySmall)
            }
            if (open) AttachmentPreview(kind, sha, title, vm)
        }
    }
}

/** The attachment itself, shown only once the user asks for it. */
@Composable
private fun AttachmentPreview(
    kind: String, sha: String, title: String, vm: HubidubiOsViewModel,
) {
    if (kind == "image") {
        val bmp = remember(sha) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(sha) {
            vm.mediaFile(sha)?.let { bmp.value = BitmapFactory.decodeFile(it.path) }
        }
        bmp.value?.let {
            Image(it.asImageBitmap(), contentDescription = title,
                modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp))
        } ?: Text("loading…", Modifier.padding(top = 4.dp))
    } else {
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
    val scope = rememberCoroutineScope()

    // The inference endpoint lives on the workstation (it holds the URL and the
    // pinned certificate), so the phone edits it through the sync API rather
    // than keeping a second copy that could drift.
    var endpoint by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("selfhostedai") }
    var model by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.paired) {
        if (state.paired && !loaded) {
            vm.agentConfig()?.let {
                endpoint = it.endpoint
                protocol = it.protocol
                model = it.model.orEmpty()
            }
            loaded = true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        StatusDot(state.paired, if (state.paired) "Paired with workstation" else "Not paired")
        StatusDot(state.inference.connected,
            "Inference: " + (state.inference.server ?: "not configured"))
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { context.startActivity(Intent(context, PairingActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.paired) "Re-pair workstation" else "Pair workstation") }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.syncNow() },
            enabled = state.paired && !state.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.syncing) "Syncing…" else "Sync now") }

        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        Text("Agent server", fontWeight = FontWeight.Bold)
        Text("Where the workstation sends questions and captures for summarising.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))

        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it; message = null },
            label = { Text("Server address") },
            placeholder = { Text("https://192.168.1.50:8443") },
            singleLine = true, enabled = state.paired,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = protocol == "selfhostedai",
                onClick = { protocol = "selfhostedai"; message = null },
                label = { Text("selfhostedai (encrypted)") })
            FilterChip(selected = protocol == "openai",
                onClick = { protocol = "openai"; message = null },
                label = { Text("OpenAI-compatible") })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model, onValueChange = { model = it; message = null },
            label = { Text("Model (optional)") },
            singleLine = true, enabled = state.paired,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                saving = true; message = null
                scope.launch {
                    val err = vm.setAgentConfig(endpoint, protocol, model.ifBlank { null })
                    message = err ?: "Saved — checking connection…"
                    saving = false
                }
            },
            enabled = state.paired && !saving && endpoint.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "Saving…" else "Save agent server") }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.paired) {
            Text("Pair with a workstation first — it is the workstation that talks "
                + "to the agent server.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun ProjectsScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    // Projects and their actions are one tree: a project contains sub-projects
    // and actions, to any depth. The shape and the move rules live in
    // ProjectTree so they are testable without driving the UI.
    val records = remember(state.projects, state.nextActions) {
        state.projects + state.nextActions
    }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf<ProjectTree.Node?>(null) }
    val nodes = remember(records, collapsed) { ProjectTree.build(records, collapsed) }

    moving?.let { node ->
        val targets = remember(node.id, records) { ProjectTree.moveTargets(node.id, records) }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move \"${node.title}\"") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { (id, label) ->
                        TextButton(onClick = { vm.move(node.id, id); moving = null }) {
                            Text(if (id == null) label else "→  $label")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Projects", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (nodes.isEmpty()) {
            Text("No projects yet. Clarify an inbox item into a project to start one.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(nodes, key = { it.id }) { node ->
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = (node.depth * 16).dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.hasChildren) {
                        TextButton(
                            onClick = {
                                collapsed = if (node.id in collapsed) collapsed - node.id
                                            else collapsed + node.id
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(28.dp),
                        ) { Text(if (node.id in collapsed) "▸" else "▾") }
                    } else {
                        Spacer(Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            node.title,
                            fontWeight = if (node.isProject) FontWeight.Bold else FontWeight.Normal,
                            style = if (node.isProject) MaterialTheme.typography.bodyLarge
                                    else MaterialTheme.typography.bodyMedium,
                        )
                        if (node.isProject) {
                            node.record.fields["status"]?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    TextButton(onClick = { moving = node }) { Text("Move") }
                }
                Divider()
            }
        }
    }
}
