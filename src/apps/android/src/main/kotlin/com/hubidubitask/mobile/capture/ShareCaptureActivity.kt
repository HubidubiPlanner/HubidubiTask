package com.hubidubitask.mobile.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.sync.Sync

/**
 * Share-target capture. Text shared from any app lands here and goes straight
 * to the durable outbox — no WebView, no network, no validation. Capture must
 * never fail (PLAN.md principle 7).
 */
class ShareCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.hubidubitask.mobile.ui.UiLanguage.apply(this)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!text.isNullOrEmpty()) {
            Sync.open(applicationContext).capture(text)
            Toast.makeText(this, t("Captured"), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
