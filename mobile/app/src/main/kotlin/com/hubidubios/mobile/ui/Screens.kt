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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Add
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
import com.hubidubios.mobile.browse.ReviewBrowse
import com.hubidubios.mobile.pairing.PairingActivity
import com.hubidubios.mobile.tree.ProjectTree
import java.io.File
import kotlinx.coroutines.launch

private fun EntityRecord.text() = fields["text"] ?: fields["title"] ?: id

/** The agent server proposed on a fresh install. A saved setting always wins;
 *  this only fills the form so Save works on arrival. */
private const val DEFAULT_AGENT_ENDPOINT = "https://192.168.1.50:8443"

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
    // an item, not the item itself. The chip sits beside the title rather than
    // under it, so the attachment reads as a property of the note and the body
    // stays the next thing the eye reaches.
    var open by remember(note.id) { mutableStateOf(false) }
    val attached = sha != null && kind != "text"

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, false))
            if (attached) {
                TextButton(
                    onClick = { open = !open },
                    contentPadding = PaddingValues(start = 8.dp, end = 0.dp),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Text(if (kind == "image") " Photo" else " Voice memo",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (body.isNotBlank()) {
            Text(body, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (attached && open) AttachmentPreview(kind, sha!!, title, vm)
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
    var kind by remember { mutableStateOf(ReviewBrowse.Kind.ITEMS) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EntityRecord?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pool = remember(kind, state.inbox, state.nextActions, state.notes) {
        ReviewBrowse.pool(kind, state.inbox, state.nextActions, state.notes)
    }
    val results = remember(pool, query) { ReviewBrowse.results(pool, query) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Inbox: ${state.inbox.size} to process",
            style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == ReviewBrowse.Kind.ITEMS,
                onClick = { kind = ReviewBrowse.Kind.ITEMS }, label = { Text("Items") })
            FilterChip(selected = kind == ReviewBrowse.Kind.NOTES,
                onClick = { kind = ReviewBrowse.Kind.NOTES }, label = { Text("Notes") })
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            if (query.isBlank()) "Recently modified" else "${results.size} found",
            fontWeight = FontWeight.Bold,
        )
        if (results.isEmpty()) {
            Text(
                if (query.isBlank()) "Nothing here yet."
                else "No ${if (kind == ReviewBrowse.Kind.NOTES) "notes" else "items"} match.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(results, key = { it.id }) { rec ->
                Box(Modifier.fillMaxWidth().clickable {
                    selected = rec; analysis = null
                }) {
                    if (kind == ReviewBrowse.Kind.NOTES) {
                        NoteRow(rec, vm)
                    } else {
                        Text(ReviewBrowse.labelOf(rec),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    }
                }
            }
        }

        selected?.let { rec ->
            AlertDialog(
                onDismissRequest = { selected = null; analysis = null },
                title = { Text(ReviewBrowse.labelOf(rec),
                    style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rec.fields["body"]?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                        }
                        when {
                            analyzing -> Text("Analysing…",
                                style = MaterialTheme.typography.bodySmall)
                            analysis != null -> Text(analysis!!,
                                style = MaterialTheme.typography.bodyMedium)
                            // Say why the button is dead rather than leaving the
                            // user tapping a greyed-out control.
                            !state.inference.connected -> Text(
                                "Agent not connected. Set the server in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = state.inference.connected && !analyzing,
                        onClick = {
                            analyzing = true; analysis = null
                            scope.launch {
                                analysis = vm.ask(ReviewBrowse.analysisPrompt(rec))
                                analyzing = false
                            }
                        },
                    ) { Text(if (analysis == null) "Analyse" else "Again") }
                },
                dismissButton = {
                    TextButton(onClick = { selected = null; analysis = null }) { Text("Close") }
                },
            )
        }

        // The defining GTD failure still deserves to be called out, but it is
        // no longer the only thing this screen does.
        if (state.staleProjects.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Projects with no next action", fontWeight = FontWeight.Bold)
            state.staleProjects.take(5).forEach { p ->
                Text("⚠ ${p.text()}", modifier = Modifier.padding(vertical = 4.dp))
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
    // Everything is proposed, nothing demanded: the form arrives filled in and
    // Save works on arrival. Deriving this from the paired workstation was a
    // worse guess than a fixed one — the agent runs on its own box here, so
    // proposing the workstation just made a wrong address easy to save.
    val defaultEndpoint = DEFAULT_AGENT_ENDPOINT
    var endpoint by remember(defaultEndpoint) { mutableStateOf(defaultEndpoint) }
    var protocol by remember { mutableStateOf("selfhostedai") }
    // The default is filled in rather than hinted at: an empty box reads as
    // "you must supply this", which is exactly what it is not.
    val defaultModel = if (protocol == "selfhostedai") "fast" else "local"
    var model by remember { mutableStateOf(defaultModel) }
    // Switching protocol swaps the default, unless the user typed their own.
    LaunchedEffect(protocol) {
        if (model.isBlank() || model == "fast" || model == "local") model = defaultModel
    }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.paired) {
        if (state.paired && !loaded) {
            vm.agentConfig()?.let {
                if (it.endpoint.isNotBlank()) endpoint = it.endpoint
                protocol = it.protocol
                // An unset model on the workstation means "use the default",
                // so show the default rather than an empty box.
                model = it.model?.takeIf { m -> m.isNotBlank() }
                    ?: if (it.protocol == "selfhostedai") "fast" else "local"
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
            supportingText = {
                Text(
                    if (endpoint == defaultEndpoint) "Default agent server" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
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
            label = { Text("Model") },
            placeholder = { Text(defaultModel) },
            supportingText = {
                Text(
                    if (model == defaultModel) "Default for this protocol"
                    else "Custom — the default is $defaultModel",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            singleLine = true, enabled = state.paired,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                saving = true; message = null
                scope.launch {
                    val err = vm.setAgentConfig(endpoint, protocol, model.ifBlank { null })
                    message = if (err != null) err else {
                        // Saving is not the same as working. Writing an address
                        // nothing answers on looks identical to writing a good
                        // one until something later fails, so probe it now and
                        // say plainly which happened.
                        message = "Saved — checking…"
                        val h = vm.checkInference()
                        if (h.connected) "Connected to ${h.server ?: endpoint}"
                        else "Saved, but nothing answered at $endpoint — check " +
                            "the address and that the agent is running there."
                    }
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
    // A project is a flat container holding its items — notes and tasks alike.
    // The shape and move rules live in ProjectTree so they are testable without
    // driving the UI.
    val records = remember(state.projects, state.nextActions, state.notes) {
        state.projects + state.nextActions + state.notes
    }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf<ProjectTree.Node?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    val nodes = remember(records, collapsed) { ProjectTree.build(records, collapsed) }

    moving?.let { node ->
        val targets = remember(node.id, records) { ProjectTree.moveTargets(node.id, records) }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(node.title, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val current = node.record.fields["parent"]
                    targets.forEach { (id, label) ->
                        val here = id == current || (id == null && current.isNullOrBlank())
                        TextButton(
                            onClick = { vm.move(node.id, id); moving = null },
                            enabled = !here,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (here) "$label  ·  here" else label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (here) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; newTitle = "" },
            title = { Text("New project") },
            text = {
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    label = { Text("Project name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.createProject(newTitle); creating = false; newTitle = "" },
                    enabled = newTitle.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false; newTitle = "" }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Text(" New", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (nodes.isEmpty()) {
            Text("No projects yet. Tap New project, then file notes and tasks into it.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(nodes, key = { it.id }) { node ->
                if (node.isProject) {
                    // Projects carry the accent colour: scanning the list, the
                    // containers should separate from their contents at a glance
                    // without needing rules or boxes.
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = node.hasChildren) {
                                collapsed = if (node.id in collapsed) collapsed - node.id
                                            else collapsed + node.id
                            }
                            .padding(top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (!node.hasChildren) "" else if (node.id in collapsed) "▸" else "▾",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            node.title,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = (node.depth * 18).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(node.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (node.isNote) "note" else "task",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { moving = node },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = "Move to another project",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
