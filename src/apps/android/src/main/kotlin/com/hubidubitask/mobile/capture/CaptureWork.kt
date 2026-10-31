package com.hubidubitask.mobile.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hubidubitask.core.NoteSplit
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync
import com.hubidubitask.mobile.ui.UiLanguage
import java.io.File

/**
 * The AI's part of a note from a home-screen widget, as a background job:
 * a few seconds that leaving the screen must not cut short, and a job is
 * what Android lets run then, with no notification and no permission. The
 * words Android's recognizer heard are tidied by the AI chosen in Settings;
 * a photo is written out by it. The note is saved with the date it was
 * given when it was taken. When the AI can't, the words are kept as heard
 * and the photo as a photo note. Only that note goes to the AI, never the
 * user's items, and nothing it answers is acted on.
 */
class CaptureWork(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        UiLanguage.apply(app)
        val file = inputData.getString(FILE)?.let(::File)?.takeIf { it.exists() } ?: return Result.success()
        val due = inputData.getString(DUE)
        val said = runCatching {
            when (inputData.getString(KIND)) {
                WORDS -> words(file.readText(), due)
                else -> photo(file, due)
            }
        }.getOrElse {
            // Not saved: the file stays for another try, a few at most.
            Log.w(TAG, "a note from a widget wasn't saved", it)
            if (runAttemptCount < 2) return Result.retry()
            null
        }
        file.delete()
        said?.let { Handler(Looper.getMainLooper()).post { Toast.makeText(app, it, Toast.LENGTH_LONG).show() } }
        return Result.success()
    }

    private fun words(heard: String, due: String?): String {
        val note = tidied(heard) ?: NoteSplit.titleAndBody(heard) ?: return t("Nothing heard")
        Sync.open(applicationContext).noteText(note.title, note.body, due)
        return t("Note saved")
    }

    /** The chosen AI's tidy version, or null when there is none or it failed. */
    private fun tidied(heard: String): NoteSplit.Note? {
        val app = applicationContext
        if (!AgentSettings.ready(app)) return null
        return runCatching {
            val client = Agent.clientFor(AgentSettings.load(app))
            try { Agent.tidySpeech(client, heard) } finally { client.close() }
        }.onFailure { Log.i(TAG, "kept as heard: ${it.message}") }.getOrNull()
    }

    private fun photo(shot: File, due: String?): String {
        val app = applicationContext
        val bytes = runCatching { PhotoShrink.of(shot) }.getOrElse { shot.readBytes() }
        val config = AgentSettings.load(app)
        val note = runCatching {
            val client = Agent.clientFor(config)
            try { Agent.readPhoto(client, config, bytes, "image/jpeg") } finally { client.close() }
        }
        val sync = Sync.open(app)
        return note.fold(
            onSuccess = { sync.noteText(it.title, it.body, due); t("Note saved") },
            onFailure = { e ->
                sync.noteMedia("image", bytes, "image/jpeg", t("Photo"), due)
                t("Kept as a photo note: %s", e.message ?: t("the agent could not be reached"))
            },
        )
    }

    companion object {
        private const val TAG = "CaptureWork"
        private const val KIND = "kind"
        private const val FILE = "file"
        private const val DUE = "due"
        private const val WORDS = "words"
        private const val PHOTO = "photo"

        /** Words heard, to be tidied and saved; false when the job couldn't be set (the caller saves them). */
        fun words(context: Context, text: String, due: String?): Boolean {
            val f = File(File(context.cacheDir, "capture").apply { mkdirs() }, "words_${System.nanoTime()}.txt")
            return runCatching { f.writeText(text) }.isSuccess && enqueue(context, WORDS, f, due)
                .also { if (!it) f.delete() }
        }

        /** A photo in the app's cache, to be written out and saved; false when the job couldn't be set. */
        fun photo(context: Context, shot: File, due: String?): Boolean = enqueue(context, PHOTO, shot, due)

        private fun enqueue(context: Context, kind: String, file: File, due: String?): Boolean = runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<CaptureWork>()
                    .setInputData(workDataOf(KIND to kind, FILE to file.path, DUE to due))
                    .build())
        }.onFailure { Log.w(TAG, "could not start", it) }.isSuccess
    }
}
