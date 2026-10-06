package com.hubidubitask.mobile.importer

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A calendar feed's text. `webcal://` is how calendar apps spell https. */
fun fetchCalendarText(url: String): String {
    val u = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")
    val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    client.newCall(Request.Builder().url(u).build()).execute().use { r ->
        if (r.code == 404 || r.code == 401 || r.code == 403)
            throw java.io.IOException("The calendar address was not accepted (${r.code}).")
        if (!r.isSuccessful) throw java.io.IOException("The calendar server answered ${r.code}.")
        val body = r.body?.string().orEmpty()
        if (!body.contains("BEGIN:VCALENDAR"))
            throw java.io.IOException("That address did not return a calendar.")
        return body
    }
}
