package com.hubidubios.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hubidubios.core.EntityRecord
import com.hubidubios.mobile.net.AgentClient
import com.hubidubios.mobile.net.PairedServer
import com.hubidubios.mobile.sync.Sync
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
        val inference: AgentClient.Health = AgentClient.Health(false, null, emptyList()),
        val inbox: List<EntityRecord> = emptyList(),
        val nextActions: List<EntityRecord> = emptyList(),
        val staleProjects: List<EntityRecord> = emptyList(),
        val notes: List<EntityRecord> = emptyList(),
        val projects: List<EntityRecord> = emptyList(),
        /** Everything carrying a date, soonest first — what Next shows. */
        val dated: List<EntityRecord> = emptyList(),
        /** Host of the paired workstation, to propose a default endpoint. */
        val workstationHost: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

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
                inference = health(),
                workstationHost = PairedServer.load(getApplication())?.host,
            )
        }
        _state.value = s
    }

    private fun health(): AgentClient.Health {
        val server = PairedServer.load(getApplication())
            ?: return AgentClient.Health(false, null, emptyList())
        return AgentClient(server).health()
    }

    /** The inference settings held by the workstation, for the settings form. */
    suspend fun agentConfig(): AgentClient.AgentConfig? = withContext(Dispatchers.IO) {
        PairedServer.load(getApplication())?.let { AgentClient(it).config() }
    }

    /** Re-probe the inference server and report what came back. */
    suspend fun checkInference(): AgentClient.Health = withContext(Dispatchers.IO) { health() }

    /** Point the agent at an inference server. Returns null on success. */
    suspend fun setAgentConfig(endpoint: String, protocol: String, model: String?): String? =
        withContext(Dispatchers.IO) {
            val server = PairedServer.load(getApplication())
                ?: return@withContext "pair with a workstation first"
            AgentClient(server).setConfig(endpoint.trim(), protocol, model)
                ?: run { refresh(); null }
        }

    fun capture(text: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().capture(text, due) }
        refresh()
    }

    // --- media notes: text / image / audio, uploaded to the workstation ---

    fun noteText(title: String, due: String? = null) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteText(title, due = due) }
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

    /** Re-nest an item in the Projects tree. */
    fun move(id: String, parentId: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().moveTo(id, parentId) }
        refresh()
    }

    fun markDone(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().markDone(id) }
        refresh()
    }

    fun syncNow() = viewModelScope.launch {
        _state.value = _state.value.copy(syncing = true)
        withContext(Dispatchers.IO) { runCatching { sync().syncNow() } }
        refresh()
        _state.value = _state.value.copy(syncing = false)
    }

    suspend fun ask(query: String, plain: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val server = PairedServer.load(getApplication())
                ?: return@withContext "not paired"
            AgentClient(server).ask(query, plain)
        }
}
