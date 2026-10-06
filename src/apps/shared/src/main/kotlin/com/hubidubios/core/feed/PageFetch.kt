package com.hubidubios.core.feed

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches a page the way a browser would ask for it, capped so a huge page can't stall a phone. */
object PageFetch {
    private const val LIMIT = 3L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).build()

    fun get(url: String): String {
        val req = Request.Builder().url(url.trim())
            .header("User-Agent", "Mozilla/5.0 (HubidubiOS feed reader)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("the page answered ${r.code}")
            val body = r.body ?: throw java.io.IOException("the page was empty")
            val source = body.source()
            source.request(LIMIT)
            val bytes = source.buffer.let { b -> b.readByteArray(minOf(b.size, LIMIT)) }
            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
            return String(bytes, charset)
        }
    }
}
