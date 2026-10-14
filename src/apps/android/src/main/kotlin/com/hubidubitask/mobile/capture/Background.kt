package com.hubidubitask.mobile.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hubidubitask.mobile.sync.Sync
import kotlin.concurrent.thread

/**
 * A capture written off the main thread, where the database refuses to work,
 * on a thread that outlives the screen that asked for it: these screens close
 * the moment the words are in. [done] is said once the write has landed.
 */
internal fun saveInBackground(context: Context, done: String, write: (Sync) -> Unit) {
    val app = context.applicationContext
    thread(name = "capture") {
        val ok = runCatching { write(Sync.open(app)) }.isSuccess
        if (ok) Handler(Looper.getMainLooper()).post { Toast.makeText(app, done, Toast.LENGTH_SHORT).show() }
    }
}
