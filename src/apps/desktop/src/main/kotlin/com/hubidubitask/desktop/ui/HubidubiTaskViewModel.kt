package com.hubidubitask.desktop.ui

import com.hubidubitask.core.Backup
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.NewItemDue
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.AgentNames
import com.hubidubitask.core.agent.AgentPrefs
import com.hubidubitask.core.agent.AgentStore
import com.hubidubitask.core.agent.AgentType
import com.hubidubitask.core.agent.AgentTypes
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.agent.ModelClient
import com.hubidubitask.core.feed.Feeds
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.nowUtc
import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.desktop.PeerServer
import com.hubidubitask.desktop.Store
import com.hubidubitask.mobile.browse.ItemSummary
import com.hubidubitask.mobile.importer.ImportContext
import com.hubidubitask.mobile.importer.ImportPlan
import com.hubidubitask.mobile.importer.ImportSource
import com.hubidubitask.mobile.importer.ImportValue
import com.hubidubitask.mobile.importer.ImportWriter
import com.hubidubitask.mobile.tree.ProjectTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private fun id(prefix: String) = com.hubidubitask.core.Ids.new(prefix)

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
        edit(id) { f -> ProjectTree.moved(f, parentId, store.byType("project")) }
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
            edit(n.id) { f -> ProjectTree.moved(f, up, store.byType("project")) }
        }
        if (rec.type == "inbox_item") store.get(rec.id)?.let { store.put(it.tombstone(nowUtc())) }
        else edit(rec.id) { it + ("done" to "true") }
    }

    // ---- the agent, asked directly

    private var agentCfg: AgentConfig? = null
    private var agent: ModelClient? = null
    private var lastHealth: Pair<Long, Health>? = null

    /** This computer's AI settings: plain values in its store, keys and tokens in the system's secret store. */
    private val agents = AgentStore(object : AgentPrefs {
        override fun get(key: String) = store.kv(key)
        override fun set(key: String, value: String?) = store.setKv(key, value)
        override fun secret(name: String) = Secrets.get(name)
        override fun setSecret(name: String, value: String) { Secrets.put(name, value) }
    }, AgentNames.COMPUTER)

    fun agentConfig(): AgentConfig = agents.load()

    /** Keep [c] as the choice (its address, and Claude's key); tests set an agent this way. */
    fun setAgentConfig(c: AgentConfig) {
        agents.keep(AgentTypes.of(c.protocol), buildMap {
            put("endpoint", c.endpoint)
            c.apiKey?.let { put("apiKey", it) }
        })
        lastHealth = null
    }

    /** Claude's key, from the system's secret store rather than the app's database. */
    fun claudeKey(): String? = agents.claudeKey()

    /** What [type]'s fields in Settings show. */
    fun agentForm(type: AgentType): Map<String, String> = agents.form(type)

    /** Whether keeping [values] for [type] is followed by a check. */
    fun agentWillCheck(type: AgentType, values: Map<String, String>): Boolean = agents.willCheck(type, values)

    /** Keep what was typed for [type] and, when it is enough, check that it answers. */
    suspend fun setUpAgent(type: AgentType, values: Map<String, String>): AgentStore.Outcome =
        withContext(Dispatchers.IO) {
            agents.setUp(type, values) {
                lastHealth = null
                health(true).also { h -> _state.value = _state.value.copy(inference = h) }
            }
        }

    // ---- feeds: watched pages, each turned into at most one task per period

    /** Look at every feed whose period has turned, or [only] now. Returns what happened, by feed id. */
    @Synchronized
    private fun runFeeds(only: String? = null): Map<String, String> =
        com.hubidubitask.core.feed.FeedRunner.run(store, agents.load(), claudeKey(), only)

    /** Watch a [kind] as typed ([values]); the first look happens straight away and only remembers it. */
    fun addFeed(kind: com.hubidubitask.core.feed.FeedKind, values: Map<String, String>, period: String,
                projectId: String?) =
        scope.launch {
            withContext(Dispatchers.IO) {
                val fid = id("f")
                write(Feeds.TYPE, fid, Feeds.newFields(kind, values, period, projectId))
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
            // A server's certificate in the home network is its own: the first one
            // answering after Save is trusted, and only it after that. Any agent.
            if (h.connected && cfg.tlsFingerprint == null) h.fingerprint?.let { pin(cfg, it) }
        }
    }

    /** Trust on first answer: a home server's certificate, pinned from now on ([AgentStore.pin]). */
    private fun pin(cfg: AgentConfig, fingerprint: String) = agents.pin(cfg, fingerprint)

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

    /** What an import from [source] would add. Network or file reading; off the main thread. */
    suspend fun planImport(source: ImportSource, value: ImportValue): ImportPlan = withContext(Dispatchers.IO) {
        source.read(value, ImportContext(store))
    }

    /** Write [plan], every item marked as imported. */
    suspend fun applyImport(plan: ImportPlan): ImportWriter.Result = withContext(Dispatchers.IO) {
        ImportWriter.apply(plan, store)
    }.also { refresh() }

    // ---- pairing

    /** "Pair a phone": show a code here and wait for it to be typed on the phone. */
    fun startPairing(): String = PeerPairing.newCode().also { server.desk.open(it) }

    /** The phone showed a code: take it (false when it isn't a valid code), and wait for the phone. */
    fun acceptCode(code: String): Boolean = server.desk.open(code)

    fun stopPairing() = server.desk.close()

    /** Whether the code is still open, or why it isn't. */
    fun pairingState() = server.desk.state()

    /** Unpairs a phone: its sync key goes from the Keychain too. */
    fun unpair(id: String) = io { server.forget(id) }
}
