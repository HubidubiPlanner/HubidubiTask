package com.hubidubitask.mobile.feed

import android.content.Context
import com.hubidubitask.core.feed.FeedRunner
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync

/**
 * Looks at the phone's feeds: every one whose period has turned, from the
 * hourly background job and when the app opens, or one on demand. The rules
 * are in core's Feeds and FeedRunner; who writes the task, in FeedWriter.
 */
object FeedWork {

    /** Returns what happened to each feed looked at, by feed id. */
    @Synchronized
    fun run(context: Context, only: String? = null): Map<String, String> {
        com.hubidubitask.mobile.ui.UiLanguage.apply(context)
        val sync = Sync.open(context.applicationContext)
        return FeedRunner.run(sync.store, AgentSettings.load(context), AgentSettings.claudeKey(context), only)
    }
}
