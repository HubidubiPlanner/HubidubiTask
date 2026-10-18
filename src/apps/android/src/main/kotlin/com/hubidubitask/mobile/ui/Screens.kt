@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hubidubitask.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hubidubitask.core.Backup
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.NewItemDue
import com.hubidubitask.core.agent.AgentConfig.Companion.PROTOCOLS
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.browse.Agenda
import com.hubidubitask.mobile.browse.ItemDetail
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.browse.ReviewBrowse
import com.hubidubitask.mobile.capture.AudioRecorder
import com.hubidubitask.mobile.tree.ProjectTree
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/** Task or note, at a glance: an empty box is something to do, lines are something kept. */
@Composable
private fun KindGlyph(record: EntityRecord) {
    val c = HubidubiTaskTheme.colors
    if (record.type == "note")
        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = t("Note"),
            tint = c.muted, modifier = Modifier.size(16.dp))
    else
        Icon(Icons.Outlined.CheckBoxOutlineBlank, contentDescription = t("Task"),
            tint = c.glyph, modifier = Modifier.size(16.dp))
}

/** "Photo", "Voice memo" beside a title: the attachment reads as a property of the item. */
@Composable
private fun AttachmentTag(record: EntityRecord) {
    if (record.fields["media_sha256"].isNullOrBlank()) return
    val label = when (record.fields["kind"]) {
        "image" -> t("Photo")
        "audio" -> t("Voice memo")
        "document" -> t("Document")
        else -> return
    }
    val c = HubidubiTaskTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, tint = c.muted,
            modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/** Something that can be finished: a task or a capture, not a note or an event. */
private fun isTask(r: EntityRecord) = r.type == "next_action" || r.type == "inbox_item"

/** "3 notes" beside a task's title: says there is more inside without listing it. */
@Composable
private fun SubtaskTag(count: Int) {
    if (count == 0) return
    val c = HubidubiTaskTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null, tint = c.muted,
            modifier = Modifier.size(14.dp))
        Text(I18n.count(count, "note"),
            style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/** Write a note under a task: a title, and optionally more. */
@Composable
private fun AddNoteSheet(task: EntityRecord?, vm: HubidubiTaskViewModel, onDismiss: () -> Unit) {
    task ?: return
    val c = HubidubiTaskTheme.colors
    var title by remember(task.id) { mutableStateOf("") }
    var body by remember(task.id) { mutableStateOf("") }
    AppSheet(onDismiss) {
        SheetTitle(t("Add a note"), overline = ReviewBrowse.labelOf(task))
        OutlinedTextField(value = title, onValueChange = { title = it },
            label = { Text(t("Note")) }, singleLine = true, colors = appFieldColors(),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = body, onValueChange = { body = it },
            label = { Text(t("More (optional)"), color = c.muted) }, minLines = 3,
            colors = appFieldColors(), shape = RectangleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        PrimaryButton(t("Save"), { vm.addNote(task.id, title, body); onDismiss() },
            Modifier.fillMaxWidth().padding(top = 18.dp), enabled = title.isNotBlank())
    }
}

/** A task straight into a project: what to do. Its date is the one Settings gives new tasks. */
@Composable
private fun AddTaskSheet(
    project: EntityRecord?,
    vm: HubidubiTaskViewModel,
    onAdded: (EntityRecord) -> Unit,
    // No project: the task goes to the inbox, as a capture did.
    loose: Boolean = false,
    onDismiss: () -> Unit,
) {
    if (project == null && !loose) return
    val key = project?.id ?: "loose"
    var text by remember(key) { mutableStateOf("") }
    AppSheet(onDismiss) {
        SheetTitle(t("Add a task"), overline = project?.let { ReviewBrowse.labelOf(it) } ?: t("No project"))
        OutlinedTextField(value = text, onValueChange = { text = it },
            label = { Text(t("Task")) }, singleLine = true, colors = appFieldColors(),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth())
        PrimaryButton(t("Add"), {
            if (project != null) { vm.addTask(project, text); onAdded(project) }
            else vm.capture(text.trim())
            onDismiss()
        },
            Modifier.fillMaxWidth().padding(top = 18.dp), enabled = text.isNotBlank())
    }
}

/** Give a project a new name, starting from the one it has. */
@Composable
private fun RenameProjectDialog(project: EntityRecord?, vm: HubidubiTaskViewModel, onDismiss: () -> Unit) {
    project ?: return
    val c = HubidubiTaskTheme.colors
    val current = project.fields["title"].orEmpty()
    // Opens focused with the whole name selected: typing replaces it, a tap
    // places the cursor to edit it.
    var field by remember(project.id) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current,
            androidx.compose.ui.text.TextRange(0, current.length)))
    }
    val title = field.text
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(project.id) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text(t("Rename project"), style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = field, onValueChange = { field = it },
                label = { Text(t("Project name")) }, singleLine = true,
                colors = appFieldColors(), shape = RectangleShape,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            PrimaryButton(t("Rename"), { vm.renameProject(project.id, title); onDismiss() },
                enabled = title.isNotBlank() && title.trim() != current)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel"), color = c.ink, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
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
    val c = HubidubiTaskTheme.colors
    val label = record.fields["title"] ?: record.fields["text"] ?: record.id
    val hasMedia = !record.fields["media_sha256"].isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Text(t("Delete?"), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                if (hasMedia) {
                    Text(
                        t("Its attachment is deleted too."),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(record.id); onDismiss() }) {
                Text(t("Delete"), color = c.danger, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel"), color = c.ink, style = MaterialTheme.typography.labelLarge)
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
    vm: HubidubiTaskViewModel,
    // The record travels with the callback. Reading it from the caller's state
    // afterwards returns null, because closing the menu clears that state
    // first — which silently did nothing at all.
    onMove: (EntityRecord) -> Unit,
    onReview: (EntityRecord) -> Unit,
    onDelete: (EntityRecord) -> Unit,
    onDismiss: () -> Unit,
    onAddNote: ((EntityRecord) -> Unit)? = null,
    onAddTask: ((EntityRecord) -> Unit)? = null,
    onRename: ((EntityRecord) -> Unit)? = null,
) {
    record ?: return
    val c = HubidubiTaskTheme.colors
    val due = record.fields["due"].orEmpty()
    var picking by remember(record.id) { mutableStateOf(false) }

    if (picking) {
        DatePickerFor(record, vm) { picking = false; onDismiss() }
        return
    }
    val state by vm.state.collectAsStateWithLifecycle()
    AppSheet(onDismiss) {
        SheetTitle(ReviewBrowse.labelOf(record), overline = ItemDetail.typeLabel(record))
        ReviewNote(state.reviews[record.id], Modifier.padding(bottom = 12.dp))
        if (record.type == "project") {
            if (onAddTask != null)
                ActionRow(Icons.Filled.Add, t("Add a task")) { onDismiss(); onAddTask(record) }
            if (onRename != null)
                ActionRow(Icons.Outlined.Edit, t("Rename")) { onDismiss(); onRename(record) }
        }
        if (canMove) {
            ActionRow(Icons.AutoMirrored.Outlined.DriveFileMove, t("Move to a project")) {
                onDismiss(); onMove(record)
            }
        }
        if (isTask(record)) {
            ActionRow(Icons.Outlined.Check, t("Mark done")) { onDismiss(); vm.complete(record) }
            if (onAddNote != null)
                ActionRow(Icons.AutoMirrored.Filled.Notes, t("Add a note")) { onDismiss(); onAddNote(record) }
        }
        ActionRow(Icons.Outlined.CalendarToday, if (due.isBlank()) t("Set a date") else t("Date"),
            meta = due.takeIf { it.isNotBlank() }?.let(::shortDate)) { picking = true }
        ActionRow(Icons.Outlined.RateReview, t("Review with AI")) {
            onDismiss(); onReview(record)
        }
        ActionRow(Icons.Outlined.DeleteOutline, t("Delete"), color = c.danger, last = true) {
            onDismiss(); onDelete(record)
        }
        SecondaryButton(t("Cancel"), onDismiss, Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/** Pick a date for an item. Dated items are what the Next view lists. */
@Composable
private fun DatePickerFor(
    record: EntityRecord,
    vm: HubidubiTaskViewModel,
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
            }) { Text(t("Set")) }
        },
        dismissButton = {
            Row {
                if (!record.fields["due"].isNullOrBlank()) {
                    TextButton(onClick = { vm.setDue(record.id, null); onDone() }) {
                        Text(t("Clear"))
                    }
                }
                TextButton(onClick = onDone) { Text(t("Cancel")) }
            }
        },
    ) { DatePicker(state = state) }
}

/** The small tracked heading inside a sheet or card: REVIEW, AGENT. */
@Composable
private fun Overline(text: String, color: Color, icon: ImageVector? = null,
                     modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(14.dp)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * What the agent made of an item, under its title: a few lines until tapped,
 * so a long summary does not push what the sheet is for out of sight, and
 * marked once anything it read has changed. Nothing at all, not even space,
 * for an item never reviewed.
 */
@Composable
private fun ReviewNote(status: ItemSummary.Status?, modifier: Modifier = Modifier) {
    status ?: return
    val c = HubidubiTaskTheme.colors
    var open by remember(status.text) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line)
            .clickable(onClickLabel = t("Review")) { open = !open }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(t("Review"), c.frame, Icons.Outlined.RateReview, Modifier.weight(1f))
            if (!status.current)
                Text(t("Not up to date"), style = MaterialTheme.typography.labelSmall, color = c.danger)
        }
        Text(
            status.text, style = MaterialTheme.typography.bodyMedium,
            color = if (status.current) c.ink else c.muted,
            maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
                .then(if (open) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                      else Modifier),
        )
    }
}

/**
 * One item, opened with a single press: what it is, when it is due, and what
 * the agent made of it last time. Reading, mostly — the long press is still
 * where things are moved, dated and deleted. A note is the exception: its
 * words are the note, so a tap on the title or the body edits them in place.
 */
@Composable
private fun DetailSheet(
    record: EntityRecord?,
    onReview: (EntityRecord) -> Unit,
    onDismiss: () -> Unit,
    subtasks: List<EntityRecord> = emptyList(),
    onOpen: (EntityRecord) -> Unit = {},
    onDone: ((EntityRecord) -> Unit)? = null,
    onEdit: ((EntityRecord) -> Unit)? = null,
    review: ItemSummary.Status? = null,
) {
    record ?: return
    val c = HubidubiTaskTheme.colors
    val due = ItemDetail.dueOf(record)
    val overdue = due != null && due < todayIso()
    val editable = onEdit != null && record.type == "note"
    var editing by remember(record.id) { mutableStateOf(false) }
    var title by remember(record.id) { mutableStateOf(record.fields["title"].orEmpty()) }
    var body by remember(record.id) { mutableStateOf(record.fields["body"].orEmpty()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun startEditing() { if (editable) editing = true }
    fun save() {
        editing = false
        onEdit?.invoke(record.copy(fields = record.fields + ("title" to title.trim()) + ("body" to body.trim())))
    }
    fun cancel() {
        title = record.fields["title"].orEmpty()
        body = record.fields["body"].orEmpty()
        editing = false
    }
    AppSheet(onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Overline(ItemDetail.typeLabel(record), c.accent)
            if (editing) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text(t("Note")) }, singleLine = true, colors = appFieldColors(),
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                        .focusRequester(focus))
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text(ReviewBrowse.labelOf(record), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        .clickable(enabled = editable, onClickLabel = t("Edit")) { startEditing() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                due?.let {
                    val tint = if (overdue) c.danger else c.muted
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = tint,
                            modifier = Modifier.size(14.dp))
                        Text(t("Due %s", shortDate(it)), style = MaterialTheme.typography.bodySmall,
                            color = tint)
                    }
                }
                ItemDetail.capturedAt(record)?.let {
                    Text(t("Captured %s", it), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            ReviewNote(review, Modifier.padding(top = 12.dp))
            if (editing) {
                OutlinedTextField(value = body, onValueChange = { body = it },
                    label = { Text(t("More (optional)"), color = c.muted) }, minLines = 3,
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            } else {
                val shown = record.fields["body"]?.takeIf { it.isNotBlank() }
                if (shown != null) {
                    Text(shown, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp)
                            .clickable(enabled = editable, onClickLabel = t("Edit")) { startEditing() })
                } else if (editable) {
                    // Nothing written yet: name the place, so the tap has a target.
                    Text(t("Add more…"), style = MaterialTheme.typography.bodyLarge, color = c.muted,
                        modifier = Modifier.padding(top = 16.dp).clickable { startEditing() })
                }
            }
            // A task's notes show here and only here: the lists stay one line
            // per thing to do, and what you need to know is one tap away.
            if (subtasks.isNotEmpty()) {
                Overline(t("Notes · %d", subtasks.size), c.accent,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                Column(Modifier.fillMaxWidth().background(c.raised).border(1.dp, c.line)) {
                    subtasks.forEachIndexed { i, sub ->
                        ListRow(onClick = { onOpen(sub) }, last = i == subtasks.lastIndex,
                            leading = { KindGlyph(sub) }) {
                            Text(ReviewBrowse.labelOf(sub), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            sub.fields["due"]?.takeIf { it.isNotBlank() }?.let {
                                Text(shortDate(it), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                        }
                    }
                }
            }
        }
        // Finishing is what a task is opened for most often, so it is the
        // filled button. No Close: the sheet goes with a swipe down or Back.
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing) {
                PrimaryButton(t("Save"), { save() }, Modifier.weight(1f), icon = Icons.Outlined.Check,
                    enabled = title.isNotBlank())
                SecondaryButton(t("Cancel"), { cancel() }, Modifier.weight(1f))
            } else {
                if (onDone != null && isTask(record)) {
                    PrimaryButton(t("Mark done"), { val r = record; onDismiss(); onDone(r) },
                        Modifier.weight(1f), icon = Icons.Outlined.Check)
                }
                SecondaryButton(if (review == null) t("Review") else t("Review again"),
                    { val r = record; onDismiss(); onReview(r) },
                    Modifier.weight(1f), icon = Icons.Outlined.RateReview)
            }
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
    vm: HubidubiTaskViewModel,
    onDismiss: () -> Unit,
) {
    record ?: return
    val c = HubidubiTaskTheme.colors
    // A summary that still describes what is there is shown as it is: it does
    // not improve by being bought twice, and a cold model takes a minute to
    // say the same. Once anything it read has changed, it is asked for again.
    val state by vm.state.collectAsStateWithLifecycle()
    val kept = remember(record.id) { state.reviews[record.id]?.takeIf { it.current }?.text }
    var text by remember(record.id) { mutableStateOf(kept) }
    var busy by remember(record.id) { mutableStateOf(false) }
    // One question per round: on opening when nothing current is kept, then on "Again".
    var round by remember(record.id) { mutableStateOf(if (kept == null) 1 else 0) }
    LaunchedEffect(record.id, round) {
        if (round == 0 || !connected) return@LaunchedEffect
        busy = true
        text = vm.review(record)
        busy = false
    }

    AppSheet(onDismiss) {
        SheetTitle(ReviewBrowse.labelOf(record), overline = t("Review"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when {
                !connected -> Text(
                    t("AI not connected. Set it up in Settings."),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted)
                busy -> Text(t("Thinking… a cold model can take a minute."),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted)
                else -> Text(text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(t("Close"), onDismiss, Modifier.weight(1f))
            if (connected && !busy && text != null) {
                PrimaryButton(t("Again"), { text = null; round++ },
                    Modifier.weight(1f), icon = Icons.Outlined.RateReview)
            }
        }
    }
}

/** A picked file's display name, for the item's title. */
private fun documentNameOf(context: android.content.Context, uri: android.net.Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: t("Document")
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

/** What the "+" offers that needs the phone itself: camera, gallery,
 *  microphone, files. Each ends in the same "Task or note?" sheet. */
private class CaptureActions(
    val photo: () -> Unit,
    val gallery: () -> Unit,
    val voice: () -> Unit,
    val file: () -> Unit,
)

/** Draws the sheets those captures need and hands back the ways to start one. */
@Composable
private fun rememberCaptureActions(vm: HubidubiTaskViewModel): CaptureActions {
    val context = LocalContext.current
    val c = HubidubiTaskTheme.colors
    var recording by remember { mutableStateOf(false) }
    val recorder = remember { mutableStateOf<AudioRecorder?>(null) }
    var pending by remember { mutableStateOf<PendingCapture?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null)
                pending = PendingCapture("image", t("Photo"), bytes, mime)
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
            pending = PendingCapture("image", t("Photo"), f.readBytes(), "image/jpeg")
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
            pending = PendingCapture("audio", t("Voice memo"), f.readBytes(), "audio/mp4")
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }

    // Ask before filing: the same photo is a task ("do something about this") or
    // a note ("keep this"), and only the user knows which. Its date is the one
    // Settings gives new tasks and notes.

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
                Text(p.text, style = MaterialTheme.typography.titleSmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(t("Note"), {
                    when (p.kind) {
                        "image", "document" -> vm.noteImage(p.bytes!!, p.mime, p.text)
                        "audio" -> vm.noteAudio(p.bytes!!, p.mime, p.text)
                        else -> vm.noteText(p.text)
                    }
                    pending = null
                }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Notes)
                PrimaryButton(t("Task"), {
                    when (p.kind) {
                        "image", "document" -> vm.captureImage(p.bytes!!, p.mime, p.text)
                        "audio" -> vm.captureAudio(p.bytes!!, p.mime, p.text)
                        else -> vm.capture(p.text)
                    }
                    pending = null
                }, Modifier.weight(1f), icon = Icons.Outlined.CheckBoxOutlineBlank)
            }
        }
    }

    if (recording) {
        AppSheet(onDismiss = { stopRecording() }) {
            SheetTitle(t("Recording…"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(t("Discard"), {
                    recorder.value?.stop()?.delete(); recorder.value = null; recording = false
                }, Modifier.weight(1f))
                PrimaryButton(t("Stop"), { stopRecording() }, Modifier.weight(1f),
                    icon = Icons.Outlined.Stop)
            }
        }
    }

    return CaptureActions(
        photo = { launchCamera() },
        gallery = {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        voice = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRecording()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        file = { pickDocument.launch("*/*") },
    )
}

/** A note written straight in: a title, and more below it if there is more.
 *  Also what the home screen's "New note" opens, outside the app. */
@Composable
internal fun NewNoteSheet(open: Boolean, onSave: (title: String, body: String) -> Unit, onDismiss: () -> Unit) {
    if (!open) return
    val c = HubidubiTaskTheme.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    // The cursor is in the note at once and Done saves it, so a long-pressed
    // + is a note in two taps.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun save() {
        if (title.isBlank()) return
        onSave(title.trim(), body.trim()); onDismiss()
    }
    AppSheet(onDismiss) {
        SheetTitle(t("A note"))
        OutlinedTextField(value = title, onValueChange = { title = it },
            label = { Text(t("Note")) }, singleLine = true, colors = appFieldColors(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(value = body, onValueChange = { body = it },
            label = { Text(t("More (optional)"), color = c.muted) }, minLines = 3,
            colors = appFieldColors(), shape = RectangleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        PrimaryButton(t("Save"), { save() },
            Modifier.fillMaxWidth().padding(top = 18.dp), enabled = title.isNotBlank())
    }
}

@Composable
fun NextScreen(state: HubidubiTaskViewModel.UiState, vm: HubidubiTaskViewModel) {
    var adding by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    NewMenu(adding, { adding = false }, state, vm)
    NewNoteSheet(noting, { title, body -> vm.noteText(title, body) }) { noting = false }
    val c = HubidubiTaskTheme.colors
    var menuFor by remember { mutableStateOf<EntityRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<EntityRecord?>(null) }
    var reviewing by remember { mutableStateOf<EntityRecord?>(null) }
    var detailFor by remember { mutableStateOf<EntityRecord?>(null) }
    // Today first: it is the question the tab is opened to answer. The wider
    // ranges are for planning, one tap away.
    var range by rememberSaveable { mutableStateOf(Agenda.Range.DAY) }
    val today = remember { todayIso() }
    // Events sit in the same days as tasks. They go first so, within a day,
    // what happens at a set time reads before what can happen any time.
    val agenda = remember(state.dated, state.events, today) {
        eventsAsDated(state.events, today) + state.dated
    }
    val view = remember(agenda, today, range) { Agenda.view(agenda, today, range) }
    val strip = remember(agenda, today) { Agenda.counts(agenda, today) }
    val projectTitle = remember(state.projects) { projectTitles(state.projects) }

    var addingNoteTo by remember { mutableStateOf<EntityRecord?>(null) }
    ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null }) { vm.delete(it) }
    ItemActionsMenu(
        record = menuFor, canMove = false, vm = vm,
        onMove = {}, onReview = { rec -> reviewing = rec },
        onDelete = { rec -> pendingDelete = rec }, onDismiss = { menuFor = null },
        onAddNote = { rec -> addingNoteTo = rec },
    )
    AddNoteSheet(addingNoteTo, vm) { addingNoteTo = null }
    val allItems = remember(state.nextActions, state.inbox, state.notes) {
        state.nextActions + state.inbox + state.notes
    }
    DetailSheet(detailFor, onReview = { rec -> reviewing = rec },
        review = detailFor?.let { state.reviews[it.id] },
        onDismiss = { detailFor = null },
        subtasks = detailFor?.let { ProjectTree.children(it.id, allItems) }.orEmpty(),
        onOpen = { detailFor = it },
        onDone = { vm.complete(it) },
        // The sheet keeps showing the edited copy: the lists refresh behind it.
        onEdit = { edited ->
            vm.editNote(edited.id, edited.fields["title"].orEmpty(), edited.fields["body"].orEmpty())
            detailFor = edited
        })
    AnalysisSheet(reviewing, state.inference.connected, vm) { reviewing = null }

    Column(Modifier.fillMaxSize()) {
        // Its title, the day (or the dates the range covers) under it, and the
        // + exactly where Projects has it: the same header, so it cannot drift.
        ScreenHeader(t("Incoming"), remember(range) {
            when (range) {
                Agenda.Range.DAY -> todayLong()
                else -> rangeSpan(today, range.days)
            }
        }) {
            PlusButton(t("New project or task"), onLongClick = { noting = true }) { adding = true }
        }
        Segmented(Agenda.Range.entries.map { it to t(it.label) }, range, { range = it },
            Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (range == Agenda.Range.WEEK) item(key = "strip") { WeekStrip(strip) }
            if (view.groups.isEmpty()) item(key = "empty") {
                Text(
                    if (state.dated.isEmpty())
                        t("Nothing dated. Long-press an item to give it a date.")
                    else when (range) {
                        Agenda.Range.DAY -> t("Nothing due today.")
                        Agenda.Range.WEEK -> t("Nothing due in the next seven days.")
                        Agenda.Range.MONTH -> t("Nothing due in the next thirty days.")
                    },
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                )
            }
            view.groups.forEach { group ->
                val overdue = group.tone == Agenda.Tone.OVERDUE
                // Overdue is the one heading that asks for something, so it alone
                // is brick; today is the accent, the rest recede.
                val tone = when (group.tone) {
                    Agenda.Tone.OVERDUE -> c.danger
                    Agenda.Tone.TODAY -> c.accent
                    Agenda.Tone.SOON -> c.frame
                    Agenda.Tone.LATER -> c.muted
                }
                // Inside one day the date says nothing new, so the row names the
                // project instead; across a week or a month the date is the point.
                val showDate = overdue || range == Agenda.Range.MONTH
                item(key = "g_${group.title}") {
                    SectionLabel(group.title, tone, group.items.size)
                    CardGroup(border = if (overdue) c.danger else c.line) {
                        group.items.forEachIndexed { i, rec ->
                            val event = rec.type == "event"
                            ListRow(
                                onClick = { detailFor = rec },
                                // An event's date is the calendar's, not something to
                                // re-date here.
                                onLongClick = if (event) null else ({ menuFor = rec }),
                                last = i == group.items.lastIndex,
                            ) {
                                // Name left, date hard right: the dates line up in a
                                // column you can scan without reading the names.
                                Text(ReviewBrowse.labelOf(rec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                if (event) {
                                    val time = rec.fields["time"] ?: t("All day")
                                    Text(if (showDate) "${shortDate(rec.fields["due"].orEmpty())} $time" else time,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.accent)
                                } else if (showDate) {
                                    Text(shortDate(rec.fields["due"].orEmpty()), style = MaterialTheme.typography.bodySmall,
                                        color = if (overdue) c.danger else c.muted)
                                } else {
                                    projectTitle(rec)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = c.muted, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(0.6f, fill = false))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Day hides tomorrow; saying how much is waiting keeps it from
            // being a surprise, and the tap is the way to see it.
            if (range == Agenda.Range.DAY && view.laterThisWeek > 0) item(key = "more") {
                Row(
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp).fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .border(1.dp, c.glyph)
                        .clickable(role = Role.Button) { range = Agenda.Range.WEEK }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = c.accent,
                        modifier = Modifier.size(18.dp))
                    Text(t("%d more this week", view.laterThisWeek),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        color = c.accent, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Seven days at a glance: a dot per item, today filled in. */
@Composable
private fun WeekStrip(days: List<Pair<java.time.LocalDate, Int>>) {
    val c = HubidubiTaskTheme.colors

    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth()
            .background(c.raised).border(1.dp, c.line).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        days.forEachIndexed { i, (date, count) ->
            val today = i == 0
            Column(
                Modifier.weight(1f).height(64.dp)
                    .background(if (today) c.button else Color.Transparent)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${I18n.weekdayShort(date)} ${date.dayOfMonth}, " +
                            I18n.count(count, "item")
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Text(I18n.weekdayShort(date).uppercase(), style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp, color = if (today) c.onButtonMuted else c.muted)
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium,
                    color = if (today) c.onButton else c.ink)
                Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(minOf(count, 3)) { Dot(if (today) c.onButton else c.accent, 5) }
                }
            }
        }
    }
}

/**
 * Calendar events as rows Next can group: dated by the local day they start,
 * named by their summary, with their start time. Past ones are left out — an
 * event that already happened isn't overdue, it's over.
 */
private fun eventsAsDated(events: List<EntityRecord>, today: String): List<EntityRecord> {
    val zone = java.time.ZoneId.systemDefault()
    val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    return events.mapNotNull { e ->
        val start = e.fields["dtstart"] ?: return@mapNotNull null
        val instant = com.hubidubitask.mobile.importer.Ics.instantOf(start)
        val (day, time) = if (instant != null) {
            val z = instant.atZone(zone); z.toLocalDate().toString() to z.format(hm)
        } else start.take(10) to null
        if (day < today) return@mapNotNull null
        e.copy(fields = e.fields + buildMap {
            put("due", day); put("text", e.fields["summary"] ?: ""); time?.let { put("time", it) }
        })
    }.sortedWith(compareBy({ it.fields["due"] }, { it.fields["time"] ?: "" }))
}

/** "26 Sep – 2 Oct": the stretch a range covers, under the title. */
private fun rangeSpan(today: String, days: Int): String {
    val start = java.time.LocalDate.parse(today)
    return I18n.span(start, start.plusDays(days - 1L))
}

/** The project an item is filed under, by name, or null when it is loose. */
private fun projectTitles(projects: List<EntityRecord>): (EntityRecord) -> String? {
    val byId = projects.associate { it.id to (it.fields["title"] ?: it.id) }
    val bySlug = projects.associate { ProjectTree.slugOf(it.fields["title"] ?: it.id) to it.id }
    return { rec -> ProjectTree.parentOf(rec, bySlug)?.let { byId[it] } }
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
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = HubidubiTaskTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty())
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            visualTransformation = if (secret)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            placeholder = placeholder?.let { { Text(it, color = c.muted) } },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            singleLine = true, enabled = enabled, colors = appFieldColors(),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth(),
        )
        if (helper.isNotEmpty())
            Text(helper, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean, last: Boolean = false) {
    val c = HubidubiTaskTheme.colors
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

/**
 * Pro, which is not sold yet: one button whose words say what it would be.
 * Pressing it signs this install up, counted once (core ProInterest); then a
 * line of thanks takes its place.
 */
@Composable
private fun ProSection(vm: HubidubiTaskViewModel, edge: Dp) {
    val c = HubidubiTaskTheme.colors
    val scope = rememberCoroutineScope()
    var wanted by remember { mutableStateOf(vm.proWanted()) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = edge, end = edge, top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wanted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = c.ok,
                    modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Text(t("Thanks, you're signed up for Pro. It gets built if enough people want it."),
                    style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        else {
            PrimaryButton(if (sending) t("Sending…") else t("Sign up for Pro: sync with your Mac and Linux computers"), {
                sending = true; failed = false
                scope.launch {
                    val ok = vm.wantPro()
                    sending = false
                    if (ok) wanted = true else failed = true
                }
            }, Modifier.fillMaxWidth(), enabled = !sending)
            if (failed)
                Text(t("That didn't go through. Try again when you're online."),
                    style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

@Composable
fun SettingsScreen(
    state: HubidubiTaskViewModel.UiState,
    vm: HubidubiTaskViewModel,
    onImport: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = HubidubiTaskTheme.colors

    // The agent is asked directly, so its address is this phone's own
    // setting. Everything is proposed, nothing demanded: the form arrives
    // filled in and Save works on arrival.
    val saved = remember { vm.agentConfig() }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var protocol by remember { mutableStateOf(saved.protocol) }
    // The default is filled in rather than hinted at: an empty box reads as
    // "you must supply this", which is exactly what it is not.
    val defaultModel = com.hubidubitask.core.agent.AgentConfig.defaultModel(protocol)
    // Empty unless one of the server's own was chosen: the default shows as
    // the placeholder, and an empty field means the default.
    var model by remember {
        mutableStateOf(saved.model.takeIf { it != com.hubidubitask.core.agent.AgentConfig.defaultModel(saved.protocol) }.orEmpty())
    }
    // Switching protocol swaps the default, unless the user typed their own.
    LaunchedEffect(protocol) {
        if (model in setOf("fast", "local", com.hubidubitask.core.agent.AgentConfig.CLAUDE_MODEL)) model = ""
    }
    // Claude's key is kept apart from the other agents' settings, in the
    // system's secret store.
    var claudeKey by remember { mutableStateOf(vm.claudeKey().orEmpty()) }
    val claude = protocol == "claude"
    val selfhosted = protocol == "selfhostedai"
    // The user's own agent on a computer at home: its IP, port and token.
    val harness = protocol == "harness"
    val (savedIp, savedPort) = remember { com.hubidubitask.core.agent.AgentLink.split(vm.harnessEndpoint()) }
    var harnessIp by remember { mutableStateOf(savedIp) }
    var harnessPort by remember { mutableStateOf(savedPort) }
    var harnessToken by remember { mutableStateOf(vm.harnessToken().orEmpty()) }
    // Set by the first change made here; what was last kept, so it is not kept twice.
    var touched by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf<List<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Whether the last check found the AI answering; the message says it in words.
    var connected by remember { mutableStateOf(false) }

    // One edge for everything here: the cards, the fields, the buttons and the
    // choices all start and end on the line the headings start on.
    val edge = 20.dp

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader(t("Settings"))
        // The date every new task and note gets: tomorrow brings it back on
        // Incoming the next day.
        SectionLabel(t("Due date for new tasks and notes"))
        val newDue by vm.newDue.collectAsStateWithLifecycle()
        Dropdown(t(NewItemDue.OPTIONS.first { it.first == newDue }.second),
            NewItemDue.OPTIONS.map { (id, label) -> DropdownItem(t(label), id == newDue) { vm.setNewDue(id) } },
            Modifier.padding(horizontal = edge))
        // Which AI answers reviews, and only what that one needs to be reached.
        SectionLabel(t("AI Agent"))

        Column(Modifier.padding(horizontal = edge), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Dropdown(t(PROTOCOLS.first { it.first == protocol }.second),
                PROTOCOLS.map { (id, label) -> DropdownItem(t(label), id == protocol) { protocol = id; message = null; touched = true } })

            if (claude)
                LabeledField("", claudeKey, { claudeKey = it; message = null; touched = true },
                    helper = "", enabled = true, placeholder = "sk-ant-…", secret = true)
            else if (harness) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("", harnessIp, { harnessIp = it; message = null; touched = true },
                        helper = "", enabled = true, placeholder = t("IP address"),
                        modifier = Modifier.weight(2f))
                    LabeledField("", harnessPort, { harnessPort = it.filter(Char::isDigit); message = null; touched = true },
                        helper = "", enabled = true, placeholder = t("Port"),
                        modifier = Modifier.weight(1f))
                }
                LabeledField("", harnessToken, { harnessToken = it; message = null; touched = true },
                    helper = "", enabled = true, placeholder = t("Token or password"), secret = true)
            }
            else
                LabeledField("", endpoint, { endpoint = it; message = null; touched = true },
                    helper = "",
                    enabled = true, placeholder = t("Server address"))

            // Only SelfHostedAI is told which of its models to use; the others choose
            // their own (Claude the app's, an OpenAI-shaped server its first).
            if (selfhosted)
                LabeledField("", model, { model = it; message = null; touched = true },
                    helper = "",
                    enabled = true, placeholder = "${t("Model")}: $defaultModel")

            // No Save: a change is kept on its own a moment after the last
            // keystroke or choice, then checked, and the line under says what
            // was found. Nothing is kept before anything was changed here.
            LaunchedEffect(protocol, endpoint, model, claudeKey, harnessIp, harnessPort, harnessToken) {
                val inputs = listOf(protocol, endpoint, model, claudeKey, harnessIp, harnessPort, harnessToken)
                if (!touched || inputs == kept) return@LaunchedEffect
                delay(800)
                kept = inputs
                message = null
                if (harness) {
                    if (harnessIp.isBlank()) return@LaunchedEffect
                    // Which agent listens there is found, not asked: the address is enough.
                    message = t("Saved — checking…")
                    val found = vm.plugAgent(harnessIp, harnessPort, harnessToken)
                    val h = vm.checkInference()
                    message = when (found) {
                        is com.hubidubitask.core.agent.AgentProbe.Result.Found -> {
                            harnessPort = com.hubidubitask.core.agent.AgentLink.split(found.endpoint).second
                            kept = listOf(protocol, endpoint, model, claudeKey, harnessIp, harnessPort, harnessToken)
                            if (h.connected) t("Connected to %s", found.label)
                            else t("Saved, but the agent at %s did not answer: %s", found.label,
                                h.error ?: t("no reply"))
                        }
                        is com.hubidubitask.core.agent.AgentProbe.Result.NeedsToken ->
                            if (found.refused) t("%s refused the token", found.label)
                            else t("%s needs its token", found.label)
                        com.hubidubitask.core.agent.AgentProbe.Result.NotFound ->
                            t("Saved, but the agent at %s did not answer: %s", harnessIp.trim(), t("no reply"))
                    }
                    connected = found is com.hubidubitask.core.agent.AgentProbe.Result.Found && h.connected
                    return@LaunchedEffect
                }
                vm.setAgentConfig(saved.copy(
                    endpoint = endpoint.trim(), protocol = protocol,
                    model = if (selfhosted) model.ifBlank { defaultModel } else defaultModel,
                    apiKey = if (claude) claudeKey.trim() else null))
                // A choice with nothing to reach it by yet is kept, not checked.
                if ((claude && claudeKey.isBlank()) || (!claude && endpoint.isBlank())) return@LaunchedEffect
                // Saving is not the same as working. An address nothing answers
                // on looks identical to a good one until a question fails, so
                // probe it now and say plainly which happened.
                message = t("Saved — checking…")
                val h = vm.checkInference()
                message = if (h.connected) t("Connected to %s", h.server ?: endpoint)
                    else if (claude) t("Saved, but Claude did not answer: %s", h.error ?: t("no reply"))
                    else t("Saved, but nothing answered at %s — check the address and that the server is running there.", endpoint)
                connected = h.connected
            }

            // What the last Save found, and nothing before: an address under the
            // fields only repeated one, or named a choice not made yet.
            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(if (connected) c.ok else c.muted)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        // The computers (Mac or Linux) this phone keeps in step with, every
        // minute: a list of them, and New device in it to pair another.
        var pairingMac by remember { mutableStateOf(false) }
        PairMacSheet(pairingMac, vm) { pairingMac = false }
        SectionLabel(t("Paired devices"))
        val macs = state.macs
        // The one chosen in the list: Sync now and Unpair under it.
        var chosenMac by remember { mutableStateOf<String?>(null) }
        val mac = macs.firstOrNull { it.id == chosenMac } ?: macs.firstOrNull()
        Column(Modifier.padding(horizontal = edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(mac?.name ?: t("Not paired"),
                macs.map { m ->
                    DropdownItem(m.name, m.id == mac?.id, detail = syncedAt(m.lastSync)) { chosenMac = m.id }
                } + DropdownItem(t("New device")) { pairingMac = true })
            if (mac != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(if (state.syncing) t("Syncing…") else t("Sync now"), { vm.syncMacNow() },
                        Modifier.weight(1f), icon = Icons.Outlined.Sync, enabled = !state.syncing)
                    SecondaryButton(t("Unpair"), { chosenMac = null; vm.unpairMac(mac.id) }, Modifier.weight(1f))
                }
            }
        }
        // The app's own language, each named in itself.
        SectionLabel(t("Language"))
        val language by vm.language.collectAsStateWithLifecycle()
        Dropdown(language.label, Language.entries.map { DropdownItem(it.label, it == language) { vm.setLanguage(it) } },
            Modifier.padding(horizontal = edge))
        // The colours: Sand follows the system's light or dark, the others are
        // always one of them, said on the right of each in the list.
        SectionLabel(t("Color theme"))
        val theme by vm.theme.collectAsStateWithLifecycle()
        Dropdown(t(theme.label), ColorTheme.entries.map {
            DropdownItem(t(it.label), it == theme, detail = t(it.mode)) { vm.setTheme(it) }
        }, Modifier.padding(horizontal = edge))
        // Kept quiet at the very end: things done once in a while, not settings
        // anyone looks for day to day. Export first: a copy of everything as one
        // file sealed with a password, the way to keep it outside the app;
        // Import reads it back, among the other places it reads from.
        var exported by remember { mutableStateOf<String?>(null) }
        var askingPassword by remember { mutableStateOf(false) }
        // Held only between the password sheet and the file picker.
        var exportPassword by remember { mutableStateOf("") }
        val export = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val password = exportPassword.toCharArray()
            exportPassword = ""
            if (uri != null) scope.launch {
                exported = t("Exporting…")
                exported = runCatching { vm.exportTo(uri, password) }.fold(
                    { (items, media) -> t("Exported %d items and %d attachments.", items, media) },
                    { t("The export failed: %s", it.message ?: it.toString()) })
                password.fill(' ')
            }
        }
        BackupPasswordSheet(askingPassword, { askingPassword = false }) {
            exportPassword = it; askingPassword = false
            export.launch(Backup.fileName())
        }
        SecondaryButton(t("Export"), { exported = null; askingPassword = true },
            Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 28.dp),
            icon = Icons.Outlined.FileUpload)
        exported?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(start = edge, end = edge, top = 8.dp))
        }
        SecondaryButton(t("Import"), onImport,
            Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 10.dp),
            icon = Icons.Outlined.FileDownload)
        // Last: what is not here yet.
        ProSection(vm, edge)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The password a backup is sealed with, asked twice before Export saves it.
 * It is the only way back into the file, so the one line under the fields says
 * so, and turns into what is wrong while it is.
 */
@Composable
private fun BackupPasswordSheet(open: Boolean, onDismiss: () -> Unit, onExport: (String) -> Unit) {
    if (!open) return
    val c = HubidubiTaskTheme.colors
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val problem = when {
        password.isNotEmpty() && password.length < MIN_BACKUP_PASSWORD -> t("At least 8 characters")
        again.isNotEmpty() && again != password -> t("The passwords differ.")
        else -> null
    }
    val ok = password.length >= MIN_BACKUP_PASSWORD && again == password
    AppSheet(onDismiss) {
        SheetTitle(t("Export"))
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text(t("Password")) }, singleLine = true, colors = appFieldColors(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = ImeAction.Next),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(value = again, onValueChange = { again = it },
            label = { Text(t("Same password again")) }, singleLine = true, colors = appFieldColors(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ok) onExport(password) }),
            shape = RectangleShape, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Text(problem ?: t("Without it, the backup can't be opened."),
            style = MaterialTheme.typography.bodySmall, color = if (problem != null) c.danger else c.muted,
            modifier = Modifier.padding(top = 10.dp))
        PrimaryButton(t("Export"), { onExport(password) },
            Modifier.fillMaxWidth().padding(top = 18.dp), enabled = ok)
    }
}

private const val MIN_BACKUP_PASSWORD = 8

/**
 * What the + starts, the same on Next and Projects: what it is (a project, a
 * task, a note, a picture, a voice memo, a file), then where it goes.
 */
@Composable
fun NewMenu(
    open: Boolean,
    onClose: () -> Unit,
    state: HubidubiTaskViewModel.UiState,
    vm: HubidubiTaskViewModel,
    onTaskAdded: (EntityRecord) -> Unit = {},
) {
    val c = HubidubiTaskTheme.colors
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var pickingProjectForTask by remember { mutableStateOf(false) }
    var addingLooseTask by remember { mutableStateOf(false) }
    var addingTaskTo by remember { mutableStateOf<EntityRecord?>(null) }
    var writingNote by remember { mutableStateOf(false) }
    val capture = rememberCaptureActions(vm)
    val projects = remember(state.projects, state.nextActions, state.notes, state.inbox) {
        ProjectTree.build(ProjectTree.records(state.projects, state.nextActions, state.notes, state.inbox))
            .filter { it.isProject }
    }
    AddTaskSheet(addingTaskTo, vm, onAdded = onTaskAdded) { addingTaskTo = null }
    AddTaskSheet(null, vm, onAdded = {}, loose = addingLooseTask) { addingLooseTask = false }
    NewNoteSheet(writingNote, { title, body -> vm.noteText(title, body) }) { writingNote = false }

    if (open) {
        AppSheet(onDismiss = onClose) {
            SheetTitle(t("New"))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ActionRow(Icons.Outlined.Folder, t("A project"), color = c.accent) {
                onClose(); creating = true
            }
            ActionRow(Icons.Outlined.CheckBoxOutlineBlank, t("A task"), color = c.accent) {
                onClose(); pickingProjectForTask = true
            }
            ActionRow(Icons.AutoMirrored.Filled.Notes, t("A note"), color = c.accent) {
                onClose(); writingNote = true
            }
            ActionRow(Icons.Outlined.CameraAlt, t("A photo"), color = c.accent) {
                onClose(); capture.photo()
            }
            ActionRow(Icons.Outlined.PhotoLibrary, t("From the gallery"), color = c.accent) {
                onClose(); capture.gallery()
            }
            ActionRow(Icons.Outlined.Mic, t("A voice memo"), color = c.accent) {
                onClose(); capture.voice()
            }
            ActionRow(Icons.Outlined.Description, t("A file"), color = c.accent, last = true) {
                onClose(); capture.file()
            }
            }
            SecondaryButton(t("Cancel"), onClose,
                Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }

    if (pickingProjectForTask) {
        AppSheet(onDismiss = { pickingProjectForTask = false }) {
            SheetTitle(t("Which project?"))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ActionRow(Icons.Outlined.Inbox, t("No project"), color = c.accent,
                    last = projects.isEmpty()) {
                    pickingProjectForTask = false; addingLooseTask = true
                }
                projects.forEachIndexed { i, p ->
                    ActionRow(Icons.Outlined.Folder, p.title, color = c.accent,
                        last = i == projects.lastIndex) {
                        pickingProjectForTask = false; addingTaskTo = p.record
                    }
                }
            }
            SecondaryButton(t("Cancel"), { pickingProjectForTask = false },
                Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; newTitle = "" },
            containerColor = c.paper,
            title = { Text(t("New project"), style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    label = { Text(t("Project name")) }, singleLine = true,
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                PrimaryButton(t("Create"),
                    { vm.createProject(newTitle); creating = false; newTitle = "" },
                    enabled = newTitle.isNotBlank())
            },
            dismissButton = {
                TextButton(onClick = { creating = false; newTitle = "" }) {
                    Text(t("Cancel"), color = c.ink, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

}

/**
 * The + at the top right of a tab, in the same place on each. Its 48dp tap
 * target reports only the title's line height, so it never makes a header
 * taller than the other tabs' or pushes what is beside it down. A long press
 * skips the menu and opens a note ready to type: the fast track for a thought.
 */
@Composable
fun PlusButton(description: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = HubidubiTaskTheme.colors
    Box(
        modifier = Modifier.layout { m, cs ->
            val p = m.measure(cs.copy(minHeight = 0))
            val h = 34.sp.roundToPx()
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        }.size(48.dp)
            // The ripple an IconButton has; an IconButton takes no long press.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                role = Role.Button,
                onLongClickLabel = onLongClick?.let { t("A note") },
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = description, tint = c.accent,
            modifier = Modifier.size(30.dp))
    }
}

@Composable
fun ProjectsScreen(state: HubidubiTaskViewModel.UiState, vm: HubidubiTaskViewModel) {
    val c = HubidubiTaskTheme.colors
    // A project is a flat container holding its items — notes and tasks alike.
    // The shape and move rules live in ProjectTree so they are testable without
    // driving the UI.
    val records = remember(state.projects, state.nextActions, state.notes, state.inbox) {
        ProjectTree.records(state.projects, state.nextActions, state.notes, state.inbox)
    }
    // Every project starts closed: the view opens as a list of projects, and a
    // tap opens the one being looked for. Only the ones opened are remembered,
    // so a project that arrives later (a sync, a new one) is closed too.
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf<ProjectTree.Node?>(null) }
    var pendingDelete by remember { mutableStateOf<EntityRecord?>(null) }
    var menuFor by remember { mutableStateOf<EntityRecord?>(null) }
    var reviewing by remember { mutableStateOf<EntityRecord?>(null) }
    var detailFor by remember { mutableStateOf<EntityRecord?>(null) }
    val nodes = remember(records, expanded) {
        val closed = records.filter { it.type == "project" }.mapTo(HashSet()) { it.id } - expanded
        ProjectTree.build(records, closed)
    }
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
    // How many sub-tasks each item holds: shown as a count, opened on tap.
    val subCount = remember(records) {
        records.mapNotNull { it.fields["parent"] }.groupingBy { it }.eachCount()
    }

    var addingNoteTo by remember { mutableStateOf<EntityRecord?>(null) }
    var addingTaskTo by remember { mutableStateOf<EntityRecord?>(null) }
    var renaming by remember { mutableStateOf<EntityRecord?>(null) }
    // The + asks what is wanted first; its menu is shared with Next. A long
    // press skips the asking: a note, ready to type.
    var adding by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    // Open the project a new task went into, so it is seen landing.
    NewMenu(adding, { adding = false }, state, vm, onTaskAdded = { expanded = expanded + it.id })
    NewNoteSheet(noting, { title, body -> vm.noteText(title, body) }) { noting = false }
    ConfirmDelete(pendingDelete, onDismiss = { pendingDelete = null }) { vm.delete(it) }
    ItemActionsMenu(
        record = menuFor, canMove = menuFor?.type != "project", vm = vm,
        onMove = { rec -> moving = nodes.firstOrNull { it.id == rec.id } },
        onReview = { rec -> reviewing = rec },
        onDelete = { rec -> pendingDelete = rec },
        onDismiss = { menuFor = null },
        onAddNote = { rec -> addingNoteTo = rec },
        onAddTask = { rec -> addingTaskTo = rec },
        onRename = { rec -> renaming = rec },
    )
    AddNoteSheet(addingNoteTo, vm) { addingNoteTo = null }
    // Open the project the task went into, so the new task is seen landing.
    AddTaskSheet(addingTaskTo, vm, onAdded = { expanded = expanded + it.id }) { addingTaskTo = null }
    RenameProjectDialog(renaming, vm) { renaming = null }
    val allItems = remember(state.nextActions, state.inbox, state.notes) {
        state.nextActions + state.inbox + state.notes
    }
    DetailSheet(detailFor, onReview = { rec -> reviewing = rec },
        review = detailFor?.let { state.reviews[it.id] },
        onDismiss = { detailFor = null },
        subtasks = detailFor?.let { ProjectTree.children(it.id, allItems) }.orEmpty(),
        onOpen = { detailFor = it },
        onDone = { vm.complete(it) },
        // The sheet keeps showing the edited copy: the lists refresh behind it.
        onEdit = { edited ->
            vm.editNote(edited.id, edited.fields["title"].orEmpty(), edited.fields["body"].orEmpty())
            detailFor = edited
        })
    AnalysisSheet(reviewing, state.inference.connected, vm) { reviewing = null }

    moving?.let { node ->
        val targets = remember(node.id, records) { ProjectTree.moveTargets(node.id, records) }
        AppSheet(onDismiss = { moving = null }) {
            SheetTitle(node.title, overline = t("Move to"))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val current = node.record.fields["parent"]
                targets.forEachIndexed { i, (id, label) ->
                    val here = id == current || (id == null && current.isNullOrBlank())
                    ActionRow(Icons.Outlined.Folder, label, meta = if (here) t("here") else null,
                        color = c.accent, enabled = !here, last = i == targets.lastIndex) {
                        vm.move(node.id, id); moving = null
                    }
                }
            }
            SecondaryButton(t("Cancel"), { moving = null }, Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("Projects")) {
            PlusButton(t("New project or task"), onLongClick = { noting = true }) { adding = true }
        }
        if (nodes.isEmpty()) {
            Text(t("No projects yet. Tap + to start one, then file notes and tasks into it."),
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
                            expanded = project.id in expanded,
                            onToggle = {
                                if (project.hasChildren) {
                                    expanded = if (project.id in expanded)
                                        expanded - project.id else expanded + project.id
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
                                SubtaskTag(subCount[node.id] ?: 0)
                            }
                        }
                    }
                }
            }
            if (free.isNotEmpty()) {
                // The loose items are their own group, named, so the first one
                // doesn't read as the last project's.
                item(key = "free") {
                    SectionLabel(t("Not in a project"), count = free.size,
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
                                SubtaskTag(subCount[node.id] ?: 0)
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
    val c = HubidubiTaskTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongClick,
                    onClickLabel = if (expanded) t("Collapse") else t("Expand"))
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
            Text(count.toString(), style = MaterialTheme.typography.labelMedium,
                color = c.muted)
        }
        if (expanded && node.hasChildren) HorizontalDivider(color = c.line, thickness = 1.dp)
    }
}
