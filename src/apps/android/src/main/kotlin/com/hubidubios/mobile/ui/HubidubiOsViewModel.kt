package com.hubidubios.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubidubios.core.EntityRecord
import com.hubidubios.core.agent.Agent
import com.hubidubios.core.agent.AgentConfig
import com.hubidubios.core.agent.Health
import com.hubidubios.core.agent.ModelClient
import com.hubidubios.mobile.net.AgentSettings
import com.hubidubios.mobile.net.PairedServer
import com.hubidubios.mobile.sync.Sync
import com.hubidubios.mobile.importer.Ics
import com.hubidubios.mobile.importer.Todoist
import com.hubidubios.mobile.importer.fetchCalendarText
import com.hubidubios.mobile.sync.eventId
import com.hubidubios.mobile.tree.ProjectTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one UI state holder for the native app. Reads come from the local mirror
 * (work offline); writes queue to the durable outbox. Sync + inference status
 * drive the two native status chips.
 */
class HubidubiOsViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val paired: Boolean = false,
        val syncing: Boolean = false,
        val inference: Health = Health(false, null, emptyList()),
        val inbox: List<EntityRecord> = emptyList(),
        val nextActions: List<EntityRecord> = emptyList(),
        val staleProjects: List<EntityRecord> = emptyList(),
        val notes: List<EntityRecord> = emptyList(),
        val projects: List<EntityRecord> = emptyList(),
        /** Everything carrying a date, soonest first — what Next shows. */
        val dated: List<EntityRecord> = emptyList(),
        /** Calendar events (imported or from the workstation), for Next. */
        val events: List<EntityRecord> = emptyList(),
        /** The paired Mac, if any. */
        val mac: com.hubidubios.mobile.net.MacLink.Mac? = null,
        /** Host of the paired workstation, to propose a default endpoint. */
        val workstationHost: String? = null,
        /** Watched pages, each turned into at most one task per period. */
        val feeds: List<EntityRecord> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // The Mac gets this phone's changes, and sends its own, every minute
        // while the app is open. (In the background Android allows far less;
        // the periodic worker catches up.)
        viewModelScope.launch {
            while (true) {
                val r = withContext(Dispatchers.IO) { runCatching { Sync.syncMac(getApplication()) }.getOrNull() }
                if (r != null) refresh()
                kotlinx.coroutines.delay(60_000)
            }
        }
        // Feeds whose period turned while the app was closed.
        viewModelScope.launch {
            val ran = withContext(Dispatchers.IO) {
                runCatching { com.hubidubios.mobile.feed.FeedWork.run(getApplication()) }.getOrNull()
            }
            if (!ran.isNullOrEmpty()) refresh()
        }
    }

    // ---- feeds ----

    /** Watch [url]; the first look happens straight away and only remembers the page. */
    fun addFeed(url: String, title: String, period: String, model: String, projectId: String?) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val feed = sync().newFeed(mapOf(
                    "kind" to com.hubidubios.core.feed.Feeds.KIND_WEB, "url" to url.trim(),
                    "title" to title.trim().ifBlank { null }, "period" to period, "model" to model,
                    "project" to projectId))
                runCatching { com.hubidubios.mobile.feed.FeedWork.run(getApplication(), only = feed.id) }
            }
            refresh()
        }

    fun deleteFeed(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().delete(id) }
        refresh()
    }

    /** Look at one feed now. Its period still allows one task. Returns what happened. */
    suspend fun checkFeed(id: String): String {
        val msg = withContext(Dispatchers.IO) {
            runCatching { com.hubidubios.mobile.feed.FeedWork.run(getApplication(), only = id)[id] }
                .getOrElse { it.message } ?: "Nothing to look at"
        }
        refresh()
        return msg
    }

    /** Sync with the Mac now, not at the next minute. */
    fun syncMacNow() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { Sync.syncMac(getApplication()) } }
        refresh()
    }

    fun unpairMac() {
        com.hubidubios.mobile.net.MacLink.forget(getApplication()); refresh()
    }

    /**
     * Keep trying to pair until a Mac takes [code] or ten minutes pass: the code
     * may be typed on the Mac after it is shown here, and the Mac may be found by
     * Bonjour or at a typed [address]. Returns a line to show.
     */
    suspend fun pairMac(code: String, address: String?, shownHere: Boolean): String =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = android.os.Build.MODEL ?: "Phone"
            val until = System.currentTimeMillis() + com.hubidubios.core.peer.PeerPairing.CODE_TTL_MS
            while (System.currentTimeMillis() < until) {
                val candidates = listOfNotNull(address?.let(com.hubidubios.mobile.net.MacLink::parseAddress)) +
                    com.hubidubios.mobile.net.MacLink.discover(ctx)
                for (at in candidates) {
                    when (val out = com.hubidubios.mobile.net.MacLink.pair(ctx, code, at, name)) {
                        is com.hubidubios.mobile.net.MacLink.PairOutcome.Paired -> {
                            runCatching { Sync.syncMac(ctx) }
                            withContext(Dispatchers.Main) { refresh() }
                            return@withContext "Paired with ${out.mac.name}."
                        }
                        // Typed on this phone and the Mac refused it: wrong code.
                        com.hubidubios.mobile.net.MacLink.PairOutcome.WrongCode ->
                            if (!shownHere) return@withContext "The Mac didn't accept that code."
                        else -> {}
                    }
                }
                if (!kotlinx.coroutines.currentCoroutineContext().isActiveSafe()) break
                Thread.sleep(2500)
            }
            "No Mac took the code in time."
        }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe() =
        this[kotlinx.coroutines.Job]?.isActive != false

    private fun sync() = Sync.open(getApplication())

    fun refresh() = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) {
            val sync = sync()
            UiState(
                paired = sync.isPaired,
                inbox = sync.inbox(),
                nextActions = sync.nextActions(),
                staleProjects = sync.projectsWithoutNextAction(),
                notes = sync.notes(),
                projects = sync.projects(),
                dated = sync.dated(),
                events = sync.events(),
                mac = com.hubidubios.mobile.net.MacLink.load(getApplication()),
                inference = _state.value.inference,
                workstationHost = PairedServer.load(getApplication())?.host,
                feeds = sync.feeds().sortedBy { com.hubidubios.core.feed.Feeds.label(it).lowercase() },
            )
        }
        _state.value = s
        // The agent is asked about apart: an unreachable one would otherwise
        // hold the lists back for a whole connection timeout.
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) { health() }
            _state.value = _state.value.copy(inference = h)
        }
    }

    // --- the agent, asked directly (no workstation in between). One client is
    // kept while the settings stay the same, so its session stays warm.

    @Volatile private var agentConfig: AgentConfig? = null
    @Volatile private var agent: ModelClient? = null

    private fun agent(): Pair<AgentConfig, ModelClient> = synchronized(this) {
        val cfg = AgentSettings.load(getApplication())
        val cur = agent
        if (cur != null && cfg == agentConfig) return cfg to cur
        cur?.close()
        agentConfig = cfg
        Agent.clientFor(cfg).also { agent = it }.let { cfg to it }
    }

    // A health probe opens a session; refresh() runs after every tap, so the
    // answer is reused for a minute rather than asked for each time.
    @Volatile private var lastHealth: Pair<Long, Health>? = null

    private fun health(force: Boolean = false): Health {
        val now = System.currentTimeMillis()
        lastHealth?.let { (at, h) -> if (!force && now - at < 60_000) return h }
        return agent().second.health().also { lastHealth = now to it }
    }

    /** The agent settings this phone holds, for the settings form. */
    fun agentConfig(): AgentConfig = AgentSettings.load(getApplication())

    fun claudeKey(): String? = AgentSettings.claudeKey(getApplication())
    fun harnessEndpoint(): String? = AgentSettings.harnessEndpoint(getApplication())
    fun harnessToken(): String? = AgentSettings.harnessToken(getApplication())

    /** Re-probe the agent and report what came back. */
    suspend fun checkInference(): Health = withContext(Dispatchers.IO) { health(force = true) }
        .also { h -> _state.value = _state.value.copy(inference = h) }

    /** Point the phone at an agent. */
    fun setAgentConfig(config: AgentConfig) {
        AgentSettings.save(getApplication(), config)
        lastHealth = null
    }

    fun capture(text: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().capture(text, due) }
        refresh()
    }

    // --- media notes: text / image / audio, uploaded to the workstation ---

    fun noteText(title: String, due: String? = null, body: String = "") = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteText(title, body = body, due = due) }
        refresh()
    }

    fun noteImage(bytes: ByteArray, mime: String, title: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("image", bytes, mime, title, due) }
        refresh()
    }

    fun noteAudio(bytes: ByteArray, mime: String, title: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("audio", bytes, mime, title, due) }
        refresh()
    }

    // --- the same captures, filed as GTD inbox tasks instead of notes ---

    fun captureImage(bytes: ByteArray, mime: String, text: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().captureMedia("image", bytes, mime, text, due) }
        refresh()
    }

    fun captureAudio(bytes: ByteArray, mime: String, text: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().captureMedia("audio", bytes, mime, text, due) }
        refresh()
    }

    /** The local file for a media note's bytes (cached, or fetched on demand). */
    suspend fun mediaFile(sha: String): java.io.File? =
        withContext(Dispatchers.IO) { sync().mediaFile(sha) }

    fun fileClarify(inboxId: String, kind: String, text: String, context: String?) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                sync().fileClarify(inboxId, kind, text, context = context)
            }
            refresh()
        }

    /** Date an item so it shows up in Next, or clear it with null. */
    fun setDue(id: String, due: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().setDue(id, due) }
        refresh()
    }

    /** Keep the agent's review on the item so opening it again is free. */
    fun setReview(id: String, text: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().setReview(id, text) }
        refresh()
    }

    /** Delete an item; its attachment goes with it. */
    fun delete(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().delete(id) }
        refresh()
    }

    /** Create a project, optionally as a child of [parentId]. */
    fun createProject(title: String, parentId: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().createProject(title, parentId) }
        refresh()
    }

    /** A task filed straight into [project], skipping the inbox. */
    fun addTask(project: EntityRecord, text: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            sync().addNextAction(text.trim(), project.id,
                ProjectTree.slugOf(project.fields["title"] ?: project.id), due)
        }
        refresh()
    }

    fun renameProject(id: String, title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().renameProject(id, title) }
        refresh()
    }

    /** A note's title and body, rewritten where they were read. */
    fun editNote(id: String, title: String, body: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().editNote(id, title, body) }
        refresh()
    }

    /** Re-nest an item in the Projects tree. */
    fun move(id: String, parentId: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().moveTo(id, parentId) }
        refresh()
    }

    fun markDone(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().markDone(id) }
        refresh()
    }

    /**
     * Close a task as done. Its notes stay: they are reference, not work, so
     * they move up to the task's project (or out to the loose items) instead
     * of vanishing with it.
     *
     * An inbox capture has no "done" on the workstation (its line has no
     * checkbox), so marking it would come back undone on the next sync;
     * finishing one removes it instead.
     */
    fun complete(rec: EntityRecord) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val sync = sync()
            val up = rec.fields["parent"]?.takeIf { it.isNotBlank() }
            ProjectTree.children(rec.id, sync.notes()).forEach { sync.moveTo(it.id, up) }
            if (rec.type == "inbox_item") sync.delete(rec.id) else sync.markDone(rec.id)
        }
        refresh()
    }

    /** A note under a task. */
    fun addNote(parentId: String, title: String, body: String = "") = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteText(title.trim(), body.trim(), parent = parentId) }
        refresh()
    }

    fun syncNow() = viewModelScope.launch {
        _state.value = _state.value.copy(syncing = true)
        withContext(Dispatchers.IO) { runCatching { sync().syncNow() } }
        refresh()
        _state.value = _state.value.copy(syncing = false)
    }

    /**
     * Ask the agent. A review ([plain]) sends the item alone; a chat question
     * goes with a short picture of what is open, since the agent no longer has
     * the workstation's vault to look things up in.
     */
    suspend fun ask(query: String, plain: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val (cfg, client) = agent()
            Agent.ask(client, cfg, query, if (plain) null else chatContext(_state.value))
        }

    private fun chatContext(s: UiState): String {
        val projectTitle = s.projects.associate { it.id to (it.fields["title"] ?: it.id) }
        val lines = ArrayList<String>()
        lines += "What the user has open (tasks with their project and date):"
        for (t in (s.nextActions + s.inbox).sortedBy { it.fields["due"] ?: "~" }) {
            val text = t.fields["text"] ?: t.fields["title"] ?: continue
            val project = t.fields["parent"]?.let { projectTitle[it] }
            lines += "- " + text + (project?.let { " [$it]" } ?: "") +
                (t.fields["due"]?.takeIf { it.isNotBlank() }?.let { " (due $it)" } ?: "")
        }
        // The server keeps only the front of an over-long prompt; the list is
        // cut here, soonest first, so the question itself is never what goes.
        val out = StringBuilder()
        for (l in lines) { if (out.length + l.length > 1500) break; out.append(l).append('\n') }
        return out.toString().trimEnd()
    }

    // --- one-time import. Nothing here is kept: the token and the calendar
    // address live only as long as the call that uses them.

    /** Read Todoist and work out what an import would add. Throws on failure. */
    suspend fun planTodoist(token: String): Todoist.Plan = withContext(Dispatchers.IO) {
        val (projects, tasks) = Todoist.fetch(token.trim())
        val s = _state.value
        val titleOf = s.projects.associate { it.id to (it.fields["title"] ?: it.id) }
        val idBySlug = s.projects.associate { ProjectTree.slugOf(it.fields["title"] ?: it.id) to it.id }
        val existing = (s.nextActions + s.inbox + s.notes).associate { rec ->
            (ProjectTree.parentOf(rec, idBySlug)?.let { titleOf[it] ?: parentProject(it, s) } to
                (rec.fields["text"] ?: rec.fields["title"] ?: "")) to rec.id
        }
        Todoist.plan(projects, tasks, titleOf.values, existing)
    }

    /** The project a sub-task's parent sits in, by name. */
    private fun parentProject(parentId: String, s: UiState): String? {
        val parent = (s.nextActions + s.inbox).firstOrNull { it.id == parentId } ?: return null
        return parent.fields["parent"]?.let { pid -> s.projects.firstOrNull { it.id == pid } }
            ?.let { it.fields["title"] ?: it.id }
    }

    /** Write a plan's projects and tasks, then sync them out. Returns tasks written. */
    suspend fun importTodoist(plan: Todoist.Plan): Int = withContext(Dispatchers.IO) {
        val sync = sync()
        val idByName = HashMap<String, String>()
        sync.projects().forEach { idByName[(it.fields["title"] ?: it.id).trim().lowercase()] = it.id }
        plan.newProjects.forEach { idByName[it.trim().lowercase()] = sync.createProject(it) }
        val idByKey = HashMap<String, String>()   // todoist id -> the id it got here
        plan.tasks.forEach { t ->   // parents come first
            val projectId = t.project?.let { idByName[it.trim().lowercase()] }
            val task = t.parentKey?.let { idByKey[it] ?: t.existingParent }
            idByKey[t.key] = if (t.parentKey != null && task != null) {
                // A Todoist sub-task is a note under its task here.
                sync.noteText(t.text, t.body.orEmpty(), t.due, parent = task)
            } else {
                sync.addNextAction(t.text, projectId,
                    t.project?.let(ProjectTree::slugOf), t.due, t.body)
            }
        }
        plan.tasks.size
    }.also { refresh(); syncNow() }

    data class CalendarPlan(
        val events: List<Ics.Occurrence>,
        val alreadyHere: Int,
        val unsupportedRules: Int,
    )

    /** Read a calendar feed: occurrences from today through the next twelve months. */
    suspend fun planCalendar(url: String): CalendarPlan = withContext(Dispatchers.IO) {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val res = Ics.parse(fetchCalendarText(url), today, today.plusMonths(12), zone)
        val known = sync().events().map { it.id }.toHashSet()
        val (here, fresh) = res.occurrences.partition { eventId(it.uid) in known }
        CalendarPlan(fresh, here.size, res.unsupportedRules)
    }

    suspend fun importCalendar(plan: CalendarPlan): Int = withContext(Dispatchers.IO) {
        val sync = sync()
        plan.events.count { sync.addEvent(it, "google") }
    }.also { refresh(); syncNow() }
}
