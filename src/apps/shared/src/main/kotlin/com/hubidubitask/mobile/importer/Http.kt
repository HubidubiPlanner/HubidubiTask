package com.hubidubitask.mobile.importer

import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.net.SafeFetch

/**
 * A calendar feed's text. `webcal://` is how calendar apps spell https; it goes
 * through [web], a [SafeFetch], so https only and never into the user's own network.
 */
fun fetchCalendarText(url: String, web: SafeFetch): String = web.get(url) { r ->
    if (r.code == 404 || r.code == 401 || r.code == 403)
        throw java.io.IOException(t("The calendar address was not accepted (%d).", r.code))
    if (!r.isSuccessful) throw java.io.IOException(t("The calendar server answered %d.", r.code))
    val body = r.body?.string().orEmpty()
    if (!body.contains("BEGIN:VCALENDAR"))
        throw java.io.IOException(t("That address did not return a calendar."))
    body
}
