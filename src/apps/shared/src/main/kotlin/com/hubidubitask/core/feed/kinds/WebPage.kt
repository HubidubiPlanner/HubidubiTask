package com.hubidubitask.core.feed.kinds

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.feed.FeedField
import com.hubidubitask.core.feed.FeedIcon
import com.hubidubitask.core.feed.FeedKind
import com.hubidubitask.core.feed.PageFetch
import com.hubidubitask.core.feed.PageText
import com.hubidubitask.core.feed.Snapshot
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.net.SafeFetch

/** A web page: its readable text, one block per line ([PageText]). */
object WebPage : FeedKind {
    override val id = "web"
    override val label get() = t("A web page")
    override val icon = FeedIcon.WEB
    override val fields get() = listOf(
        FeedField("url", t("Address"), address = true),
        FeedField("title", t("Name (optional)"), optional = true),
    )

    override fun valid(values: Map<String, String>): Boolean {
        val url = values["url"].orEmpty().trim()
        return (url.startsWith("http://") || url.startsWith("https://")) && url.length > 10
    }

    override fun read(feed: EntityRecord, web: SafeFetch): Snapshot =
        snapshot(PageFetch.get(feed.fields["url"].orEmpty(), web))

    /** A page's lines and title, from its HTML. */
    fun snapshot(html: String) = Snapshot(PageText.extract(html), PageText.title(html))

    override val system =
        "You watch a web page for the user and turn what changed on it into one task. " +
        "You are given the lines that disappeared and the lines that appeared since the last look. " +
        "Reply with one line only: a task the user can act on, starting with a verb, under 100 " +
        "characters, that says what changed. Write it in the language of the page. " +
        "If the change is noise (dates, counters, ads, cookie banners, layout), reply with the single word NONE."

    override val noun = "Page"
}
