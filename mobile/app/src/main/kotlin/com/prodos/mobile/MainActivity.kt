package com.prodos.mobile

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.prodos.mobile.sync.Sync
import com.prodos.mobile.sync.SyncWorker
import com.prodos.mobile.web.ProdosWebView

/**
 * The native shell's host activity. Owns the WebView (all UI renders here) and
 * triggers a sync on foreground. Discovery/pairing/settings are reached from
 * the web UI via the bridge; capture also has native entry points (widget and
 * share intent) that never touch this activity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val bundlesDir = java.io.File(filesDir, "plugin-bundles").apply { mkdirs() }
        ProdosWebView.configure(webView, bundlesDir.absolutePath)
        // The WebMessagePort bridge is established from the page once loaded;
        // see web/host.js posting a channel the native side adopts.
        webView.loadUrl(ProdosWebView.coreUiUrl().toString())

        SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Foreground trigger. Off the main thread in the real build; kept
        // simple here — syncNow no-ops when unpaired.
        Thread { runCatching { Sync.open(applicationContext).syncNow() } }.start()
    }
}
