package com.hubidubios.mobile.feed

import android.content.Context
import com.hubidubios.core.agent.Agent
import com.hubidubios.core.feed.Feeds
import com.hubidubios.core.feed.PageFetch
import com.hubidubios.mobile.net.AgentSettings
import com.hubidubios.mobile.sync.Sync
import com.hubidubios.mobile.tree.ProjectTree
import java.time.LocalDate

/**
 * Looks at the phone's feeds: every one whose period has turned, from the
 * hourly background job and when the app opens, or one on demand. The rules
 * are in core's Feeds; this only fetches, asks and saves.
 */
object FeedWork {

    /** Returns what happened to each feed looked at, by feed id. */
    @Synchronized
    fun run(context: Context, only: String? = null): Map<String, String> {
        val sync = Sync.open(context.applicationContext)
        val today = LocalDate.now()
        val out = LinkedHashMap<String, String>()
        for (feed in sync.feeds()) {
            if (only != null && feed.id != only) continue
            if (only == null && !Feeds.isDue(feed, today)) continue
            val client = Agent.clientFor(AgentSettings.configFor(context, feed.fields["model"] ?: "claude"))
            val result = try {
                Feeds.check(feed, today, com.hubidubios.core.nowUtc(), PageFetch::get,
                    { client.complete(it) }, sync::exists)
            } finally { client.close() }
            sync.putFeed(result.feed)
            result.task?.takeIf { !sync.exists(it.id) }?.let { t ->
                val project = t.project?.let(sync::get)?.takeIf { !it.deleted }
                sync.addNextAction(t.text, project?.id,
                    project?.let { ProjectTree.slugOf(it.fields["title"] ?: it.id) },
                    t.due, t.body, id = t.id)
            }
            out[feed.id] = result.message
        }
        return out
    }
}
