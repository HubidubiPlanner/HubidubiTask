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
import com.hubidubitask.mobile.importer.Todoist
import kotlinx.coroutines.launch

private enum class Source { TODOIST, GOOGLE }

/**
 * One-time import from another app, reached from the bottom of Settings.
 *
 * Choose a source, type what it needs, see what would come in, confirm.
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
    var source by remember { mutableStateOf<Source?>(null) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var todo by remember { mutableStateOf<Todoist.Plan?>(null) }
    var cal by remember { mutableStateOf<HubidubiTaskViewModel.CalendarPlan?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    fun reset() {
        source = null; secret = ""; error = null; todo = null; cal = null; busy = false
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        IconButton(onClick = { if (source != null && done == null) reset() else onBack() },
            modifier = Modifier.padding(start = 8.dp, top = 8.dp).size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
        }
        ScreenHeader(
            when (source) { null -> "Import"; Source.TODOIST -> "Todoist"; Source.GOOGLE -> "Google Calendar" },
            "One time. Nothing is kept afterwards — not the token, not the address.",
        )

        val body = Modifier.padding(horizontal = 20.dp)
        when {
            done != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(done!!, style = MaterialTheme.typography.bodyLarge)
                PrimaryButton("Done", onBack, Modifier.fillMaxWidth())
            }

            source == null -> {
                SectionLabel("Import from", modifier = Modifier.padding(top = 0.dp))
                CardGroup {
                    SourceRow(Icons.Outlined.TaskAlt, "Todoist", "Projects and open tasks") {
                        source = Source.TODOIST
                    }
                    SourceRow(Icons.Outlined.CalendarMonth, "Google Calendar",
                        "Events from the next 12 months", last = true) { source = Source.GOOGLE }
                }
            }

            todo != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val p = todo!!
                Summary(buildList {
                    add(Line("${p.newProjects.size} new projects" +
                        if (p.joinedProjects > 0) ", ${p.joinedProjects} existing ones get new tasks" else ""))
                    add(Line("${p.tasks.size - p.subtasks} tasks" + if (p.dated > 0) ", ${p.dated} with a date" else ""))
                    if (p.subtasks > 0) add(Line("${p.subtasks} of them are sub-tasks — they come in as notes under their task"))
                    if (p.withDescription > 0) add(Line("${p.withDescription} with a description"))
                    if (p.alreadyHere > 0) add(Line("${p.alreadyHere} already in the app — skipped", true))
                }, buildList {
                    if (p.recurring > 0) add("${p.recurring} repeats (the next date is kept)")
                    if (p.withLabels > 0) add("${p.withLabels} tasks' labels")
                    if (p.inSections > 0) add("${p.inSections} tasks' sections")
                })
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                Confirm(busy, p.tasks.isEmpty() && p.newProjects.isEmpty(), onCancel = ::reset) {
                    busy = true
                    scope.launch {
                        val n = runCatching { vm.importTodoist(p) }
                        busy = false
                        n.onSuccess {
                            done = "Imported ${it - p.subtasks} tasks, ${p.subtasks} notes and " +
                                "${p.newProjects.size} projects. " +
                                "They reach your phone on the next sync."
                        }.onFailure { error = it.message ?: "The import failed." }
                    }
                }
            }

            cal != null -> Column(body, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val p = cal!!
                Summary(buildList {
                    add(Line("${p.events.size} events in the next 12 months"))
                    if (p.alreadyHere > 0) add(Line("${p.alreadyHere} already in the app — skipped", true))
                }, buildList {
                    if (p.unsupportedRules > 0)
                        add("${p.unsupportedRules} repeating events use a rule this can't expand — only their first date comes in")
                })
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                Confirm(busy, p.events.isEmpty(), onCancel = ::reset) {
                    busy = true
                    scope.launch {
                        val n = runCatching { vm.importCalendar(p) }
                        busy = false
                        n.onSuccess {
                            done = "Imported $it events. They show in Next, and reach your " +
                                "phone on the next sync."
                        }.onFailure { error = it.message ?: "The import failed." }
                    }
                }
            }

            else -> Column(body, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val todoist = source == Source.TODOIST
                Text(
                    if (todoist) "API token" else "Secret address in iCal format",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it; error = null },
                    singleLine = true, enabled = !busy,
                    visualTransformation = if (todoist) PasswordVisualTransformation()
                                           else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (todoist) KeyboardType.Password else KeyboardType.Uri,
                        autoCorrectEnabled = false),
                    colors = appFieldColors(), shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (todoist) "In Todoist: Settings → Integrations → Developer → API token."
                    else "In Google Calendar on the web: Settings → your calendar → Integrate " +
                        "calendar → Secret address in iCal format. Treat it like a password.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger)
                }
                PrimaryButton(if (busy) "Reading…" else "Continue", {
                    busy = true; error = null
                    scope.launch {
                        val res = runCatching {
                            if (todoist) todo = vm.planTodoist(secret)
                            else cal = vm.planCalendar(secret)
                        }
                        busy = false
                        res.onSuccess { secret = "" }   // read once; not needed any more
                        res.onFailure {
                            error = when (it) {
                                is Todoist.TokenRejected -> "Todoist didn't accept that token."
                                is java.net.UnknownHostException -> "No internet connection."
                                else -> it.message ?: "Couldn't read it."
                            }
                        }
                    }
                }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy && secret.isNotBlank())
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
            Text("NOT CARRIED OVER", style = MaterialTheme.typography.labelSmall, color = c.muted,
                modifier = Modifier.padding(top = 8.dp))
            lost.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
    }
}

@Composable
private fun Confirm(busy: Boolean, nothing: Boolean, onCancel: () -> Unit, onImport: () -> Unit) {
    if (nothing) {
        Text("Nothing new to import.", style = MaterialTheme.typography.bodyMedium,
            color = HubidubiTaskTheme.colors.muted)
        SecondaryButton("Back", onCancel, Modifier.fillMaxWidth())
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("Cancel", onCancel, Modifier.weight(1f), enabled = !busy)
        PrimaryButton(if (busy) "Importing…" else "Import", onImport, Modifier.weight(1f),
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
