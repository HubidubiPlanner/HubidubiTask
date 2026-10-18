package com.hubidubitask.desktop.ui

import com.hubidubitask.core.Backup
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.NewItemDue
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentLink
import com.hubidubitask.core.agent.AgentProbe
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.core.feed.PageFetch
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.desktop.PeerServer
import com.hubidubitask.desktop.Store
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.importer.Ics
import com.hubidubitask.mobile.importer.Todoist
import com.hubidubitask.mobile.importer.fetchCalendarText
import com.hubidubitask.mobile.tree.ProjectTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The Mac's model: the same surface the phone's ViewModel offers its screens,
 * over the Mac's own store. Every edit is a record with a fresh modification
 * time, written to the store's change log — which is what the phone picks up
 * on its next sync round.
 */
class HubidubiTaskViewModel(val store: Store, val server: PeerServer) {

    /**
     * The language the screens speak, chosen in Settings; until then this
     * computer's own language when the app speaks it, else English. Changing it
     * redraws the screens in place.
     */
    private val _language = MutableStateFlow(
        (Language.of(store.kv("ui_language")) ?: Language.system())
            .also { I18n.language = it })
    val language: StateFlow<Language> = _language

    fun setLanguage(l: Language) {
        store.setKv("ui_language", l.code)
        I18n.language = l
        _language.value = l
    }

    /**
     * The due date every new task and note gets ([NewItemDue]), chosen in
     * Settings and kept on this computer; nothing is asked when one is made.
     * The key was named when it covered notes alone.
     */
    private val _newDue = MutableStateFlow(NewItemDue.of(store.kv("note_due")))
    val newDue: StateFlow<String> = _newDue

    fun setNewDue(choice: String) {
        store.setKv("note_due", NewItemDue.of(choice))
        _newDue.value = NewItemDue.of(choice)
    }

    private fun due(): String? = NewItemDue.due(_newDue.value)

    /** The colour theme ([ColorTheme]) chosen in Settings, kept on this computer. */
    private val _theme = MutableStateFlow(ColorTheme.of(store.kv("theme")))
    val theme: StateFlow<ColorTheme> = _theme

    fun setTheme(theme: ColorTheme) {
        store.setKv("theme", theme.id)
        _theme.value = theme
    }

    /** What a question to the user's agent carries, and where its changes land. */
    private val items = com.hubidubitask.desktop.agent.Items(store) { refresh() }

    data class UiState(
        /** A phone is paired with this Mac. */
        val paired: Boolean = false,
        val syncing: Boolean = false,
        val inference: Health = Health(false, null, emptyList()),
        val inbox: List<EntityRecord> = emptyList(),
        val nextActions: List<EntityRecord> = emptyList(),
        val notes: List<EntityRecord> = emptyList(),
        val projects: List<EntityRecord> = emptyList(),
        val dated: List<EntityRecord> = emptyList(),
        val events: List<EntityRecord> = emptyList(),
        val peers: List<Store.Peer> = emptyList(),
        /** Watched pages, each turned into at most one task per period. */
        val feeds: List<EntityRecord> = emptyList(),
        /** Each reviewed item's summary, and whether it still describes what is there. */
        val reviews: Map<String, ItemSummary.Status> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        // Feeds whose day, week or month has turned: looked at on start and
        // every half hour while the app is open.
        scope.launch {
            while (true) {
                val ran = withContext(Dispatchers.IO) { runCatching { runFeeds() }.getOrNull() }
                if (!ran.isNullOrEmpty()) refresh()
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    fun refresh() = scope.launch {
        val s = withContext(Dispatchers.IO) {
            val all = store.all()
            fun ofType(type: String) = all.filter { it.type == type }
            val peers = store.peers()
            UiState(
                paired = peers.isNotEmpty(),
                inbox = ofType("inbox_item"),
                nextActions = ofType("next_action").filter { it.fields["done"] != "true" },
                notes = ofType("note"),
                projects = ofType("project"),
                dated = (ofType("next_action") + ofType("inbox_item") + ofType("note"))
                    .filter { !it.fields["due"].isNullOrBlank() && it.fields["done"] != "true" }
                    .sortedBy { it.fields["due"] },
                events = ofType("event"),
                inference = _state.value.inference,
                peers = peers,
                feeds = ofType(Feeds.TYPE).sortedBy { Feeds.label(it).lowercase() },
                reviews = ItemSummary.statuses(all),
            )
        }
        _state.value = s
        // The agent is asked about apart: an unreachable one would otherwise
        // hold the lists back for a whole connection timeout.
        scope.launch {
            val h = withContext(Dispatchers.IO) { health() }
            _state.value = _state.value.copy(inference = h)
        }
    }

    /** The phone drives sync; here it only means "read the store again". */
    fun syncNow() = refresh()

    // ---- writes

    private fun id(prefix: String) = prefix + "_" + PeerPairing.nonce().take(8)
        .map { "abcdefghijklmnopqrstuvwxyz234567"[it.code % 32] }.joinToString("")

    private fun write(type: String, id: String, fields: Map<String, String?>) =
        store.put(EntityRecord(id, type, nowUtc(), fields.filterValues { it != null }))

    private fun edit(id: String, change: (Map<String, String?>) -> Map<String, String?>) {
        val rec = store.get(id) ?: return
        store.put(rec.copy(updated = nowUtc(), fields = change(rec.fields)))
    }

    private fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; refresh() }

    fun capture(text: String) = io {
        val now = java.time.LocalDateTime.now()
        write("inbox_item", id("i"), mapOf("text" to text, "due" to due(),
            "date" to now.toLocalDate().toString(),
            "time" to "%02d:%02d".format(now.hour, now.minute)))
    }

    fun noteText(title: String, body: String = "") = io {
        write("note", id("n"), mapOf("title" to title, "kind" to "text", "body" to body, "due" to due()))
    }

    /** A photo, recording or file kept as a note. The bytes sit in blobs/ by
     *  their hash, the record names them, and the phone fetches them from here. */
    fun noteMedia(kind: String, bytes: ByteArray, mime: String, title: String) = io {
        val sha = store.putBlob(bytes)
        write("note", id("n"), mapOf("title" to title, "kind" to kind, "media_sha256" to sha,
            "media_mime" to mime, "media_bytes" to bytes.size.toString(), "due" to due()))
    }

    /** The same media as a task: the phone's "do something about this". */
    fun captureMedia(kind: String, bytes: ByteArray, mime: String, text: String) = io {
        val sha = store.putBlob(bytes)
        val now = java.time.LocalDateTime.now()
        write("inbox_item", id("i"), mapOf("text" to text, "due" to due(),
            "date" to now.toLocalDate().toString(),
            "time" to "%02d:%02d".format(now.hour, now.minute),
            "kind" to kind, "media_sha256" to sha, "media_mime" to mime,
            "media_bytes" to bytes.size.toString()))
    }

    fun addNote(parentId: String, title: String, body: String = "") = io {
        write("note", id("n"), mapOf("title" to title.trim(), "kind" to "text",
            "body" to body.trim(), "parent" to parentId, "due" to due()))
    }

    fun createProject(title: String, parentId: String? = null) = io {
        write("project", id("p"), mapOf("title" to title.trim(), "status" to "active", "parent" to parentId))
    }

    /** As on the phone: a task filed straight into [project], skipping the inbox. */
    fun addTask(project: EntityRecord, text: String) = io {
        write("next_action", id("t"), mapOf("text" to text.trim(), "parent" to project.id,
            "project" to ProjectTree.slugOf(project.fields["title"] ?: project.id), "due" to due()))
    }

    fun renameProject(id: String, title: String) = io { edit(id) { it + ("title" to title.trim()) } }

    fun editNote(id: String, title: String, body: String) = io {
        edit(id) { it + ("title" to title.trim()) + ("body" to body.trim()) }
    }

    fun move(id: String, parentId: String?) = io {
        edit(id) { f -> if (parentId.isNullOrBlank()) f - "parent" else f + ("parent" to parentId) }
    }

    fun setDue(id: String, due: String?) = io {
        edit(id) { f -> if (due == null) f - "due" else f + ("due" to due) }
    }

    fun delete(id: String) = io {
        store.get(id)?.let { store.put(it.tombstone(nowUtc())) }
    }

    fun markDone(id: String) = io { edit(id) { it + ("done" to "true") } }

    /** As on the phone: a task's notes move up to its project; a capture is removed. */
    fun complete(rec: EntityRecord) = io {
        val up = rec.fields["parent"]?.takeIf { it.isNotBlank() }
        ProjectTree.children(rec.id, store.byType("note")).forEach { n ->
            edit(n.id) { f -> if (up == null) f - "parent" else f + ("parent" to up) }
        }
        if (rec.type == "inbox_item") store.get(rec.id)?.let { store.put(it.tombstone(nowUtc())) }
        else edit(rec.id) { it + ("done" to "true") }
    }

    // ---- the agent, asked directly

    private var agentCfg: AgentConfig? = null
    private var agent: ModelClient? = null
    private var lastHealth: Pair<Long, Health>? = null

    fun agentConfig(): AgentConfig {
        val p = store.kv("agent_protocol") ?: AgentConfig.DEFAULT_PROTOCOL
        val harness = p == "harness"
        return AgentConfig(
            endpoint = if (harness) harnessEndpoint()
                       else store.kv("agent_endpoint") ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = p,
            model = if (harness) store.kv(HARNESS_MODEL).orEmpty() else store.kv("agent_model") ?: AgentConfig.defaultModel(p),
            apiKey = when (p) { "claude" -> claudeKey(); "harness" -> harnessToken(); else -> null },
            tlsFingerprint = if (harness) store.kv(HARNESS_FP) else store.kv("agent_fp"),
            kind = if (harness) store.kv(HARNESS_KIND) else null,
        )
    }

    fun setAgentConfig(c: AgentConfig) {
        // Claude and the user's agent keep their own address and secret (see
        // plugAgent), so switching back and forth loses neither.
        when (c.protocol) {
            "claude" -> c.apiKey?.let { Secrets.put(CLAUDE_KEY, it) }
            "harness" -> {}
            else -> { store.setKv("agent_endpoint", c.endpoint); store.setKv("agent_fp", c.tlsFingerprint) }
        }
        store.setKv("agent_protocol", c.protocol)
        if (c.protocol != "harness") store.setKv("agent_model", c.model)
        lastHealth = null
    }

    /** Claude's key, from the system's secret store rather than the app's database. */
    fun claudeKey(): String? = Secrets.get(CLAUDE_KEY)
    /** The user's own agent on a computer at home, "scheme://ip:port"; blank until found. */
    fun harnessEndpoint(): String = store.kv(HARNESS_ENDPOINT).orEmpty()
    /** Its token or password, from the system's secret store. */
    fun harnessToken(): String? = Secrets.get(HARNESS_TOKEN)?.takeIf { it.isNotBlank() }

    /**
     * Find the user's agent at [host] and [port] (blank: each kind's usual
     * one), and make it the Agent if it answers. What was typed is kept either
     * way, so the form shows it again. Its certificate is pinned from now on.
     */
    suspend fun plugAgent(host: String, port: String, token: String): AgentProbe.Result =
        withContext(Dispatchers.IO) {
            val typed = AgentLink.parse(host, port) ?: return@withContext AgentProbe.Result.NotFound
            val found = AgentProbe.detect(typed.first, typed.second, token.trim().ifBlank { null })
            fun save(endpoint: String, kind: String?, model: String, fp: String?) {
                store.setKv(HARNESS_ENDPOINT, endpoint); store.setKv(HARNESS_KIND, kind)
                store.setKv(HARNESS_MODEL, model); store.setKv(HARNESS_FP, fp)
                Secrets.put(HARNESS_TOKEN, token.trim())
            }
            when (found) {
                is AgentProbe.Result.Found -> save(found.endpoint, found.kind.id, found.model, found.fingerprint)
                is AgentProbe.Result.NeedsToken -> save(found.endpoint, found.kind.id, "", null)
                AgentProbe.Result.NotFound -> save(AgentLink.endpoint("https", typed.first, typed.second), null, "", null)
            }
            store.setKv("agent_protocol", "harness")
            lastHealth = null
            found
        }

    /**
     * What a feed writes its task with: Claude. Never the user's own agent: a
     * feed's text is a stranger's web page, and that agent can run commands.
     */
    private fun feedConfig() = AgentConfig(endpoint = "", protocol = "claude", apiKey = claudeKey())

    private companion object {
        const val CLAUDE_KEY = "claude-api-key"
        const val HARNESS_ENDPOINT = "harness_endpoint"
        const val HARNESS_FP = "harness_fp"
        const val HARNESS_KIND = "harness_kind"
        const val HARNESS_MODEL = "harness_model"
        const val HARNESS_TOKEN = "harness-token"
    }

    // ---- feeds: watched pages, each turned into at most one task per period

    /** Look at every feed whose period has turned, or [only] now. Returns what happened, by feed id. */
    @Synchronized
    private fun runFeeds(only: String? = null): Map<String, String> {
        val today = java.time.LocalDate.now()
        val out = LinkedHashMap<String, String>()
        for (feed in store.byType(Feeds.TYPE)) {
            if (only != null && feed.id != only) continue
            if (only == null && !Feeds.isDue(feed, today)) continue
            val client = Agent.clientFor(feedConfig())
            val result = try {
                Feeds.check(feed, today, nowUtc(), PageFetch::get, { client.complete(it) },
                    { store.get(it) != null })
            } finally { client.close() }
            store.put(result.feed)
            result.task?.takeIf { store.get(it.id) == null }?.let { t ->
                val project = t.project?.let(store::get)?.takeIf { !it.deleted }
                write("next_action", t.id, mapOf("text" to t.text, "parent" to project?.id,
                    "project" to (project?.let { ProjectTree.slugOf(it.fields["title"] ?: it.id) } ?: "inbox"),
                    "due" to t.due, "body" to t.body))
            }
            out[feed.id] = result.message
        }
        return out
    }

    /** Watch [url]; the first look happens straight away and only remembers the page. */
    fun addFeed(url: String, title: String, period: String, model: String, projectId: String?) =
        scope.launch {
            withContext(Dispatchers.IO) {
                val fid = id("f")
                write(Feeds.TYPE, fid, mapOf("kind" to Feeds.KIND_WEB, "url" to url.trim(),
                    "title" to title.trim().ifBlank { null }, "period" to period, "model" to model,
                    "project" to projectId))
                runCatching { runFeeds(only = fid) }
            }
            refresh()
        }

    fun deleteFeed(id: String) = delete(id)

    /** Look at one feed now. Its period still allows one task. Returns what happened. */
    suspend fun checkFeed(id: String): String {
        val msg = withContext(Dispatchers.IO) {
            runCatching { runFeeds(only = id)[id] }.getOrElse { it.message } ?: t("Nothing to look at")
        }
        refresh()
        return msg
    }

    @Synchronized
    private fun agent(): Pair<AgentConfig, ModelClient> {
        val cfg = agentConfig()
        agent?.let { if (cfg == agentCfg) return cfg to it; it.close() }
        agentCfg = cfg
        return cfg to Agent.clientFor(cfg).also { agent = it }
    }

    private fun health(force: Boolean = false): Health {
        val now = System.currentTimeMillis()
        lastHealth?.let { (at, h) -> if (!force && now - at < 60_000) return h }
        val (cfg, client) = agent()
        return client.health().also { h ->
            lastHealth = now to h
            // The agent's certificate is its own: the first one answering after
            // Save is trusted, and only it after that.
            if (cfg.protocol == "harness" && h.connected && cfg.tlsFingerprint == null)
                h.fingerprint?.let { store.setKv(HARNESS_FP, it) }
        }
    }

    suspend fun checkInference(): Health = withContext(Dispatchers.IO) { health(true) }
        .also { h -> _state.value = _state.value.copy(inference = h) }

    /**
     * A review of one item by the AI chosen in Settings: a summary of what
     * happened in it, from it and everything filed under it ([ItemSummary]).
     * A real answer is kept on the item with the fingerprint of what it read;
     * why there is none is shown, not kept. The user's own agent also gets the
     * items to look things up in.
     */
    suspend fun review(record: EntityRecord): String {
        val answer = withContext(Dispatchers.IO) {
            val all = store.all()
            val root = all.firstOrNull { it.id == record.id } ?: record
            val under = ItemSummary.under(root, all)
            val (cfg, client) = agent()
            Agent.ask(client, cfg, ItemSummary.prompt(root, under), items).also { a ->
                if (a.isNotBlank() && !a.startsWith("error:")) edit(root.id) {
                    it + ("review" to a.trim()) + ("review_of" to ItemSummary.fingerprint(root, under))
                }
            }
        }
        refresh()
        return answer
    }

    // ---- a backup: everything on this computer as one file ([Backup]), and back

    /** Everything here, sealed with [password] and written to [file]: how many items and media. */
    suspend fun exportTo(file: java.io.File, password: CharArray): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val records = store.all()
        file.outputStream().use { out ->
            records.size to Backup.write(out, records, System.getProperty("jpackage.app-version") ?: "dev", password) { sha ->
                store.blob(sha)?.takeIf { it.exists() }?.readBytes()
            }
        }
    }

    /** Whether the backup in [file] is sealed with a password. */
    suspend fun backupIsSealed(file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        file.inputStream().use { Backup.isSealed(it) }
    }

    /** What restoring the backup in [file] would change here. Throws [Backup.NotABackup], a wrong password. */
    suspend fun planBackup(file: java.io.File, password: CharArray?): Backup.Plan = withContext(Dispatchers.IO) {
        val contents = file.inputStream().use { Backup.read(it, password) }
        Backup.plan(contents) { store.get(it) }
    }

    /** The items [plan] takes, then the backup's media this computer lacks. Returns how many items. */
    suspend fun restoreBackup(file: java.io.File, plan: Backup.Plan, password: CharArray?): Int {
        val n = withContext(Dispatchers.IO) {
            plan.incoming.forEach { store.put(it) }
            file.inputStream().use { input -> Backup.media(input, password) { _, bytes -> store.putBlob(bytes) } }
            plan.incoming.size
        }
        refresh()
        return n
    }

    // ---- one-time import, the same logic as the phone's

    suspend fun planTodoist(token: String): Todoist.Plan = withContext(Dispatchers.IO) {
        val (projects, tasks) = Todoist.fetch(token.trim())
        val s = _state.value
        val titleOf = s.projects.associate { it.id to (it.fields["title"] ?: it.id) }
        val idBySlug = s.projects.associate { ProjectTree.slugOf(it.fields["title"] ?: it.id) to it.id }
        val all = s.nextActions + s.inbox + s.notes
        val existing = all.associate { rec ->
            val pid = ProjectTree.parentOf(rec, idBySlug)
            val project = pid?.let { titleOf[it] ?: all.firstOrNull { r -> r.id == it }
                ?.fields?.get("parent")?.let(titleOf::get) }
            (project to (rec.fields["text"] ?: rec.fields["title"] ?: "")) to rec.id
        }
        Todoist.plan(projects, tasks, titleOf.values, existing)
    }

    suspend fun importTodoist(plan: Todoist.Plan): Int = withContext(Dispatchers.IO) {
        val idByName = HashMap<String, String>()
        store.byType("project").forEach { idByName[(it.fields["title"] ?: it.id).trim().lowercase()] = it.id }
        plan.newProjects.forEach { name ->
            val pid = id("p"); write("project", pid, mapOf("title" to name, "status" to "active"))
            idByName[name.trim().lowercase()] = pid
        }
        val idByKey = HashMap<String, String>()
        plan.tasks.forEach { t ->
            val task = t.parentKey?.let { idByKey[it] ?: t.existingParent }
            idByKey[t.key] = if (t.parentKey != null && task != null) {
                id("n").also { write("note", it, mapOf("title" to t.text, "kind" to "text",
                    "body" to (t.body ?: ""), "due" to t.due, "parent" to task)) }
            } else {
                val pid = t.project?.let { idByName[it.trim().lowercase()] }
                id("t").also { write("next_action", it, mapOf("text" to t.text, "parent" to pid,
                    "project" to (t.project?.let(ProjectTree::slugOf) ?: "inbox"),
                    "due" to t.due, "body" to t.body)) }
            }
        }
        plan.tasks.size
    }.also { refresh() }

    data class CalendarPlan(val events: List<Ics.Occurrence>, val alreadyHere: Int, val unsupportedRules: Int)

    suspend fun planCalendar(url: String): CalendarPlan = withContext(Dispatchers.IO) {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val res = Ics.parse(fetchCalendarText(url), today, today.plusMonths(12), zone)
        val (here, fresh) = res.occurrences.partition { store.get(eventId(it.uid)) != null }
        CalendarPlan(fresh, here.size, res.unsupportedRules)
    }

    suspend fun importCalendar(plan: CalendarPlan): Int = withContext(Dispatchers.IO) {
        plan.events.count { o ->
            val eid = eventId(o.uid)
            if (store.get(eid) != null) return@count false
            write("event", eid, mapOf("uid" to o.uid, "summary" to o.summary, "dtstart" to o.dtstart,
                "dtend" to o.dtend, "calendar" to "google", "all_day" to o.allDay.toString(),
                "location" to o.location))
            true
        }
    }.also { refresh() }

    /** The workstation's rule, so the same event has the same id everywhere. */
    private fun eventId(uid: String) = "e_" + MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    // ---- Pro: not sold yet. "Sign up for Pro" only counts who would want it.

    /** The form the answer goes to; a test points it elsewhere. */
    internal var proForm = com.hubidubitask.core.pro.ProInterest.FORM

    /** Whether this computer has already said it wants Pro. */
    fun proWanted(): Boolean = store.kv("pro_wanted") != null

    /** Say so, once: true when the answer went through. */
    suspend fun wantPro(): Boolean = withContext(Dispatchers.IO) {
        com.hubidubitask.core.pro.ProInterest.send(
            if (com.hubidubitask.desktop.Platform.isMac) "mac" else "linux",
            // jpackage names the version to the app it launches; a run from Gradle has none.
            System.getProperty("jpackage.app-version") ?: "dev", language.value.code, proForm)
    }.also { if (it) store.setKv("pro_wanted", nowUtc()) }

    // ---- pairing

    /** "Pair a phone": show a code here and wait for it to be typed on the phone. */
    fun startPairing(): String = PeerPairing.newCode().also { server.desk.open(it) }

    /** The phone showed a code: take it, and wait for the phone to connect. */
    fun acceptCode(code: String) = server.desk.open(code)

    fun stopPairing() = server.desk.close()

    fun unpair(id: String) = io { store.removePeer(id) }
}
