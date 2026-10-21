package com.hubidubitask.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Description
import com.hubidubitask.core.Backup
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.importer.ImportIcon
import com.hubidubitask.mobile.importer.ImportInput
import com.hubidubitask.mobile.importer.ImportPlan
import com.hubidubitask.mobile.importer.ImportSource
import com.hubidubitask.mobile.importer.ImportSources
import com.hubidubitask.mobile.importer.ImportText
import com.hubidubitask.mobile.importer.ImportValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time import from another app, reached from the bottom of Settings.
 *
 * Choose a source ([ImportSources], then the HubidubiTask backup), type or
 * pick what it needs, see what would come in, confirm.
 * Nothing is kept: the token or address is held only by this screen until the
 * source has been read, then dropped; there is no ongoing sync and no record
 * of the import beyond the items it created.
 */
@Composable
fun ImportScreen(vm: HubidubiTaskViewModel, onBack: () -> Unit) {
    val c = HubidubiTaskTheme.colors
    val scope = rememberCoroutineScope()
    // Plain remember, not rememberSaveable: a saved-state bundle would outlive
    // the screen, and the secret must not.
    var source by remember { mutableStateOf<ImportSource?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var backup by remember { mutableStateOf<Pair<java.io.File, Backup.Plan>?>(null) }
    // A sealed backup waits here for its password; the password lives as long as the screen.
    var sealedFile by remember { mutableStateOf<java.io.File?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<String?>(null) }

    fun reset() {
        source = null; restoring = false; secret = ""; error = null; plan = null; backup = null
        sealedFile = null; backupPassword = ""; busy = false
    }
    // A backup is a file, not a token: the system's file picker, then what it holds.
    // What a backup would change, read once its password is known (or there is none).
    fun plan(file: java.io.File) {
        busy = true; error = null
        scope.launch {
            val password = backupPassword.ifEmpty { null }?.toCharArray()
            runCatching { vm.planBackup(file, password) }
                .onSuccess { backup = file to it; sealedFile = null }
                .onFailure {
                    error = when (it) {
                        is com.hubidubitask.core.Sealed.WrongPassword -> t("Wrong password, or the file is damaged.")
                        is com.hubidubitask.core.Sealed.Damaged -> t("The backup is damaged or incomplete.")
                        is Backup.NotABackup -> t("That file isn't a HubidubiTask backup.")
                        else -> it.message ?: t("Couldn't read it.")
                    }
                }
            busy = false
        }
    }
    // A sealed one asks for its password first.
    fun opened(file: java.io.File) {
        scope.launch {
            if (runCatching { vm.backupIsSealed(file) }.getOrDefault(false)) { sealedFile = file; error = null }
            else plan(file)
        }
    }
    fun pick() {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, t("HubidubiTask backup"), java.awt.FileDialog.LOAD)
            .apply { isVisible = true }
        val name = dialog.file ?: return
        opened(java.io.File(dialog.directory, name))
    }

    // What a source would bring in, from what was typed or picked.
    fun read(src: ImportSource, value: ImportValue) {
        busy = true; error = null
        scope.launch {
            runCatching { vm.planImport(src, value) }
                .onSuccess { plan = it; secret = "" }   // read once; not needed any more
                .onFailure { error = ImportText.error(src, it) }
            busy = false
        }
    }
    // A source that reads a file: the system's file dialog, then its bytes.
    fun pickFor(src: ImportSource, input: ImportInput.File) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, src.title, java.awt.FileDialog.LOAD).apply {
            if (input.extensions.isNotEmpty())
                setFilenameFilter { _, name -> input.extensions.any { name.lowercase().endsWith(".$it") } }
            isVisible = true
        }
        val name = dialog.file ?: return
        val file = java.io.File(dialog.directory, name)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                if (file.length() > ImportValue.FILE_LIMIT) null else file.readBytes()
            }
            if (bytes == null) error = t("That file is too large.") else read(src, ImportValue.File(name, bytes))
        }
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        IconButton(onClick = { if ((source != null || restoring) && done == null) reset() else onBack() },
            modifier = Modifier.padding(start = 8.dp, top = 8.dp).size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = c.ink)
        }
        ScreenHeader(
            when { restoring -> t("HubidubiTask backup"); else -> source?.title ?: t("Import") },
            t("One time. Nothing is kept afterwards — not the token, not the address."),
        )

        val body = Modifier.padding(horizontal = 20.dp)
        when {
            done != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(done!!, style = MaterialTheme.typography.bodyLarge)
                PrimaryButton(t("Done"), onBack, Modifier.fillMaxWidth())
            }

            source == null && !restoring -> {
                SectionLabel(t("Import from"), modifier = Modifier.padding(top = 0.dp))
                CardGroup {
                    ImportSources.all.forEach { src ->
                        SourceRow(src.icon.vector(), src.title, src.subtitle) { source = src }
                    }
                    SourceRow(Icons.Outlined.Restore, t("HubidubiTask backup"),
                        t("Everything, from Export"), last = true) { restoring = true }
                }
            }

            plan != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val p = plan!!
                Summary(p.lines.map { Line(it.text, it.muted) }, p.lost)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                Confirm(busy, p.empty, onCancel = ::reset) {
                    busy = true
                    scope.launch {
                        val n = runCatching { vm.applyImport(p) }
                        busy = false
                        n.onSuccess { done = ImportText.done(it, onComputer = true) }
                            .onFailure { error = it.message ?: t("The import failed.") }
                    }
                }
            }

            backup != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val (file, p) = backup!!
                Summary(buildList {
                    add(Line(t("%d items new to this device", p.added)))
                    if (p.replaced > 0) add(Line(t("%d newer than here — they replace what is here", p.replaced)))
                    if (p.media > 0) add(Line(t("%d photos, voice memos and files", p.media)))
                    if (p.alreadyHere > 0) add(Line(t("%d already in the app — skipped", p.alreadyHere), true))
                }, emptyList())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                Confirm(busy, p.incoming.isEmpty(), onCancel = ::reset) {
                    busy = true
                    scope.launch {
                        val n = runCatching { vm.restoreBackup(file, p, backupPassword.ifEmpty { null }?.toCharArray()) }
                        busy = false
                        n.onSuccess {
                            done = t("Restored %d items. They reach your phone on the next sync.", it)
                        }.onFailure { error = it.message ?: t("The import failed.") }
                    }
                }
            }

            sealedFile != null -> Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("Password"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = backupPassword, onValueChange = { backupPassword = it; error = null },
                    singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                PrimaryButton(if (busy) t("Reading…") else t("Continue"), { plan(sealedFile!!) },
                    Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy && backupPassword.isNotEmpty())
            }

            restoring -> Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                PrimaryButton(if (busy) t("Reading…") else t("Choose the file"), { pick() },
                    Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy)
            }

            else -> Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val src = source!!
                when (val input = src.input) {
                    is ImportInput.File -> {
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                        PrimaryButton(if (busy) t("Reading…") else t("Choose the file"), { pickFor(src, input) },
                            Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy)
                    }
                    is ImportInput.Secret, is ImportInput.Address -> {
                        val hidden = input is ImportInput.Secret
                        val (label, howTo) = when (input) {
                            is ImportInput.Secret -> input.label to input.howTo
                            is ImportInput.Address -> input.label to input.howTo
                            else -> "" to null
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = secret, onValueChange = { secret = it; error = null },
                            singleLine = true, enabled = !busy,
                            visualTransformation = if (hidden) PasswordVisualTransformation()
                                                   else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Uri,
                                autoCorrectEnabled = false),
                            colors = appFieldColors(), shape = RectangleShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        howTo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger)
                        }
                        PrimaryButton(if (busy) t("Reading…") else t("Continue"),
                            { read(src, ImportValue.Text(secret)) },
                            Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy && secret.isNotBlank())
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class Line(val text: String, val muted: Boolean = false)

/** What would come in, then — muted — what has no place in the app. */
@Composable
private fun Summary(lines: List<Line>, lost: List<String>) {
    val c = HubidubiTaskTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium,
            color = if (it.muted) c.muted else c.ink) }
        if (lost.isNotEmpty()) {
            Text(t("NOT CARRIED OVER"), style = MaterialTheme.typography.labelSmall, color = c.muted,
                modifier = Modifier.padding(top = 8.dp))
            lost.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
    }
}

@Composable
private fun Confirm(busy: Boolean, nothing: Boolean, onCancel: () -> Unit, onImport: () -> Unit) {
    if (nothing) {
        Text(t("Nothing new to import."), style = MaterialTheme.typography.bodyMedium,
            color = HubidubiTaskTheme.colors.muted)
        SecondaryButton(t("Back"), onCancel, Modifier.fillMaxWidth())
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(t("Cancel"), onCancel, Modifier.weight(1f), enabled = !busy)
        PrimaryButton(if (busy) t("Importing…") else t("Import"), onImport, Modifier.weight(1f),
            enabled = !busy)
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, sub: String, last: Boolean = false,
                      onClick: () -> Unit) {
    val c = HubidubiTaskTheme.colors
    ListRow(onClick = onClick, last = last,
        leading = { Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp)) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.muted,
            modifier = Modifier.size(20.dp).align(Alignment.CenterVertically))
    }
}

/** How this app draws a source's icon. */
private fun ImportIcon.vector(): ImageVector = when (this) {
    ImportIcon.TASKS -> Icons.Outlined.TaskAlt
    ImportIcon.CALENDAR -> Icons.Outlined.CalendarMonth
    ImportIcon.FILE -> Icons.Outlined.Description
}
