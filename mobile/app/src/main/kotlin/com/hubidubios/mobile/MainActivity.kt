package com.hubidubios.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hubidubios.mobile.sync.SyncWorker
import com.hubidubios.mobile.ui.HubidubiOsApp
import com.hubidubios.mobile.ui.HubidubiOsViewModel

/**
 * The native app entry point (Phase 8). All UI is Jetpack Compose — no WebView.
 * The native shell (Room/SQLCipher mirror, OkHttp pinning, WorkManager,
 * NsdManager, Keystore, QR pairing, capture widget + share intent) is unchanged.
 */
class MainActivity : ComponentActivity() {

    private val vm: HubidubiOsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page colour runs under the status bar instead of stopping at a
        // grey system strip; Scaffold pads the content back out of it.
        enableEdgeToEdge()
        setContent { HubidubiOsApp(vm) }
        SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Foreground trigger: refresh mirror + status, and sync if paired.
        vm.syncNow()
        vm.refresh()
    }
}
