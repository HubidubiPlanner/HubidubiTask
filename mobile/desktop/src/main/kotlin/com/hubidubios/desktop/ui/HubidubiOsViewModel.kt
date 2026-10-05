package com.hubidubios.desktop.ui

import com.hubidubios.core.EntityRecord
import com.hubidubios.core.agent.Agent
import com.hubidubios.core.agent.AgentConfig
import com.hubidubios.core.agent.Health
import com.hubidubios.core.agent.ModelClient
import com.hubidubios.core.nowUtc
import com.hubidubios.core.peer.PeerPairing
import com.hubidubios.desktop.PeerServer
import com.hubidubios.desktop.Store
import com.hubidubios.mobile.importer.Ics
import com.hubidubios.mobile.importer.Todoist
import com.hubidubios.mobile.importer.fetchCalendarText
import com.hubidubios.mobile.tree.ProjectTree
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
class HubidubiOsViewModel(val store: Store, val server: PeerServer) {

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
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = scope.launch {
        val s = withContext(Dispatchers.IO) {
            val all = store.all()
            fun t(type: String) = all.filter { it.type == type }
            val peers = store.peers()
            UiState(
                paired = peers.isNotEmpty(),
                inbox = t("inbox_item"),
                nextActions = t("next_action").filter { it.fields["done"] != "true" },
                notes = t("note"),
                projects = t("project"),
                dated = (t("next_action") + t("inbox_item") + t("note"))
                    .filter { !it.fields["due"].isNullOrBlank() && it.fields["done"] != "true" }
                    .sortedBy { it.fields["due"] },
                events = t("event"),
                inference = _state.value.inference,
                peers = peers,
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

    fun capture(text: String, due: String? = null) = io {
        val now = java.time.LocalDateTime.now()
        write("inbox_item", id("i"), mapOf("text" to text, "due" to due,
            "date" to now.toLocalDate().toString(),
            "time" to "%02d:%02d".format(now.hour, now.minute)))
    }

    fun noteText(title: String, due: String? = null) = io {
        write("note", id("n"), mapOf("title" to title, "kind" to "text", "body" to "", "due" to due))
    }

    fun addNote(parentId: String, title: String, body: String = "") = io {
        write("note", id("n"), mapOf("title" to title.trim(), "kind" to "text",
            "body" to body.trim(), "parent" to parentId))
    }

    fun createProject(title: String, parentId: String? = null) = io {
        write("project", id("p"), mapOf("title" to title.trim(), "status" to "active", "parent" to parentId))
    }

    /** As on the phone: a task filed straight into [project], skipping the inbox. */
    fun addTask(project: EntityRecord, text: String, due: String? = null) = io {
        write("next_action", id("t"), mapOf("text" to text.trim(), "parent" to project.id,
            "project" to ProjectTree.slugOf(project.fields["title"] ?: project.id), "due" to due))
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

    fun setReview(id: String, text: String?) = io { edit(id) { it + ("review" to (text ?: "")) } }

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
        val p = store.kv("agent_protocol") ?: "selfhostedai"
        return AgentConfig(
            endpoint = store.kv("agent_endpoint") ?: AgentConfig.DEFAULT_ENDPOINT,
            protocol = p,
            model = store.kv("agent_model") ?: AgentConfig.defaultModel(p),
            tlsFingerprint = store.kv("agent_fp"),
        )
    }

    fun setAgentConfig(c: AgentConfig) {
        store.setKv("agent_endpoint", c.endpoint); store.setKv("agent_protocol", c.protocol)
        store.setKv("agent_model", c.model); store.setKv("agent_fp", c.tlsFingerprint)
        lastHealth = null
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
        return agent().second.health().also { lastHealth = now to it }
    }

    suspend fun checkInference(): Health = withContext(Dispatchers.IO) { health(true) }
        .also { h -> _state.value = _state.value.copy(inference = h) }

    suspend fun ask(query: String, plain: Boolean = false): String = withContext(Dispatchers.IO) {
        val (cfg, client) = agent()
        Agent.ask(client, cfg, query, if (plain) null else chatContext(_state.value))
    }

    private fun chatContext(s: UiState): String {
        val title = s.projects.associate { it.id to (it.fields["title"] ?: it.id) }
        val out = StringBuilder("What the user has open (tasks with their project and date):\n")
        for (t in (s.nextActions + s.inbox).sortedBy { it.fields["due"] ?: "~" }) {
            val line = "- " + (t.fields["text"] ?: continue) +
                (t.fields["parent"]?.let { title[it] }?.let { " [$it]" } ?: "") +
                (t.fields["due"]?.takeIf { it.isNotBlank() }?.let { " (due $it)" } ?: "")
            if (out.length + line.length > 1500) break
            out.append(line).append('\n')
        }
        return out.toString().trimEnd()
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

    // ---- pairing

    /** "Pair a phone": show a code here and wait for it to be typed on the phone. */
    fun startPairing(): String = PeerPairing.newCode().also { server.desk.open(it) }

    /** The phone showed a code: take it, and wait for the phone to connect. */
    fun acceptCode(code: String) = server.desk.open(code)

    fun stopPairing() = server.desk.close()

    fun unpair(id: String) = io { store.removePeer(id) }
}
