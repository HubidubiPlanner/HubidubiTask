package com.hubidubitask.mobile.importer

import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.net.SafeFetch
import java.util.concurrent.TimeUnit

private val calendarWeb = SafeFetch { callTimeout(60, TimeUnit.SECONDS) }

/**
 * A calendar feed's text. `webcal://` is how calendar apps spell https; it goes
 * through [SafeFetch], so https only and never into the user's own network.
 */
fun fetchCalendarText(url: String): String = fetchCalendarText(url, calendarWeb)

/** [fetchCalendarText] through [web]; a test hands it one that may reach its local server. */
internal fun fetchCalendarText(url: String, web: SafeFetch): String = web.get(url) { r ->
    if (r.code == 404 || r.code == 401 || r.code == 403)
        throw java.io.IOException(t("The calendar address was not accepted (%d).", r.code))
    if (!r.isSuccessful) throw java.io.IOException(t("The calendar server answered %d.", r.code))
    val body = r.body?.string().orEmpty()
    if (!body.contains("BEGIN:VCALENDAR"))
        throw java.io.IOException(t("That address did not return a calendar."))
    body
}
