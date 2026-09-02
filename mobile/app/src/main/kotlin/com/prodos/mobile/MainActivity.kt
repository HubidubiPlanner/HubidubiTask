package com.prodos.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.prodos.mobile.sync.SyncWorker
import com.prodos.mobile.ui.ProdosApp
import com.prodos.mobile.ui.ProdosViewModel

/**
 * The native app entry point (Phase 8). All UI is Jetpack Compose — no WebView.
 * The native shell (Room/SQLCipher mirror, OkHttp pinning, WorkManager,
 * NsdManager, Keystore, QR pairing, capture widget + share intent) is unchanged.
 */
class MainActivity : ComponentActivity() {

    private val vm: ProdosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProdosApp(vm) }
        SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Foreground trigger: refresh mirror + status, and sync if paired.
        vm.syncNow()
        vm.refresh()
    }
}
