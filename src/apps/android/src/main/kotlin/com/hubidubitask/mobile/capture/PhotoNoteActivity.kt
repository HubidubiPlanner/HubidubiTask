package com.hubidubitask.mobile.capture

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync
import com.hubidubitask.mobile.ui.AppSheet
import com.hubidubitask.mobile.ui.HubidubiTaskTheme
import com.hubidubitask.mobile.ui.NewDue
import com.hubidubitask.mobile.ui.SheetTitle
import com.hubidubitask.mobile.ui.ThemeChoice
import com.hubidubitask.mobile.ui.UiLanguage
import java.io.File
import kotlin.concurrent.thread

/**
 * A note from a photo, from the home screen. The phone's camera app takes
 * it; the AI agent chosen in Settings writes out what it says (or, with no
 * writing in it, what it shows), and that is saved as a text note at once,
 * with the date Settings gives new notes. Only the photo goes to the agent,
 * never the user's items, and nothing it answers is done: it is only words.
 * When the agent can't read it, the photo itself is kept as a photo note.
 */
class PhotoNoteActivity : ComponentActivity() {
    private var reading by mutableStateOf(false)
    /** Where the camera writes the photo; kept across the camera, which may close this screen behind it. */
    private var shot: File? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot
        if (ok && f != null && f.exists() && f.length() > 0) read(f) else { f?.delete(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiLanguage.apply(this)
        // The home screen shows through under the status bar too, not a black strip.
        enableEdgeToEdge()
        setContent {
            HubidubiTaskTheme(ThemeChoice.load(applicationContext)) {
                // Closing this only hides it: the note is still written, and said when it is.
                if (reading) AppSheet(onDismiss = { finish() }) {
                    SheetTitle(t("Reading the photo…"))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        color = HubidubiTaskTheme.colors.accent, trackColor = HubidubiTaskTheme.colors.line)
                }
            }
        }
        // Back from the camera into a new copy of this screen: the result comes to it by itself.
        savedInstanceState?.getString(SHOT)?.let { shot = File(it); return }

        val store = AgentSettings.store(this)
        val type = store.type()
        if (!type.ready(store.form(type))) {
            toast(t("AI not connected. Set it up in Settings.")); finish(); return
        }
        val f = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        shot = f
        runCatching { takePhoto.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f)) }
            .onFailure { toast(t("No camera app on this phone.")); finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shot?.let { outState.putString(SHOT, it.path) }
    }

    /** Off to the agent, on a thread that outlives this screen; the toast says how it ended. */
    private fun read(photo: File) {
        reading = true
        val app = applicationContext
        val due = NewDue.date(app)
        val config = AgentSettings.load(app)
        thread(name = "photo-note") {
            val bytes = runCatching { PhotoShrink.of(photo) }.getOrElse { photo.readBytes() }
            photo.delete()
            val note = runCatching {
                val client = Agent.clientFor(config)
                try { Agent.readPhoto(client, config, bytes, "image/jpeg") } finally { client.close() }
            }
            val said = runCatching {
                val sync = Sync.open(app)
                note.fold(
                    onSuccess = { sync.noteText(it.title, it.body, due); t("Note saved") },
                    onFailure = { e ->
                        sync.noteMedia("image", bytes, "image/jpeg", t("Photo"), due)
                        t("Kept as a photo note: %s", e.message ?: t("the agent could not be reached"))
                    },
                )
            }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                said?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
                if (!isDestroyed) finish()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private companion object { const val SHOT = "shot" }
}
