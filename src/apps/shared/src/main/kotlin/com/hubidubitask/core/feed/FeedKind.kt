package com.hubidubitask.core.feed

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.feed.kinds.WebPage
import com.hubidubitask.core.net.SafeFetch

/**
 * A kind of thing a feed can watch. The + on Feeds lists [FeedKinds.all] on
 * the phone and the computer; the sheet that adds one asks for its [fields].
 *
 * A kind only reads. [read] fetches through the [SafeFetch] it is handed
 * (https only, never into the home network) and turns what it got into
 * stable lines, so noise does not look like a change. What changed always
 * goes to Claude, never to the user's own agent: a feed's text is a
 * stranger's, and that agent can run commands (see [FeedRunner]).
 *
 * Texts shown to the user are getters that translate (t); [system] is the
 * model's instruction and stays in English.
 */
interface FeedKind {
    /** Stored on each feed as "kind"; never changes. */
    val id: String
    /** Its name in the + list and as the add sheet's title. */
    val label: String
    val icon: FeedIcon
    /**
     * What the add sheet asks for, in order. The address is always stored
     * as "url" and the name as "title": the list and the feed's sheet show them.
     */
    val fields: List<FeedField>

    /** Whether what was typed ([values], by field key) is enough to watch. */
    fun valid(values: Map<String, String>): Boolean

    /** What [feed] shows now, as lines. Network through [web] only; off the main thread. */
    fun read(feed: EntityRecord, web: SafeFetch): Snapshot

    /** What the model is told it is looking at, and how to answer. */
    val system: String

    /** What the prompt calls one of these, e.g. "Page". */
    val noun: String
}

/** One thing the add sheet asks for. [address] gets a web-address keyboard. */
data class FeedField(val key: String, val label: String, val optional: Boolean = false,
                     val address: Boolean = false)

/** What one look found: [text] as stable lines, and the source's own name when it has one. */
data class Snapshot(val text: String, val title: String?)

/** The icon next to a kind in the + list; each app draws its own. */
enum class FeedIcon { WEB, NEWS }

/** Every kind of feed, in the order the + lists them. */
object FeedKinds {
    val all: List<FeedKind> = listOf(WebPage)

    /** The kind stored on a feed: none means a web page (the first kind); one this version doesn't know, null. */
    fun of(id: String?): FeedKind? = if (id.isNullOrBlank()) WebPage else all.firstOrNull { it.id == id }
}
