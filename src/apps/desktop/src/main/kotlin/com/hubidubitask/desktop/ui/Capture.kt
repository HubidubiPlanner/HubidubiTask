package com.hubidubitask.desktop.ui

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
import com.hubidubitask.core.i18n.t
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

/** What the "+" offers that needs the Mac itself: the file panel, the
 *  microphone, and files dropped on the window. Each ends in the same
 *  "Task or note?" sheet. */
internal class CaptureActions(
    val picture: () -> Unit,
    val voice: () -> Unit,
    val file: () -> Unit,
    /** A file dropped on the window; false if it can't be taken. */
    val drop: (List<File>) -> Boolean,
)

/** Draws the sheets those captures need and hands back the ways to start one. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun rememberCaptureActions(vm: HubidubiTaskViewModel): CaptureActions {
    val c = HubidubiTaskTheme.colors
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<AudioRecorder?>(null) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }

    fun startRecording() {
        runCatching { AudioRecorder().also { it.start(); recorder.value = it } }
            .onSuccess { recording = true }
    }
    fun stopRecording() {
        val bytes = recorder.value?.stop(); recorder.value = null; recording = false
        if (bytes != null && bytes.isNotEmpty())
            pending = PendingCapture("audio", t("Voice memo"), bytes, "audio/wav")
    }

    // A date is offered with the question: the one fact still in mind at
    // capture time, and what decides whether the item ever resurfaces.
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
                }) { Text(t("Set")) }
            },
            dismissButton = {
                TextButton(onClick = { captureDue = null; pickingDate = false }) { Text(t("No date")) }
            },
        ) { DatePicker(state = dateState) }
    }

    if (recording) {
        AppSheet(onDismiss = { stopRecording() }) {
            SheetTitle(t("Recording…"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(t("Discard"), {
                    recorder.value?.stop(); recorder.value = null; recording = false
                }, Modifier.weight(1f))
                PrimaryButton(t("Stop"), { stopRecording() }, Modifier.weight(1f),
                    icon = Icons.Outlined.Stop)
            }
        }
    }

    pending?.let { p ->
        AppSheet(onDismiss = { pending = null }) {
            SheetTitle(t("Task or note?"))
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
                Text(captureDue?.let { t("Due %s", shortDate(it)) } ?: t("Add a date"),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = c.accent, modifier = Modifier.weight(1f))
                if (captureDue == null)
                    Text(t("optional"), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(t("Note"), {
                    when (p.kind) {
                        "text" -> vm.noteText(p.text, captureDue, p.body)
                        else -> vm.noteMedia(p.kind, p.bytes!!, p.mime, p.text, captureDue)
                    }
                    pending = null; captureDue = null
                }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Notes)
                PrimaryButton(t("Task"), {
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

    return CaptureActions(
        picture = { chooseFile(t("Choose a picture"))?.let { pending = pendingFrom(it) } },
        voice = { startRecording() },
        file = { chooseFile(t("Attach a file"))?.let { pending = pendingFrom(it) } },
        drop = { files ->
            files.firstNotNullOfOrNull { pendingFrom(it) }?.let { pending = it; true } ?: false
        },
    )
}

/** Files dropped anywhere on [Modifier]'s area go to [onFiles]; [onHover]
 *  says while something is being dragged over it. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onFiles: (List<File>) -> Boolean): Modifier =
    dragAndDropTarget(shouldStartDragAndDrop = { true }, target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) = onHover(true)
        override fun onExited(event: DragAndDropEvent) = onHover(false)
        override fun onEnded(event: DragAndDropEvent) = onHover(false)
        override fun onDrop(event: DragAndDropEvent): Boolean {
            onHover(false)
            val t = event.awtTransferable
            if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
            @Suppress("UNCHECKED_CAST")
            return onFiles(t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
        }
    })

/** A note written straight in: a title, and more below it if there is more. */
@Composable
internal fun NewNoteSheet(open: Boolean, vm: HubidubiTaskViewModel, onDismiss: () -> Unit) {
    if (!open) return
    val c = HubidubiTaskTheme.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AppSheet(onDismiss) {
        SheetTitle(t("A note"))
        OutlinedTextField(value = title, onValueChange = { title = it },
            label = { Text(t("Note")) }, singleLine = true, colors = appFieldColors(),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = body, onValueChange = { body = it },
            label = { Text(t("More (optional)"), color = c.muted) }, minLines = 3,
            colors = appFieldColors(), shape = RectangleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        PrimaryButton(t("Save"), { vm.noteText(title.trim(), body = body.trim()); onDismiss() },
            Modifier.fillMaxWidth().padding(top = 18.dp), enabled = title.isNotBlank())
    }
}
