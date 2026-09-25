@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hubidubios.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hubidubios.core.EntityRecord
import com.hubidubios.mobile.browse.Agenda
import com.hubidubios.mobile.browse.ItemDetail
import com.hubidubios.mobile.browse.ReviewBrowse
import com.hubidubios.mobile.capture.AudioRecorder
import com.hubidubios.mobile.pairing.PairingActivity
import com.hubidubios.mobile.tree.ProjectTree
import java.io.File
import kotlinx.coroutines.launch

/** The agent server proposed on a fresh install. A saved setting always wins;
 *  this only fills the form so Save works on arrival. */
private const val DEFAULT_AGENT_ENDPOINT = "https://192.168.1.50:8443"

/** Task or note, at a glance: an empty box is something to do, lines are something kept. */
@Composable
private fun KindGlyph(record: EntityRecord) {
    val c = HubidubiOsTheme.colors
    if (record.type == "note")
        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Note",
            tint = c.muted, modifier = Modifier.size(16.dp))
    else
        Icon(Icons.Outlined.CheckBoxOutlineBlank, contentDescription = "Task",
            tint = c.glyph, modifier = Modifier.size(16.dp))
}

/** "Photo", "Voice memo" beside a title: the attachment reads as a property of the item. */
@Composable
private fun AttachmentTag(record: EntityRecord) {
    if (record.fields["media_sha256"].isNullOrBlank()) return
    val label = when (record.fields["kind"]) {
        "image" -> "Photo"
        "audio" -> "Voice memo"
        "document" -> "Document"
        else -> return
    }
    val c = HubidubiOsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, tint = c.muted,
            modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/**
 * Confirm before deleting. Long-press alone is too easy to do by accident for
 * something irreversible, and the attachment goes with the item, so the sheet
 * says so rather than letting the user discover it afterwards.
 */
@Composable
private fun ConfirmDelete(
    record: EntityRecord?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    record ?: return
    val c = HubidubiOsTheme.colors
    val label = record.fields["title"] ?: record.fields["text"] ?: record.id
    val hasMedia = !record.fields["media_sha256"].isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text("Delete?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                if (hasMedia) {
                    Text(
                        "Its attachment is deleted too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(record.id); onDismiss() }) {
                Text("Delete", color = c.danger, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.ink, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/**
 * Everything you can do to one item, from a long press. Actions live here
 * rather than as icons in the row: the lists are mostly for reading, and a row
 * that ends in a strip of buttons reads as a toolbar instead of content.
 */
@Composable
private fun ItemActionsMenu(
    record: EntityRecord?,
    canMove: Boolean,
    vm: HubidubiOsViewModel,
    // The record travels with the callback. Reading it from the caller's state
    // afterwards returns null, because closing the menu clears that state
    // first — which silently did nothing at all.
    onMove: (EntityRecord) -> Unit,
    onReview: (EntityRecord) -> Unit,
    onDelete: (EntityRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    record ?: return
    val c = HubidubiOsTheme.colors
    val due = record.fields["due"].orEmpty()
    var picking by remember(record.id) { mutableStateOf(false) }

    if (picking) {
        DatePickerFor(record, vm) { picking = false; onDismiss() }
        return
    }
    AppSheet(onDismiss) {
        SheetTitle(ReviewBrowse.labelOf(record), overline = ItemDetail.typeLabel(record))
        if (canMove) {
            ActionRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to a project") {
                onDismiss(); onMove(record)
            }
        }
        ActionRow(Icons.Outlined.CalendarToday, if (due.isBlank()) "Set a date" else "Date",
            meta = due.takeIf { it.isNotBlank() }?.let(::shortDate)) { picking = true }
        ActionRow(Icons.Outlined.RateReview, "Review with the agent") {
            onDismiss(); onReview(record)
        }
        ActionRow(Icons.Outlined.DeleteOutline, "Delete", color = c.danger, last = true) {
            onDismiss(); onDelete(record)
        }
        SecondaryButton("Cancel", onDismiss, Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/** Pick a date for an item. Dated items are what the Next view lists. */
@Composable
private fun DatePickerFor(
    record: EntityRecord,
    vm: HubidubiOsViewModel,
    onDone: () -> Unit,
) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDone,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    // The picker hands back UTC midnight; formatting in UTC
                    // keeps the date the user tapped from sliding a day.
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    vm.setDue(record.id, fmt.format(java.util.Date(ms)))
                }
                onDone()
            }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (!record.fields["due"].isNullOrBlank()) {
                    TextButton(onClick = { vm.setDue(record.id, null); onDone() }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    ) { DatePicker(state = state) }
}

/** The small tracked heading inside a sheet or card: REVIEW, AGENT. */
@Composable
private fun Overline(text: String, color: Color, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(14.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * One item, opened with a single press: what it is, when it is due, and what
 * the agent made of it last time. Reading, not editing — the long press is
 * still where things are moved, dated and deleted.
 */
@Composable
private fun DetailSheet(
    record: EntityRecord?,
    onReview: (EntityRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    record ?: return
    val c = HubidubiOsTheme.colors
    val review = ItemDetail.reviewOf(record)
    val due = ItemDetail.dueOf(record)
    val overdue = due != null && due < todayIso()
    AppSheet(onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Overline(ItemDetail.typeLabel(record), c.accent)
            Text(ReviewBrowse.labelOf(record), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                due?.let {
                    val tint = if (overdue) c.danger else c.muted
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = tint,
                            modifier = Modifier.size(14.dp))
                        Text("Due ${shortDate(it)}", style = MaterialTheme.typography.bodySmall,
                            color = tint)
                    }
                }
                ItemDetail.capturedAt(record)?.let {
                    Text("Captured $it", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            record.fields["body"]?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp))
            }
            Column(
                Modifier.padding(top = 20.dp).fillMaxWidth()
                    .background(c.raised).border(1.dp, c.line)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Overline("Review", c.frame, Icons.Outlined.RateReview)
                Text(
                    review ?: "Nothing yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (review == null) c.muted else c.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Close", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (review == null) "Review" else "Review again",
                { val r = record; onDismiss(); onReview(r) },
                Modifier.weight(1f), icon = Icons.Outlined.RateReview)
        }
    }
}

/**
 * The agent's take on one item. Chosen from the menu, so it starts working the
 * moment it opens — asking twice (menu, then a button) for something already
 * asked for is a step that earns nothing.
 */
@Composable
private fun AnalysisSheet(
    record: EntityRecord?,
    connected: Boolean,
    vm: HubidubiOsViewModel,
    onDismiss: () -> Unit,
) {
    record ?: return
    val c = HubidubiOsTheme.colors
    // A review already asked for is shown as it is: the answer does not improve
    // by being bought twice, and a cold model takes a minute to say the same.
    var text by remember(record.id) { mutableStateOf(ItemDetail.reviewOf(record)) }
    var busy by remember(record.id) { mutableStateOf(false) }

    LaunchedEffect(record.id) {
        if (connected && text == null) {
            busy = true
            text = vm.ask(ReviewBrowse.analysisPrompt(record), plain = true)
            busy = false
            // Only a real answer is kept. An explanation of why there is none
            // is worth showing and not worth remembering.
            text?.takeIf { !it.startsWith("error:") }?.let { vm.setReview(record.id, it) }
        }
    }

    AppSheet(onDismiss) {
        SheetTitle(ReviewBrowse.labelOf(record), overline = "Review")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when {
                !connected -> Text(
                    "Agent not connected. Set the server in Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted)
                busy -> Text("Thinking… a cold model can take a minute.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted)
                else -> Text(text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Close", onDismiss, Modifier.weight(1f))
            if (connected && !busy && text != null) {
                PrimaryButton("Again", {
                    text = null   // re-triggers the LaunchedEffect guard below
                    busy = true
                }, Modifier.weight(1f), icon = Icons.Outlined.RateReview)
            }
        }
    }
    // "Again" clears the text; run once more when that happens.
    LaunchedEffect(busy) {
        if (busy && text == null && connected) {
            text = vm.ask(ReviewBrowse.analysisPrompt(record), plain = true)
            busy = false
            // Only a real answer is kept. An explanation of why there is none
            // is worth showing and not worth remembering.
            text?.takeIf { !it.startsWith("error:") }?.let { vm.setReview(record.id, it) }
        }
    }
}

/** A picked file's display name, for the item's title. */
private fun documentNameOf(context: android.content.Context, uri: android.net.Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: "Document"
}

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
    val c = HubidubiOsTheme.colors
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
                pending = PendingCapture("image", "Photo", bytes, mime)
        }
    }
    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val name = documentNameOf(context, uri)
            if (bytes != null)
                pending = PendingCapture("document", name, bytes, mime)
        }
    }
    val cameraFile = remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile.value
        if (ok && f != null && f.exists())
            pending = PendingCapture("image", "Photo", f.readBytes(), "image/jpeg")
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
            pending = PendingCapture("audio", "Voice memo", f.readBytes(), "audio/mp4")
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }

    // Ask before filing: the same photo is a task ("do something about this") or
    // a note ("keep this"), and only the user knows which. A date is offered at
    // the same moment — it is the one fact you still have in mind at capture
    // time and the thing that decides whether the item ever resurfaces.
    var captureDue by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        captureDue = fmt.format(java.util.Date(ms))
                    }
                    pickingDate = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { captureDue = null; pickingDate = false }) {
                    Text("No date")
                }
            },
        ) { DatePicker(state = dateState) }
    }

    pending?.let { p ->
        AppSheet(onDismiss = { pending = null }) {
            SheetTitle("Task or note?")
            Text("Act on it, or keep it as a note.", style = MaterialTheme.typography.bodyMedium,
                color = c.muted, modifier = Modifier.padding(bottom = 18.dp))
            // What is being filed, so the choice is made looking at it.
            Row(
                Modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(56.dp).background(c.well), contentAlignment = Alignment.Center) {
                    Icon(
                        when (p.kind) {
                            "image" -> Icons.Outlined.Image
                            "audio" -> Icons.Outlined.Mic
                            "document" -> Icons.Outlined.Description
                            else -> Icons.Outlined.Edit
                        },
                        contentDescription = null, tint = c.frame, modifier = Modifier.size(24.dp),
                    )
                }
                Text(p.text, style = MaterialTheme.typography.titleSmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .border(1.dp, c.glyph)
                    .clickable(role = Role.Button) { pickingDate = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = c.accent,
                    modifier = Modifier.size(20.dp))
                Text(captureDue?.let { "Due ${shortDate(it)}" } ?: "Add a date",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = c.accent, modifier = Modifier.weight(1f))
                if (captureDue == null)
                    Text("optional", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Note", {
                    when (p.kind) {
                        "image", "document" -> vm.noteImage(p.bytes!!, p.mime, p.text, captureDue)
                        "audio" -> vm.noteAudio(p.bytes!!, p.mime, p.text, captureDue)
                        else -> vm.noteText(p.text, captureDue)
                    }
                    pending = null; captureDue = null
                }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Notes)
                PrimaryButton("Task", {
                    when (p.kind) {
                        "image", "document" -> vm.captureImage(p.bytes!!, p.mime, p.text, captureDue)
                        "audio" -> vm.captureAudio(p.bytes!!, p.mime, p.text, captureDue)
                        else -> vm.capture(p.text, captureDue)
                    }
                    pending = null; captureDue = null
                }, Modifier.weight(1f), icon = Icons.Outlined.CheckBoxOutlineBlank)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Capture", "Get it out of your head. Sort it later.") {
            StatusChip(if (state.paired) "Synced" else "Not paired", state.paired)
        }
        // The buttons are the screen's whole purpose, so they sit low on it,
        // within thumb reach, rather than tucked under the title.
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptureTile("Text", "Type a thought", Icons.Outlined.Edit, primary = true,
                    modifier = Modifier.weight(1f)) {
                    textMode = !textMode; showImageChoice = false
                }
                CaptureTile("Image", "Camera or gallery", Icons.Outlined.Image,
                    modifier = Modifier.weight(1f)) {
                    showImageChoice = !showImageChoice; textMode = false
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptureTile(
                    if (recording) "Stop" else "Audio",
                    if (recording) "Recording… tap to stop" else "Record a memo",
                    if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    alert = recording, modifier = Modifier.weight(1f),
                ) {
                    if (recording) stopRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                CaptureTile("Document", "Attach a file", Icons.Outlined.Description,
                    modifier = Modifier.weight(1f)) {
                    textMode = false; showImageChoice = false
                    pickDocument.launch("*/*")
                }
            }
            if (showImageChoice) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("Gallery", {
                        showImageChoice = false
                        pickImage.launch(PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.weight(1f), icon = Icons.Outlined.PhotoLibrary)
                    SecondaryButton("Camera", { showImageChoice = false; launchCamera() },
                        Modifier.weight(1f), icon = Icons.Outlined.CameraAlt)
                }
            }
            if (textMode) {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("Text note") }, colors = appFieldColors(),
                    shape = RectangleShape, modifier = Modifier.fillMaxWidth())
                PrimaryButton("Save", {
                    if (text.isNotBlank()) {
                        pending = PendingCapture("text", text.trim()); text = ""; textMode = false
                    }
                }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One way in: a large square the thumb can't miss, the label saying what it takes. */
@Composable
private fun CaptureTile(
    label: String,
    hint: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    alert: Boolean = false,
    onClick: () -> Unit,
) {
    val c = HubidubiOsTheme.colors
    val bg = when { alert -> c.danger; primary -> c.button; else -> c.raised }
    val fg = if (alert || primary) c.onButton else c.ink
    val sub = if (alert || primary) c.onButtonMuted else c.muted
    Column(
        modifier.height(148.dp).background(bg)
            .border(1.dp, if (alert || primary) bg else c.line)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, color = fg)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = sub)
        }
    }
}

@Composable
fun NextScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val c = HubidubiOsTheme.colors
    var menuFor by remember { mutableStateOf<EntityRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<EntityRecord?>(null) }
    var reviewing by remember { mutableStateOf<EntityRecord?>(null) }
    var detailFor by remember { mutableStateOf<EntityRecord?>(null) }
    val today = remember { todayIso() }
    val sections = remember(state.dated, today) { Agenda.sections(state.dated, today) }

    ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null }) { vm.delete(it) }
    ItemActionsMenu(
        record = menuFor, canMove = false, vm = vm,
        onMove = {}, onReview = { rec -> reviewing = rec },
        onDelete = { rec -> pendingDelete = rec }, onDismiss = { menuFor = null },
    )
    DetailSheet(detailFor, onReview = { rec -> reviewing = rec },
        onDismiss = { detailFor = null })
    AnalysisSheet(reviewing, state.inference.connected, vm) { reviewing = null }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Next", remember { todayLong() })
        if (sections.isEmpty()) {
            Text("Nothing dated. Add a date when you capture, or long-press an item.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEach { section ->
                val overdue = section.bucket == Agenda.Bucket.OVERDUE
                // Overdue is the one heading that asks for something, so it alone
                // is brick; today is the accent, the rest recede.
                val tone = when (section.bucket) {
                    Agenda.Bucket.OVERDUE -> c.danger
                    Agenda.Bucket.TODAY -> c.accent
                    Agenda.Bucket.TOMORROW -> c.frame
                    Agenda.Bucket.LATER -> c.muted
                }
                item(key = "s_${section.bucket}") {
                    SectionLabel(section.title, tone, section.items.size)
                    CardGroup(border = if (overdue) c.danger else c.line) {
                        section.items.forEachIndexed { i, rec ->
                            ListRow(
                                onClick = { detailFor = rec }, onLongClick = { menuFor = rec },
                                last = i == section.items.lastIndex,
                            ) {
                                // Name left, date hard right: the dates line up in a
                                // column you can scan without reading the names.
                                Text(ReviewBrowse.labelOf(rec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                Text(shortDate(rec.fields["due"].orEmpty()), fontFamily = Mono,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (overdue) c.danger else c.muted)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ChatScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val c = HubidubiOsTheme.colors
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val online = state.paired && state.inference.connected

    // Dictation uses the phone's own recogniser: a spoken question is text by
    // the time it leaves the device, so nothing new travels and nothing new
    // has to run on the workstation.
    val dictate = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!said.isNullOrBlank()) query = if (query.isBlank()) said else "$query $said"
    }
    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask the agent")
        runCatching { dictate.launch(intent) }
            .onFailure { answer = "No speech recogniser on this phone." }
    }
    fun send() {
        val q = query.trim()
        if (q.isNotBlank()) {
            asked = q; query = ""
            scope.launch { answer = "…"; answer = vm.ask(q) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val model = state.inference.models.firstOrNull()
        ScreenHeader("Agent") {
            StatusChip(
                if (!online) "Offline" else if (model != null) "Online · $model" else "Online",
                online,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!online) {
                Text("Online only — needs the workstation and a connected inference server.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.fillMaxWidth().background(c.raised)
                        .border(1.dp, c.line).padding(14.dp))
            }
            if (asked.isNotEmpty()) {
                Text(asked, style = MaterialTheme.typography.bodyMedium, color = c.onButton,
                    modifier = Modifier.align(Alignment.End).fillMaxWidth(0.8f)
                        .background(c.button).padding(horizontal = 14.dp, vertical = 12.dp))
            }
            // No card: the answer is the page, not something sitting on it.
            if (answer.isNotEmpty()) {
                Column {
                    Overline("Agent", c.accent)
                    Text(answer, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        HorizontalDivider(color = c.line, thickness = 1.dp)
        // Ask, speak, send — one row, in that order, docked where the thumb is.
        Row(
            Modifier.fillMaxWidth().background(c.raised)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Ask about your projects…", color = c.muted) },
                enabled = online, maxLines = 4, colors = appFieldColors(),
                shape = RectangleShape, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { listen() }, enabled = online, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Mic, contentDescription = "Dictate",
                    tint = if (online) c.frame else c.line)
            }
            FilledIconButton(
                onClick = { send() }, enabled = online && query.isNotBlank(),
                shape = RectangleShape, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = c.button, contentColor = c.onButton,
                    disabledContainerColor = c.line, disabledContentColor = c.muted,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A field with its label above it rather than inside: it stays readable once filled. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helper: String,
    enabled: Boolean,
    placeholder: String? = null,
) {
    val c = HubidubiOsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it, fontFamily = Mono, color = c.muted) } },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 14.sp),
            singleLine = true, enabled = enabled, colors = appFieldColors(),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth(),
        )
        if (helper.isNotEmpty())
            Text(helper, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean, last: Boolean = false) {
    val c = HubidubiOsTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Dot(if (good) c.ok else c.muted)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!last) HorizontalDivider(color = c.line, thickness = 1.dp)
    }
}

@Composable
fun SettingsScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = HubidubiOsTheme.colors

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
            // A null here means the workstation did not answer, so the fields
            // keep their proposed values rather than silently blanking.
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

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings")
        SectionLabel("Connection", modifier = Modifier.padding(top = 0.dp))
        CardGroup {
            StatusRow("Workstation", if (state.paired) "Paired" else "Not paired", state.paired)
            StatusRow("Inference", state.inference.server ?: "not configured",
                state.inference.connected, last = true)
        }
        val openPairing = { context.startActivity(Intent(context, PairingActivity::class.java)) }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Unpaired, pairing is the only thing this screen can do, so it is
            // the filled button; once paired it steps back beside Sync.
            if (state.paired) {
                SecondaryButton("Re-pair", openPairing, Modifier.weight(1f), icon = Icons.Outlined.Link)
                SecondaryButton(if (state.syncing) "Syncing…" else "Sync now", { vm.syncNow() },
                    Modifier.weight(1f), icon = Icons.Outlined.Sync, enabled = !state.syncing)
            } else {
                PrimaryButton("Pair workstation", openPairing, Modifier.weight(1f),
                    icon = Icons.Outlined.Link)
            }
        }

        SectionLabel("Agent server", modifier = Modifier.padding(top = 10.dp))
        Text("Where the workstation sends questions and captures for summarising.",
            style = MaterialTheme.typography.bodySmall, color = c.muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LabeledField("Server address", endpoint, { endpoint = it; message = null },
                helper = if (endpoint == defaultEndpoint) "Default agent server" else "",
                enabled = state.paired, placeholder = "https://192.168.1.50:8443")

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Protocol", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().border(1.dp, c.glyph)) {
                    listOf("selfhostedai" to "selfhostedai (encrypted)", "openai" to "OpenAI-compatible")
                        .forEach { (value, label) ->
                            val on = protocol == value
                            Box(
                                Modifier.weight(1f).height(44.dp)
                                    .background(if (on) c.button else Color.Transparent)
                                    .clickable(role = Role.RadioButton) {
                                        protocol = value; message = null
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (on) c.onButton else c.ink)
                            }
                        }
                }
            }

            LabeledField("Model", model, { model = it; message = null },
                helper = if (model == defaultModel) "Default for this protocol"
                         else "Custom — the default is $defaultModel",
                enabled = state.paired, placeholder = defaultModel)

            PrimaryButton(
                if (saving) "Saving…" else "Save agent server",
                {
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
                Modifier.fillMaxWidth(),
                enabled = state.paired && !saving && endpoint.isNotBlank(),
            )

            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(if (it.startsWith("Connected")) c.ok else c.muted)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            if (!state.paired) {
                Text("Pair with a workstation first — it is the workstation that talks "
                    + "to the agent server.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ProjectsScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val c = HubidubiOsTheme.colors
    // A project is a flat container holding its items — notes and tasks alike.
    // The shape and move rules live in ProjectTree so they are testable without
    // driving the UI.
    val records = remember(state.projects, state.nextActions, state.notes, state.inbox) {
        ProjectTree.records(state.projects, state.nextActions, state.notes, state.inbox)
    }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf<ProjectTree.Node?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<EntityRecord?>(null) }
    var menuFor by remember { mutableStateOf<EntityRecord?>(null) }
    var reviewing by remember { mutableStateOf<EntityRecord?>(null) }
    var detailFor by remember { mutableStateOf<EntityRecord?>(null) }
    val nodes = remember(records, collapsed) { ProjectTree.build(records, collapsed) }
    // Counted from the full tree, so a collapsed project still says how much it holds.
    val counts = remember(records) {
        val out = HashMap<String, Int>()
        var owner: String? = null
        ProjectTree.build(records).forEach { n ->
            if (n.isProject) { owner = n.id; out[n.id] = 0 }
            else if (n.depth == 1) owner?.let { out[it] = (out[it] ?: 0) + 1 }
        }
        out
    }
    // Each project with the items shown under it, then the loose ones.
    val groups = remember(nodes) {
        val out = mutableListOf<Pair<ProjectTree.Node, MutableList<ProjectTree.Node>>>()
        nodes.forEach { n ->
            if (n.isProject) out += n to mutableListOf()
            else if (n.depth == 1) out.lastOrNull()?.second?.add(n)
        }
        out
    }
    val free = remember(nodes) { nodes.filter { it.isFree } }

    ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null }) { vm.delete(it) }
    ItemActionsMenu(
        record = menuFor, canMove = menuFor?.type != "project", vm = vm,
        onMove = { rec -> moving = nodes.firstOrNull { it.id == rec.id } },
        onReview = { rec -> reviewing = rec },
        onDelete = { rec -> pendingDelete = rec },
        onDismiss = { menuFor = null },
    )
    DetailSheet(detailFor, onReview = { rec -> reviewing = rec },
        onDismiss = { detailFor = null })
    AnalysisSheet(reviewing, state.inference.connected, vm) { reviewing = null }

    moving?.let { node ->
        val targets = remember(node.id, records) { ProjectTree.moveTargets(node.id, records) }
        AppSheet(onDismiss = { moving = null }) {
            SheetTitle(node.title, overline = "Move to")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val current = node.record.fields["parent"]
                targets.forEachIndexed { i, (id, label) ->
                    val here = id == current || (id == null && current.isNullOrBlank())
                    ActionRow(Icons.Outlined.Folder, label, meta = if (here) "here" else null,
                        color = c.accent, enabled = !here, last = i == targets.lastIndex) {
                        vm.move(node.id, id); moving = null
                    }
                }
            }
            SecondaryButton("Cancel", { moving = null }, Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; newTitle = "" },
            containerColor = c.paper,
            title = { Text("New project", style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    label = { Text("Project name") }, singleLine = true,
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                PrimaryButton("Create",
                    { vm.createProject(newTitle); creating = false; newTitle = "" },
                    enabled = newTitle.isNotBlank())
            },
            dismissButton = {
                TextButton(onClick = { creating = false; newTitle = "" }) {
                    Text("Cancel", color = c.ink, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Projects") {
            // The plus alone: on a screen called Projects it can only mean one thing.
            FilledIconButton(
                onClick = { creating = true },
                shape = RectangleShape, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = c.button, contentColor = c.onButton,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New project")
            }
        }
        if (nodes.isEmpty()) {
            Text("No projects yet. Tap + to start one, then file notes and tasks into it.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (project, items) ->
                item(key = project.id) {
                    CardGroup(Modifier.padding(bottom = 10.dp)) {
                        ProjectHeader(
                            project, counts[project.id] ?: 0,
                            expanded = project.id !in collapsed,
                            onToggle = {
                                if (project.hasChildren) {
                                    collapsed = if (project.id in collapsed)
                                        collapsed - project.id else collapsed + project.id
                                }
                            },
                            onLongClick = { menuFor = project.record },
                        )
                        items.forEachIndexed { i, node ->
                            // No date here, however close it is: this list is for
                            // finding things. Dates live in Next, and in the sheet
                            // the item opens into.
                            ListRow(
                                onClick = { detailFor = node.record },
                                onLongClick = { menuFor = node.record },
                                last = i == items.lastIndex, startPadding = 44,
                                leading = { KindGlyph(node.record) },
                            ) {
                                Text(node.title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                AttachmentTag(node.record)
                            }
                        }
                    }
                }
            }
            if (free.isNotEmpty()) {
                // The loose items are their own group, named, so the first one
                // doesn't read as the last project's.
                item(key = "free") {
                    SectionLabel("Not in a project", count = free.size,
                        modifier = Modifier.padding(top = 0.dp))
                    CardGroup {
                        free.forEachIndexed { i, node ->
                            ListRow(
                                onClick = { detailFor = node.record },
                                onLongClick = { menuFor = node.record },
                                last = i == free.lastIndex,
                                leading = { KindGlyph(node.record) },
                            ) {
                                Text(node.title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                AttachmentTag(node.record)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * A project's name row. Projects carry the accent colour: scanning the list,
 * the containers separate from their contents at a glance.
 */
@Composable
private fun ProjectHeader(
    node: ProjectTree.Node,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = HubidubiOsTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongClick,
                    onClickLabel = if (expanded) "Collapse" else "Expand")
                .defaultMinSize(minHeight = 52.dp)
                .padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (node.hasChildren || count > 0) {
                Icon(if (expanded) Icons.Filled.KeyboardArrowDown
                     else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Text(node.title, style = MaterialTheme.typography.titleMedium, color = c.accent,
                modifier = Modifier.weight(1f))
            Text(count.toString(), fontFamily = Mono, style = MaterialTheme.typography.labelMedium,
                color = c.muted)
        }
        if (expanded && node.hasChildren) HorizontalDivider(color = c.line, thickness = 1.dp)
    }
}
