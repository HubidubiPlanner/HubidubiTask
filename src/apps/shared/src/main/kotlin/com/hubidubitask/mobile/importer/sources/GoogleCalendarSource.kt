package com.hubidubitask.mobile.importer.sources

import com.hubidubitask.core.Ids
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.importer.ImportContext
import com.hubidubitask.mobile.importer.ImportIcon
import com.hubidubitask.mobile.importer.ImportInput
import com.hubidubitask.mobile.importer.ImportLine
import com.hubidubitask.mobile.importer.ImportPlan
import com.hubidubitask.mobile.importer.ImportSource
import com.hubidubitask.mobile.importer.ImportValue
import com.hubidubitask.mobile.importer.Ics
import com.hubidubitask.mobile.importer.fetchCalendarText

/** The next twelve months of a Google calendar, from its secret iCal address ([Ics]). */
object GoogleCalendarSource : ImportSource {
    override val id = "google_calendar"
    override val title = "Google Calendar"
    override val subtitle get() = t("Events from the next 12 months")
    override val icon = ImportIcon.CALENDAR
    override val input get() = ImportInput.Address(t("Secret address in iCal format"),
        t("In Google Calendar on the web: Settings → your calendar → Integrate calendar → Secret address in iCal format. Treat it like a password."))

    override fun read(value: ImportValue, here: ImportContext): ImportPlan {
        val url = (value as ImportValue.Text).text.trim()
        val res = Ics.parse(fetchCalendarText(url, here.web), here.today, here.today.plusMonths(12), here.zone)
        val (known, fresh) = res.occurrences.partition { here.has(Ids.event(it.uid)) }
        return ImportPlan(
            events = fresh, calendar = "google",
            lines = buildList {
                add(ImportLine(t("%d events in the next 12 months", fresh.size)))
                if (known.isNotEmpty()) add(ImportLine(t("%d already in the app — skipped", known.size), muted = true))
            },
            lost = buildList {
                if (res.unsupportedRules > 0)
                    add(t("%d repeating events use a rule this can't expand — only their first date comes in", res.unsupportedRules))
            },
        )
    }
}
