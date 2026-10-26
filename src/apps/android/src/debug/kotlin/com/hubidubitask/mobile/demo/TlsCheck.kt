package com.hubidubitask.mobile.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Which key exchange the app's TLS really agrees, as Cloudflare saw it
 * (`kex=X25519MLKEM768` is post-quantum). Debug builds only:
 *
 *     adb shell am broadcast -a com.hubidubitask.mobile.TLS_CHECK -p <applicationId>
 *     adb logcat -s TlsCheck
 */
class TlsCheck : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                // --es url <address> checks one more that answers like Cloudflare's trace.
                // The computer's side is the desktop test: ./gradlew :desktop:pqTlsTest.
                val urls = listOf("https://pq.cloudflareresearch.com/cdn-cgi/trace", "https://api.anthropic.com/cdn-cgi/trace") +
                    listOfNotNull(intent.getStringExtra("url"))
                for (url in urls) {
                    val line = runCatching {
                        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                            r.body!!.string().lines().filter { it.startsWith("kex=") || it.startsWith("tls=") }.joinToString(" ")
                        }
                    }.getOrElse { "failed: $it" }
                    Log.i("TlsCheck", "$url $line")
                }
            } finally { done.finish() }
        }.start()
    }
}
