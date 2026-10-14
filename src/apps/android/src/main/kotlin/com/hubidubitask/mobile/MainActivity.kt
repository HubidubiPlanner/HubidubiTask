package com.hubidubitask.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hubidubitask.mobile.sync.SyncWorker
import com.hubidubitask.mobile.ui.HubidubiTaskApp
import com.hubidubitask.mobile.ui.HubidubiTaskViewModel

/**
 * The native app entry point (Phase 8). All UI is Jetpack Compose — no WebView.
 * The native shell (Room/SQLCipher mirror, OkHttp pinning, WorkManager,
 * NsdManager, Keystore, QR pairing, capture widget + share intent) is unchanged.
 */
class MainActivity : ComponentActivity() {

    private val vm: HubidubiTaskViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.hubidubitask.mobile.ui.UiLanguage.apply(this)
        // The page colour runs under the status bar instead of stopping at a
        // grey system strip; Scaffold pads the content back out of it.
        enableEdgeToEdge()
        setContent { HubidubiTaskApp(vm) }
        SyncWorker.schedule(this)
        com.hubidubitask.mobile.capture.NoteShortcut.publish(this)
    }

    override fun onResume() {
        super.onResume()
        // Foreground trigger: refresh mirror + status, and sync if paired.
        vm.syncNow()
        vm.refresh()
    }
}
