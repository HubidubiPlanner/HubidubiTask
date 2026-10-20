package com.hubidubitask.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubidubitask.core.Backup
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.NewItemDue
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.agent.AgentAction
import com.hubidubitask.core.agent.AgentItems
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentLink
import com.hubidubitask.core.agent.AgentProbe
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync
import com.hubidubitask.mobile.importer.Ics
import com.hubidubitask.mobile.importer.Todoist
import com.hubidubitask.mobile.importer.fetchCalendarText
import com.hubidubitask.mobile.sync.eventId
import com.hubidubitask.mobile.tree.ProjectTree
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
class HubidubiTaskViewModel(app: Application) : AndroidViewModel(app) {

    /** The language the screens speak; changing it redraws them in place. */
    private val _language = MutableStateFlow(UiLanguage.apply(app))
    val language: StateFlow<Language> = _language.asStateFlow()

    fun setLanguage(l: Language) {
        UiLanguage.save(getApplication(), l)
        _language.value = l
        // The home screen's "New note" is named in the app's language too.
        com.hubidubitask.mobile.capture.NoteShortcut.publish(getApplication())
    }

    /**
     * The due date every new task and note gets ([NewItemDue]), chosen in
     * Settings; nothing is asked when one is made.
     */
    private val _newDue = MutableStateFlow(NewDue.load(app))
    val newDue: StateFlow<String> = _newDue.asStateFlow()

    fun setNewDue(choice: String) {
        NewDue.save(getApplication(), choice)
        _newDue.value = NewItemDue.of(choice)
    }

    private fun due(): String? = NewItemDue.due(_newDue.value)

    /** The colour theme ([ColorTheme]) chosen in Settings. */
    private val _theme = MutableStateFlow(ThemeChoice.load(app))
    val theme: StateFlow<ColorTheme> = _theme.asStateFlow()

    fun setTheme(theme: ColorTheme) {
        ThemeChoice.save(getApplication(), theme)
        _theme.value = theme
    }


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
        /** Calendar events (imported here or on the computer), for Next. */
        val events: List<EntityRecord> = emptyList(),
        /** The paired Mac, if any. */
        /** The computers this phone is paired with. */
        val macs: List<com.hubidubitask.mobile.net.MacLink.Mac> = emptyList(),
        /** Watched pages, each turned into at most one task per period. */
        val feeds: List<EntityRecord> = emptyList(),
        /** Each reviewed item's summary, and whether it still describes what is there. */
        val reviews: Map<String, ItemSummary.Status> = emptyMap(),
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
                runCatching { com.hubidubitask.mobile.feed.FeedWork.run(getApplication()) }.getOrNull()
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
                    "kind" to com.hubidubitask.core.feed.Feeds.KIND_WEB, "url" to url.trim(),
                    "title" to title.trim().ifBlank { null }, "period" to period, "model" to model,
                    "project" to projectId))
                runCatching { com.hubidubitask.mobile.feed.FeedWork.run(getApplication(), only = feed.id) }
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
            runCatching { com.hubidubitask.mobile.feed.FeedWork.run(getApplication(), only = id)[id] }
                .getOrElse { it.message } ?: t("Nothing to look at")
        }
        refresh()
        return msg
    }

    // ---- Pro: not sold yet. "Sign up for Pro" only counts who would want it.

    /** Whether this phone has already said it wants Pro. */
    fun proWanted(): Boolean = proPrefs().getBoolean("wanted", false)

    /** Say so, once: kept as said when the answer went through. */
    suspend fun wantPro(): com.hubidubitask.core.pro.ProInterest.Result = withContext(Dispatchers.IO) {
        com.hubidubitask.core.pro.ProInterest.send(com.hubidubitask.core.pro.ProInterest.ANDROID,
            com.hubidubitask.mobile.BuildConfig.VERSION_NAME, language.value.code)
    }.also { if (it == com.hubidubitask.core.pro.ProInterest.Result.SENT) proPrefs().edit().putBoolean("wanted", true).apply() }

    private fun proPrefs() = getApplication<Application>()
        .getSharedPreferences("pro", android.content.Context.MODE_PRIVATE)

    /** Sync with the paired computers now, not at the next minute. */
    fun syncMacNow() = viewModelScope.launch {
        _state.value = _state.value.copy(syncing = true)
        withContext(Dispatchers.IO) { runCatching { Sync.syncMac(getApplication()) } }
        refresh()
        _state.value = _state.value.copy(syncing = false)
    }

    /** Let go of one computer; the others stay paired. */
    fun unpairMac(id: String) {
        com.hubidubitask.mobile.net.MacLink.forget(getApplication(), id); refresh()
    }

    /**
     * Keep trying to pair until a computer takes [code], ten minutes pass, or
     * five tries fail: the code may be typed on the computer after it is shown
     * here, and the computer may be found by Bonjour or at a typed [address].
     * Returns a line to show.
     */
    suspend fun pairMac(code: String, address: String?, shownHere: Boolean): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = android.os.Build.MODEL ?: t("Phone")
            val link = com.hubidubitask.mobile.net.MacLink
            val scope = kotlinx.coroutines.currentCoroutineContext()
            val run = com.hubidubitask.core.peer.PhonePairing(code, link.deviceId(ctx), name, shownHere)
            when (val out = run.run({ listOfNotNull(address?.let(link::parseAddress)) + link.discover(ctx) },
                                    { scope.isActiveSafe() })) {
                is com.hubidubitask.core.peer.PhonePairing.Result.Paired -> {
                    val mac = link.keep(ctx, out.at, out.outcome)
                    runCatching { Sync.syncMac(ctx) }
                    withContext(Dispatchers.Main) { refresh() }
                    true to t("Paired with %s.", mac.name)
                }
                // Typed on this phone and the computer refused it: wrong code.
                com.hubidubitask.core.peer.PhonePairing.Result.WrongCode ->
                    false to t("The computer didn't accept that code.")
                com.hubidubitask.core.peer.PhonePairing.Result.GaveUp ->
                    false to t("Stopped after 5 failed tries. Start again with a new code.")
                com.hubidubitask.core.peer.PhonePairing.Result.TimedOut ->
                    false to t("No computer took the code in time.")
            }
        }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe() =
        this[kotlinx.coroutines.Job]?.isActive != false

    private fun sync() = Sync.open(getApplication())

    fun refresh() = viewModelScope.launch {
        val s = withContext(Dispatchers.IO) {
            val sync = sync()
            UiState(
                paired = com.hubidubitask.mobile.net.MacLink.all(getApplication()).isNotEmpty(),
                inbox = sync.inbox(),
                nextActions = sync.nextActions(),
                staleProjects = sync.projectsWithoutNextAction(),
                notes = sync.notes(),
                projects = sync.projects(),
                dated = sync.dated(),
                events = sync.events(),
                macs = com.hubidubitask.mobile.net.MacLink.all(getApplication()),
                inference = _state.value.inference,
                feeds = sync.feeds().sortedBy { com.hubidubitask.core.feed.Feeds.label(it).lowercase() },
                reviews = ItemSummary.statuses(REVIEWABLE.flatMap { sync.byType(it) }),
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

    // --- the agent, asked directly (nothing in between). One client is
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

    /**
     * The phone's items as a question to the user's agent carries them, and
     * where the changes it asked for land: as if typed here, so they sync out.
     */
    private val agentItems = object : AgentItems {
        override fun records(): List<EntityRecord> = sync().let { s ->
            listOf("project", "next_action", "inbox_item", "note", "event").flatMap(s::byType) }

        override fun apply(actions: List<AgentAction>) {
            val s = sync()
            for (a in actions) when (a.op) {
                "add_task" -> {
                    val p = a.project?.let(s::get)?.takeIf { it.type == "project" && !it.deleted }
                    s.addNextAction(a.text ?: continue, p?.id,
                        p?.let { ProjectTree.slugOf(it.fields["title"] ?: it.id) }, a.due)
                }
                "capture" -> s.capture(a.text ?: continue)
                "mark_done" -> s.get(a.id ?: continue)?.takeIf { !it.deleted }?.let { r ->
                    if (r.type == "inbox_item") s.delete(r.id) else if (r.type == "next_action") s.markDone(r.id)
                }
            }
            refresh()
        }
    }

    // A health probe opens a session; refresh() runs after every tap, so the
    // answer is reused for a minute rather than asked for each time.
    @Volatile private var lastHealth: Pair<Long, Health>? = null

    private fun health(force: Boolean = false): Health {
        val now = System.currentTimeMillis()
        lastHealth?.let { (at, h) -> if (!force && now - at < 60_000) return h }
        val (cfg, client) = agent()
        return client.health().also { h ->
            lastHealth = now to h
            // A server's certificate in the home network is its own: the first one
            // answering after Save is trusted, and only it after that. Any agent.
            if (h.connected && cfg.tlsFingerprint == null)
                h.fingerprint?.let { AgentSettings.pin(getApplication(), cfg, it) }
        }
    }

    /** The agent settings this phone holds, for the settings form. */
    fun agentConfig(): AgentConfig = AgentSettings.load(getApplication())

    fun claudeKey(): String? = AgentSettings.claudeKey(getApplication())
    fun harnessEndpoint(): String = AgentSettings.harnessEndpoint(getApplication())
    fun harnessToken(): String? = AgentSettings.harnessToken(getApplication())

    /**
     * Find the user's agent at [host] and [port] (blank: each kind's usual
     * one), and make it the Agent if it answers. What was typed is kept either
     * way, so the form shows it again.
     */
    suspend fun plugAgent(host: String, port: String, token: String): AgentProbe.Result =
        withContext(Dispatchers.IO) {
            val typed = AgentLink.parse(host, port) ?: return@withContext AgentProbe.Result.NotFound
            val found = AgentProbe.detect(typed.first, typed.second, token.trim().ifBlank { null })
            val app = getApplication<Application>()
            when (found) {
                is AgentProbe.Result.Found ->
                    AgentSettings.saveHarness(app, found.endpoint, found.kind.id, found.model, found.fingerprint, token)
                is AgentProbe.Result.NeedsToken ->
                    AgentSettings.saveHarness(app, found.endpoint, found.kind.id, "", null, token)
                AgentProbe.Result.NotFound ->
                    AgentSettings.saveHarness(app, AgentLink.endpoint("https", typed.first, typed.second), null, "", null, token)
            }
            AgentSettings.save(app, AgentSettings.load(app).copy(protocol = "harness"))
            lastHealth = null
            found
        }

    /** Re-probe the agent and report what came back. */
    suspend fun checkInference(): Health = withContext(Dispatchers.IO) { health(force = true) }
        .also { h -> _state.value = _state.value.copy(inference = h) }

    /** Point the phone at an agent. */
    fun setAgentConfig(config: AgentConfig) {
        AgentSettings.save(getApplication(), config)
        lastHealth = null
    }

    fun capture(text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().capture(text, due()) }
        refresh()
    }

    // --- media notes: text / image / audio; peer sync carries them to the computer ---

    fun noteText(title: String, body: String = "") = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteText(title, body = body, due = due()) }
        refresh()
    }

    fun noteImage(bytes: ByteArray, mime: String, title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("image", bytes, mime, title, due()) }
        refresh()
    }

    fun noteAudio(bytes: ByteArray, mime: String, title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("audio", bytes, mime, title, due()) }
        refresh()
    }

    // --- the same captures, filed as GTD inbox tasks instead of notes ---

    fun captureImage(bytes: ByteArray, mime: String, text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().captureMedia("image", bytes, mime, text, due()) }
        refresh()
    }

    fun captureAudio(bytes: ByteArray, mime: String, text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().captureMedia("audio", bytes, mime, text, due()) }
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
    fun addTask(project: EntityRecord, text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            sync().addNextAction(text.trim(), project.id,
                ProjectTree.slugOf(project.fields["title"] ?: project.id), due())
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
        withContext(Dispatchers.IO) { sync().noteText(title.trim(), body.trim(), due = due(), parent = parentId) }
        refresh()
    }

    /** Sync with the paired computer now. */
    fun syncNow() = syncMacNow()

    /**
     * A review of one item by the AI chosen in Settings: a summary of what
     * happened in it, from it and everything filed under it ([ItemSummary]).
     * A real answer is kept on the item with the fingerprint of what it read;
     * why there is none is shown, not kept. The user's own agent also gets
     * the phone's items to look things up in.
     */
    suspend fun review(record: EntityRecord): String {
        val answer = withContext(Dispatchers.IO) {
            val sync = sync()
            val all = REVIEWABLE.flatMap { sync.byType(it) }
            val root = all.firstOrNull { it.id == record.id } ?: record
            val under = ItemSummary.under(root, all)
            val (cfg, client) = agent()
            Agent.ask(client, cfg, ItemSummary.prompt(root, under), agentItems).also { a ->
                if (a.isNotBlank() && !a.startsWith("error:"))
                    sync.setReview(root.id, a, ItemSummary.fingerprint(root, under))
            }
        }
        refresh()
        return answer
    }

    private companion object {
        val REVIEWABLE = listOf("project", "next_action", "inbox_item", "note")
    }

    // --- a backup: everything on this phone as one file ([Backup]), and back.

    /** Everything here, sealed with [password] and written to [uri]: how many items and media. */
    suspend fun exportTo(uri: android.net.Uri, password: CharArray): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val sync = sync()
        val records = sync.store.all()
        val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
            ?: throw java.io.IOException(t("Couldn't write the file."))
        out.use { records.size to Backup.write(it, records, com.hubidubitask.mobile.BuildConfig.VERSION_NAME, password) { sha ->
            sync.mediaFile(sha)?.readBytes() } }
    }

    /** Whether the backup at [uri] is sealed with a password. */
    suspend fun backupIsSealed(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { Backup.isSealed(it) } ?: false
    }

    /** What restoring the backup at [uri] would change here. Throws [Backup.NotABackup], a wrong password. */
    suspend fun planBackup(uri: android.net.Uri, password: CharArray?): Backup.Plan = withContext(Dispatchers.IO) {
        val sync = sync()
        val contents = getApplication<Application>().contentResolver.openInputStream(uri)?.use { Backup.read(it, password) }
            ?: throw Backup.NotABackup()
        Backup.plan(contents) { sync.store.get(it) }
    }

    /** The items [plan] takes, then the backup's media this phone lacks. Returns how many items. */
    suspend fun restoreBackup(uri: android.net.Uri, plan: Backup.Plan, password: CharArray?): Int {
        val n = withContext(Dispatchers.IO) {
            val sync = sync()
            plan.incoming.forEach { sync.store.put(it) }
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                Backup.media(input, password) { sha, bytes -> sync.keepMedia(sha, bytes) }
            }
            plan.incoming.size
        }
        refresh()
        return n
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
