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
                inference = health(),
            )
        }
        _state.value = s
    }

    private fun health(): AgentClient.Health {
        val server = PairedServer.load(getApplication())
            ?: return AgentClient.Health(false, null, emptyList())
        return AgentClient(server).health()
    }

    fun capture(text: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().capture(text) }
        refresh()
    }

    // --- media notes: text / image / audio, uploaded to the workstation ---

    fun noteText(title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteText(title) }
        refresh()
    }

    fun noteImage(bytes: ByteArray, mime: String, title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("image", bytes, mime, title) }
        refresh()
    }

    fun noteAudio(bytes: ByteArray, mime: String, title: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { sync().noteMedia("audio", bytes, mime, title) }
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

    suspend fun ask(query: String): String = withContext(Dispatchers.IO) {
        val server = PairedServer.load(getApplication())
            ?: return@withContext "not paired"
        AgentClient(server).ask(query)
    }
}
