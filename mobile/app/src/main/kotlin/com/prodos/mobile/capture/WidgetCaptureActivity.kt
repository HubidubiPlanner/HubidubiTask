package com.prodos.mobile.capture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.prodos.mobile.sync.Sync

/**
 * Home-screen widget capture target. A single field over a translucent
 * activity: type, done — the app never opens, capture completes offline.
 */
class WidgetCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = EditText(this).apply {
            hint = "Capture…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("prodos capture")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Sync.open(applicationContext).capture(text)
                    Toast.makeText(this, "Captured", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
