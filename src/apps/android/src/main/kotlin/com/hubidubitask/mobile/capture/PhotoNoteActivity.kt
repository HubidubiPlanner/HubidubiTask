package com.hubidubitask.mobile.capture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.ui.NewDue
import com.hubidubitask.mobile.ui.PhotoChoice
import com.hubidubitask.mobile.ui.UiLanguage
import java.io.File

/**
 * A note from a photo, from the home screen. The phone's camera app takes
 * it. With AI chosen in Settings ([PhotoChoice]) and an AI set up, the AI
 * writes out what it says (or, with no writing in it, what it shows) in the
 * background ([CaptureWork]), and that is saved as a text note, with the
 * date Settings gives new notes. With None, or no AI set up, the photo
 * itself is saved as a photo note, as the "+" saves it.
 */
class PhotoNoteActivity : ComponentActivity() {
    /** Where the camera writes the photo; kept across the camera, which may close this screen behind it. */
    private var shot: File? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot
        if (ok && f != null && f.exists() && f.length() > 0) keep(f) else f?.delete()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiLanguage.apply(this)
        // The home screen shows through under the status bar too, not a black strip.
        enableEdgeToEdge()
        // Back from the camera into a new copy of this screen: the result comes to it by itself.
        savedInstanceState?.getString(SHOT)?.let { shot = File(it); return }

        val f = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        shot = f
        runCatching { takePhoto.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", f)) }
            .onFailure { toast(t("No camera app on this phone.")); finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shot?.let { outState.putString(SHOT, it.path) }
    }

    /** To the AI in the background, which says how it ended; or saved as it is. */
    private fun keep(photo: File) {
        val app = applicationContext
        val due = NewDue.date(app)
        val read = PhotoChoice.load(app) == PhotoChoice.AI && AgentSettings.ready(app)
        if (read && CaptureWork.photo(app, photo, due)) { toast(t("Reading the photo…")); return }
        saveInBackground(app, t("Note saved")) {
            try { it.noteMedia("image", photo.readBytes(), "image/jpeg", t("Photo"), due) } finally { photo.delete() }
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private companion object { const val SHOT = "shot" }
}
