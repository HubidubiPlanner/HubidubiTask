package com.hubidubitask.core.feed

import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.net.SafeFetch
import java.util.concurrent.TimeUnit

/**
 * Fetches a page the way a browser would ask for it, capped so a huge page can't stall a phone.
 * It goes through [SafeFetch]: https only, and never into the user's own network.
 */
object PageFetch {
    internal const val LIMIT = 3L * 1024 * 1024

    /** The fetcher feeds use: https only, never into the home network. */
    val web = SafeFetch { connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS) }

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (HubidubiTask feed reader)",
        "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5",
    )

    fun get(url: String): String = get(url, web)

    /** [get] through [web]; a test hands it one that may reach its local server. */
    internal fun get(url: String, web: SafeFetch): String = web.get(url, HEADERS) { r ->
        if (!r.isSuccessful) throw java.io.IOException(t("The page answered %d.", r.code))
        val body = r.body ?: throw java.io.IOException(t("The page was empty."))
        val source = body.source()
        source.request(LIMIT)
        val bytes = source.buffer.let { b -> b.readByteArray(minOf(b.size, LIMIT)) }
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        String(bytes, charset)
    }
}
