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
import com.hubidubitask.core.agent.AgentStore
import com.hubidubitask.core.agent.AgentType
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync
import com.hubidubitask.mobile.importer.ImportContext
import com.hubidubitask.mobile.importer.ImportPlan
import com.hubidubitask.mobile.importer.ImportSource
import com.hubidubitask.mobile.importer.ImportValue
import com.hubidubitask.mobile.importer.ImportWriter
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

    /** Watch a [kind] as typed ([values]); the first look happens straight away and only remembers it. */
    fun addFeed(kind: com.hubidubitask.core.feed.FeedKind, values: Map<String, String>, period: String,
                projectId: String?) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val feed = sync().newFeed(com.hubidubitask.core.feed.Feeds.newFields(kind, values, period, projectId))
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
     * here, and the computer may be found by Bonjour, by a sweep of the Wi-Fi,
     * or at a typed [address] (read on every pass). [reached] hears which
     * computers answered. Returns a line to show.
     */
    suspend fun pairMac(code: String, address: () -> String?, shownHere: Boolean,
                        reached: (List<com.hubidubitask.core.peer.PeerAddress>) -> Unit = {}): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = android.os.Build.MODEL ?: t("Phone")
            val link = com.hubidubitask.mobile.net.MacLink
            val scope = kotlinx.coroutines.currentCoroutineContext()
            val run = com.hubidubitask.core.peer.PhonePairing(code, link.deviceId(ctx), name, shownHere,
                reach = { link.client(ctx, it.host, it.port, null) })
            when (val out = run.run(link.candidates(ctx, address), { scope.isActiveSafe() }, reached)) {
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

    /** What [type]'s fields in Settings show. */
    fun agentForm(type: AgentType): Map<String, String> = AgentSettings.store(getApplication()).form(type)

    /** Whether keeping [values] for [type] is followed by a check. */
    fun agentWillCheck(type: AgentType, values: Map<String, String>): Boolean =
        AgentSettings.store(getApplication()).willCheck(type, values)

    /** Keep what was typed for [type] and, when it is enough, check that it answers. */
    suspend fun setUpAgent(type: AgentType, values: Map<String, String>): AgentStore.Outcome =
        withContext(Dispatchers.IO) {
            AgentSettings.store(getApplication()).setUp(type, values) {
                lastHealth = null
                health(force = true).also { h -> _state.value = _state.value.copy(inference = h) }
            }
        }

    /** Re-probe the agent and report what came back. */
    suspend fun checkInference(): Health = withContext(Dispatchers.IO) { health(force = true) }
        .also { h -> _state.value = _state.value.copy(inference = h) }

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
    /** What an import from [source] would add. Network or file reading; off the main thread. */
    suspend fun planImport(source: ImportSource, value: ImportValue): ImportPlan = withContext(Dispatchers.IO) {
        source.read(value, ImportContext(sync().store))
    }

    /** [planImport] for a file the user picked, read here (at most [ImportValue.FILE_LIMIT]). */
    suspend fun planImport(source: ImportSource, uri: android.net.Uri): ImportPlan = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > ImportValue.FILE_LIMIT) throw java.io.IOException(t("That file is too large."))
            }
            out.toByteArray()
        } ?: throw java.io.IOException(t("Couldn't read it."))
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        source.read(ImportValue.File(name, bytes), ImportContext(sync().store))
    }

    /** Write [plan], every item marked as imported, then sync it out. */
    suspend fun applyImport(plan: ImportPlan): ImportWriter.Result = withContext(Dispatchers.IO) {
        ImportWriter.apply(plan, sync().store)
    }.also { refresh(); syncNow() }
}
