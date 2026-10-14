package com.hubidubitask.mobile.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.ui.HubidubiTaskTheme
import com.hubidubitask.mobile.ui.NewDue
import com.hubidubitask.mobile.ui.NewNoteSheet
import com.hubidubitask.mobile.ui.UiLanguage

/**
 * A note straight from the home screen. The "New note" shortcut and widget
 * open the app's own note sheet over whatever is there, the cursor already in
 * it; the note goes to the outbox like any capture, with the date Settings
 * gives new notes, and the app itself never opens.
 */
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiLanguage.apply(this)
        // The home screen shows through under the status bar too, not a black strip.
        enableEdgeToEdge()
        setContent {
            HubidubiTaskTheme {
                NewNoteSheet(true, onSave = { title, body ->
                    val due = NewDue.date(applicationContext)
                    saveInBackground(this, t("Note saved")) { it.noteText(title, body, due) }
                }, onDismiss = { finish() })
            }
        }
    }
}
