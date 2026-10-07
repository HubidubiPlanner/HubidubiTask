package com.hubidubitask.mobile.capture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.sync.Sync

/**
 * Home-screen widget capture target. A single field over a translucent
 * activity: type, done — the app never opens, capture completes offline.
 */
class WidgetCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.hubidubitask.mobile.ui.UiLanguage.apply(this)
        val input = EditText(this).apply {
            hint = t("Capture…")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Capture"))
            .setView(input)
            .setPositiveButton(t("Save")) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Sync.open(applicationContext).capture(text)
                    Toast.makeText(this, t("Captured"), Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
