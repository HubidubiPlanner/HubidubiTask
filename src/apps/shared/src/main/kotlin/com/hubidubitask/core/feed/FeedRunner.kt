package com.hubidubitask.core.feed

import com.hubidubitask.core.ItemSink
import com.hubidubitask.core.ItemWriter
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.net.SafeFetch
import com.hubidubitask.core.nowUtc
import com.hubidubitask.mobile.tree.ProjectTree
import java.time.LocalDate

/**
 * Looks at feeds and saves what came of it, for the phone and the computer
 * alike. The task's wording comes from the AI chosen in Settings, or from
 * Claude when that one can run commands ([FeedWriter]), whatever a feed says.
 */
object FeedRunner {

    /**
     * Look at every feed in [sink] whose period has turned, or only [only], now.
     * Returns what happened, by feed id. A feed of a kind this version doesn't
     * know (made on a newer device) is left alone.
     */
    fun run(sink: ItemSink, chosen: AgentConfig, claudeKey: String?, only: String? = null,
            today: LocalDate = LocalDate.now()): Map<String, String> {
        val client = FeedWriter.clientFor(chosen, claudeKey)
        return try { run(sink, only, today, PageFetch.web) { client.complete(it) } } finally { client.close() }
    }

    /** Claude, with the user's key: what feeds write with when the chosen agent can run commands. */
    fun feedConfig(claudeKey: String?) = AgentConfig(endpoint = "", protocol = "claude", apiKey = claudeKey)

    /** [run] through [web], with the model's answers from [ask]; for tests. */
    internal fun run(sink: ItemSink, only: String?, today: LocalDate, web: SafeFetch,
                     ask: (List<Message>) -> String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val writer = ItemWriter(sink)
        for (feed in sink.byType(Feeds.TYPE)) {
            if (feed.deleted || (only != null && feed.id != only)) continue
            if (only == null && !Feeds.isDue(feed, today)) continue
            val kind = FeedKinds.of(feed.fields["kind"]) ?: continue
            val result = Feeds.check(feed, today, nowUtc(), { kind.read(it, web) }, ask,
                { sink.get(it) != null }, kind)
            sink.put(result.feed)
            result.task?.takeIf { sink.get(it.id) == null }?.let { t ->
                val project = t.project?.let(sink::get)?.takeIf { !it.deleted }
                // Written from a stranger's words: marked so for the user's agent.
                writer.nextAction(t.text, project?.id,
                    project?.let { ProjectTree.slugOf(it.fields["title"] ?: it.id) }, t.due, t.body,
                    id = t.id, origin = "feed")
            }
            out[feed.id] = result.message
        }
        return out
    }
}
