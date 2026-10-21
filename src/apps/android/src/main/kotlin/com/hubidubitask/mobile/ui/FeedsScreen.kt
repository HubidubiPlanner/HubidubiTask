package com.hubidubitask.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.feed.FeedIcon
import com.hubidubitask.core.feed.FeedKind
import com.hubidubitask.core.feed.FeedKinds
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.core.i18n.t
import kotlinx.coroutines.launch

/**
 * Feeds: pages the user watches. Each is looked at once a day, week or month,
 * and what changed on it becomes one task. The rules live in core's Feeds.
 */
@Composable
fun FeedsScreen(state: HubidubiTaskViewModel.UiState, vm: HubidubiTaskViewModel) {
    val c = HubidubiTaskTheme.colors
    var choosingKind by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<FeedKind?>(null) }
    var open by remember { mutableStateOf<EntityRecord?>(null) }
    val projectTitle = remember(state.projects) {
        state.projects.associate { it.id to (it.fields["title"] ?: it.id) }
    }

    if (choosingKind) {
        AppSheet(onDismiss = { choosingKind = false }) {
            SheetTitle(t("New feed"))
            FeedKinds.all.forEachIndexed { i, kind ->
                ActionRow(kind.icon.vector(), kind.label, color = c.accent,
                    last = i == FeedKinds.all.lastIndex) {
                    choosingKind = false
                    adding = kind
                }
            }
            SecondaryButton(t("Cancel"), { choosingKind = false },
                Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }
    adding?.let { kind -> NewFeedSheet(kind, state, vm) { adding = null } }
    open?.let { f ->
        FeedSheet(state.feeds.firstOrNull { it.id == f.id } ?: f, projectTitle, vm) { open = null }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t("Feeds")) {
            IconButton(
                onClick = { choosingKind = true },
                // Same as Projects: the 48dp target is not allowed to make the
                // header taller than the other tabs'.
                modifier = Modifier.layout { m, cs ->
                    val p = m.measure(cs.copy(minHeight = 0))
                    val h = 34.sp.roundToPx()
                    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
                }.size(48.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = t("New feed"), tint = c.accent,
                    modifier = Modifier.size(30.dp))
            }
        }
        if (state.feeds.isEmpty()) {
            Text(t("No feeds yet."), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(horizontal = 20.dp))
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CardGroup {
                state.feeds.forEachIndexed { i, f ->
                    ListRow(onClick = { open = f }, last = i == state.feeds.lastIndex) {
                        Column(Modifier.weight(1f)) {
                            Text(Feeds.label(f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(summary(f, projectTitle), style = MaterialTheme.typography.bodySmall,
                                color = if (f.fields["last_error"] != null) c.danger else c.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun periodLabel(p: String?) = t(Feeds.PERIODS.firstOrNull { it.first == p }?.second ?: "Day")

/** "Week · Trip to Lisbon · changed 6 Oct": how often, where its task goes, what it last saw. */
private fun summary(f: EntityRecord, projectTitle: Map<String, String>): String {
    val where = f.fields["project"]?.let { projectTitle[it] } ?: t("Inbox")
    val last = when {
        f.fields["last_error"] != null -> t("could not check")
        f.fields["last_changed"] != null -> t("changed %s", shortDate(f.fields["last_changed"]!!))
        f.fields["last_checked"] != null -> t("watching")
        else -> t("not checked yet")
    }
    return "${periodLabel(f.fields["period"])} · $where · $last"
}

/** Adds a feed of [kind]: what it asks for, how often, and where its task goes. */
@Composable
private fun NewFeedSheet(kind: FeedKind, state: HubidubiTaskViewModel.UiState, vm: HubidubiTaskViewModel,
                         onDismiss: () -> Unit) {
    val c = HubidubiTaskTheme.colors
    var values by remember(kind.id) { mutableStateOf(mapOf<String, String>()) }
    var period by remember { mutableStateOf("week") }
    var project by remember { mutableStateOf<EntityRecord?>(null) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        AppSheet(onDismiss = { picking = false }) {
            SheetTitle(t("Which project?"))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ActionRow(Icons.Outlined.Inbox, t("No project"), color = c.accent,
                    last = state.projects.isEmpty()) { project = null; picking = false }
                state.projects.sortedBy { (it.fields["title"] ?: it.id).lowercase() }
                    .forEachIndexed { i, p ->
                        ActionRow(Icons.Outlined.Folder, p.fields["title"] ?: p.id, color = c.accent,
                            last = i == state.projects.lastIndex) { project = p; picking = false }
                    }
            }
        }
        return
    }

    AppSheet(onDismiss) {
        SheetTitle(kind.label)
        Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            kind.fields.forEach { field ->
                OutlinedTextField(value = values[field.key].orEmpty(),
                    onValueChange = { values = values + (field.key to it) },
                    label = { if (field.optional) Text(field.label, color = c.muted) else Text(field.label) },
                    singleLine = true,
                keyboardOptions = if (field.address) KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions.Default,
                    colors = appFieldColors(), shape = RectangleShape, modifier = Modifier.fillMaxWidth())
            }
            Segmented(Feeds.PERIODS.map { (id, label) -> id to t(label) }, period, { period = it })
            ActionRow(Icons.Outlined.Folder, project?.fields?.get("title") ?: t("No project"),
                color = c.accent, last = true) { picking = true }
        }
        PrimaryButton(t("Watch"), {
            vm.addFeed(kind, values, period, project?.id); onDismiss()
        }, Modifier.fillMaxWidth().padding(top = 18.dp), enabled = kind.valid(values))
    }
}

@Composable
private fun FeedSheet(feed: EntityRecord, projectTitle: Map<String, String>, vm: HubidubiTaskViewModel,
                      onDismiss: () -> Unit) {
    val c = HubidubiTaskTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember(feed.id) { mutableStateOf(false) }
    var result by remember(feed.id) { mutableStateOf<String?>(null) }
    val f = feed.fields
    AppSheet(onDismiss) {
        SheetTitle(Feeds.label(feed))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f["url"].orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val model = t(AgentConfig.FEED_MODELS.firstOrNull { it.first == f["model"] }?.second ?: "Claude")
            Text("${periodLabel(f["period"])} · ${f["project"]?.let { projectTitle[it] } ?: t("Inbox")} · $model",
                style = MaterialTheme.typography.bodyMedium)
            (result ?: f["last_error"])?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = if (result == null) c.danger else c.ink)
            }
        }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(t("Delete"), { vm.deleteFeed(feed.id); onDismiss() }, Modifier.weight(1f),
                icon = Icons.Outlined.DeleteOutline)
            PrimaryButton(if (busy) t("Checking…") else t("Check now"), {
                busy = true; result = null
                scope.launch { result = vm.checkFeed(feed.id); busy = false }
            }, Modifier.weight(1f), icon = Icons.Outlined.Sync, enabled = !busy)
        }
    }
}

/** How this app draws a feed kind's icon. */
private fun FeedIcon.vector(): ImageVector = when (this) {
    FeedIcon.WEB -> Icons.Outlined.Language
    FeedIcon.NEWS -> Icons.Outlined.RssFeed
}
