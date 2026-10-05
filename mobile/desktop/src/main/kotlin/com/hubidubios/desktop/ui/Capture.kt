package com.hubidubios.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/** A capture waiting for the user to say whether it is a task or a note.
 *
 *  Nothing is filed until that is answered: a file means different things in
 *  the inbox ("do something about this") and in notes ("keep this"), and only
 *  the user knows which. `bytes == null` is words, not an attachment. */
internal data class PendingCapture(
    val kind: String,          // "text" | "image" | "audio" | "document"
    val text: String,
    val bytes: ByteArray? = null,
    val mime: String = "",
    /** For words that came with more below the first line: a text file. */
    val body: String = "",
)

/**
 * What a file becomes when it is dropped or chosen. A text file is the note
 * itself: its words go in as the words, not as an attachment. Anything else
 * is kept as it is, under its own name.
 */
internal fun pendingFrom(file: File): PendingCapture? {
    if (!file.isFile) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val mime = mimeOf(file)
    return when {
        mime.startsWith("text/") && bytes.size <= 512 * 1024 -> {
            val content = bytes.decodeToString().trim()
            val lines = content.lines()
            val first = lines.firstOrNull { it.isNotBlank() }?.trim(' ', '#', '-', '*')
                ?.ifBlank { null } ?: file.nameWithoutExtension
            val rest = lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim()
            PendingCapture("text", first, body = rest)
        }
        mime.startsWith("image/") -> PendingCapture("image", file.name, bytes, mime)
        mime.startsWith("audio/") -> PendingCapture("audio", file.name, bytes, mime)
        else -> PendingCapture("document", file.name, bytes, mime)
    }
}

private fun mimeOf(file: File): String =
    when (file.extension.lowercase()) {
        "md", "markdown" -> "text/markdown"
        "txt", "text" -> "text/plain"
        else -> runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
    } ?: "application/octet-stream"

/** The Mac's own open panel. Blocks until closed; null when nothing was picked. */
private fun chooseFile(title: String): File? {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isMultipleMode = false
    d.isVisible = true
    return d.files.firstOrNull()
}

/** A voice memo from the Mac's microphone, kept as 16-bit mono WAV. */
private class AudioRecorder {
    private val format = AudioFormat(44100f, 16, 1, true, false)
    private val pcm = ByteArrayOutputStream()
    private var line: TargetDataLine? = null
    private var pump: Thread? = null

    fun start() {
        val l = AudioSystem.getTargetDataLine(format)
        l.open(format); l.start(); line = l
        pump = Thread {
            val buf = ByteArray(4096)
            while (l.isOpen) {
                val n = runCatching { l.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n > 0) pcm.write(buf, 0, n) else if (n < 0) break
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop(): ByteArray {
        val l = line ?: return ByteArray(0)
        l.stop(); l.close(); pump?.join(2000)
        val bytes = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(bytes), format, (bytes.size / format.frameSize).toLong()),
            AudioFileFormat.Type.WAVE, out)
        return out.toByteArray()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HubidubiOsViewModel.UiState, vm: HubidubiOsViewModel) {
    val c = HubidubiOsTheme.colors
    var textMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<AudioRecorder?>(null) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }
    var dragging by remember { mutableStateOf(false) }

    fun startRecording() {
        runCatching { AudioRecorder().also { it.start(); recorder.value = it } }
            .onSuccess { recording = true }
    }
    fun stopRecording() {
        val bytes = recorder.value?.stop(); recorder.value = null; recording = false
        if (bytes != null && bytes.isNotEmpty())
            pending = PendingCapture("audio", "Voice memo", bytes, "audio/wav")
    }

    // Ask before filing: the same file is a task ("do something about this") or
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
                TextButton(onClick = { captureDue = null; pickingDate = false }) { Text("No date") }
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
                Column(Modifier.weight(1f)) {
                    Text(p.text, style = MaterialTheme.typography.titleSmall, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                    if (p.body.isNotBlank())
                        Text(p.body, style = MaterialTheme.typography.bodySmall, color = c.muted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                    .border(1.dp, c.glyph).clickable(role = Role.Button) { pickingDate = true }
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
                        "text" -> vm.noteText(p.text, captureDue, p.body)
                        else -> vm.noteMedia(p.kind, p.bytes!!, p.mime, p.text, captureDue)
                    }
                    pending = null; captureDue = null
                }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Notes)
                PrimaryButton("Task", {
                    when (p.kind) {
                        // A task is one line. A short file fits; a long one is named.
                        "text" -> vm.capture(
                            if (p.body.isBlank()) p.text
                            else if (p.body.length <= 160) p.text + "\n" + p.body else p.text,
                            captureDue)
                        else -> vm.captureMedia(p.kind, p.bytes!!, p.mime, p.text, captureDue)
                    }
                    pending = null; captureDue = null
                }, Modifier.weight(1f), icon = Icons.Outlined.CheckBoxOutlineBlank)
            }
        }
    }

    // Anything dropped on the screen is a capture, the same as choosing it.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val t = event.awtTransferable
                if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                pending = files.firstNotNullOfOrNull { pendingFrom(it) } ?: return false
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .border(2.dp, if (dragging) c.accent else androidx.compose.ui.graphics.Color.Transparent)
    ) {
        ScreenHeader("Capture", "Get it out of your head. Sort it later.") {
            StatusChip(if (state.paired) "Phone paired" else "No phone paired", state.paired)
        }
        Column(
            Modifier.padding(horizontal = 20.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptureTile("Text", "Type a thought", Icons.Outlined.Edit, primary = true,
                    modifier = Modifier.weight(1f)) { textMode = !textMode }
                CaptureTile("Image", "Choose a picture", Icons.Outlined.Image,
                    modifier = Modifier.weight(1f)) {
                    textMode = false
                    chooseFile("Choose a picture")?.let { pending = pendingFrom(it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CaptureTile(
                    if (recording) "Stop" else "Audio",
                    if (recording) "Recording… click to stop" else "Record a memo",
                    if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    alert = recording, modifier = Modifier.weight(1f),
                ) { if (recording) stopRecording() else startRecording() }
                CaptureTile("Document", "Attach a file", Icons.Outlined.Description,
                    modifier = Modifier.weight(1f)) {
                    textMode = false
                    chooseFile("Attach a file")?.let { pending = pendingFrom(it) }
                }
            }
            if (textMode) {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    label = { Text("What's on your mind?") }, minLines = 3, colors = appFieldColors(),
                    shape = RectangleShape, modifier = Modifier.fillMaxWidth())
                PrimaryButton("Save", {
                    if (text.isNotBlank()) {
                        pending = PendingCapture("text", text.trim()); text = ""; textMode = false
                    }
                }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
            }
            Text(
                if (dragging) "Drop it here." else "Or drop a file anywhere on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = if (dragging) c.accent else c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One way in: a large square, the label saying what it takes. As on the phone. */
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
