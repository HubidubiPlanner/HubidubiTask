package com.hubidubitask.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hubidubitask.core.NoteSplit
import com.hubidubitask.core.Wav
import com.hubidubitask.core.agent.Agent
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.R
import com.hubidubitask.mobile.net.AgentSettings
import com.hubidubitask.mobile.sync.Sync
import com.hubidubitask.mobile.ui.NewDue
import com.hubidubitask.mobile.ui.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What was said becomes a saved note here, apart from the screen that heard
 * it, so closing that screen loses nothing: Android freezes an app that has
 * no screen open, and Whisper can take minutes on a slow phone. A recording
 * is written out by [Whisper]; the words are then tidied by the AI chosen in
 * Settings, when one is set up, and saved as a text note with the date
 * Settings gives new notes. When it can't be written out, the recording is
 * kept as a voice memo, and the toast says why.
 */
class VoiceNoteService : Service() {

    /** How far the note in hand has got, for the screen that handed it over. */
    sealed interface Work {
        data object Idle : Work
        data class Writing(val percent: Int) : Work
        data object Tidying : Work
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLanguage.apply(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val id = intent?.getLongExtra(ID, 0L) ?: 0L
        val text = intent?.getStringExtra(TEXT)
        val wav = intent?.getStringExtra(WAV)?.let(::File)
        val app = applicationContext
        jobs.execute {
            try { if (text != null || wav != null) note(app, text, wav) }
            catch (e: Exception) { Log.w(TAG, "a voice note failed", e) }
            finally {
                wav?.let { inWork -= it.path }
                timedOut = false
                _work.value = Work.Idle
                _finished.value = maxOf(_finished.value, id)
                main.post { stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }

    // Only a short service on Android 14 (3 minutes): past that, the note is
    // stopped and its recording kept as a memo.
    override fun onTimeout(startId: Int) = timeUp()
    override fun onTimeout(startId: Int, fgsType: Int) = timeUp()

    private fun timeUp() {
        timedOut = true
        Whisper.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foreground() {
        val n = notification()
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT == 34 -> startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else -> startForeground(NOTE, n)
        }
    }

    // The progress itself is on the screen that handed the note over; the
    // notification only says that one is being written out.
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Voice notes"), NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voice)
            .setContentTitle(t("Writing out a voice note…"))
            .setProgress(0, 0, true)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "VoiceNote"
        private const val CHANNEL = "voice_notes"
        private const val NOTE = 7301
        private const val ID = "id"
        private const val TEXT = "text"
        private const val WAV = "wav"

        private val _work = MutableStateFlow<Work>(Work.Idle)
        val work: StateFlow<Work> = _work.asStateFlow()
        private val _finished = MutableStateFlow(0L)
        /** The id of the last note handed over that is done with, saved or not. */
        val finished: StateFlow<Long> = _finished.asStateFlow()

        // One note at a time, on a thread that outlives this service if it is stopped.
        private val jobs = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-note") }
        private val inWork: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var timedOut = false

        /** Where recordings wait for Whisper. */
        fun folder(context: Context) = File(context.cacheDir, "voice").apply { mkdirs() }

        /** Hands over words already written down; false when it couldn't start (the caller saves them). */
        fun words(context: Context, id: Long, text: String) =
            start(context, Intent(context, VoiceNoteService::class.java).putExtra(ID, id).putExtra(TEXT, text))

        /** Hands over a recording in [folder]; false when it couldn't start (the caller keeps it). */
        fun recording(context: Context, id: Long, wav: File): Boolean {
            inWork += wav.path
            return start(context, Intent(context, VoiceNoteService::class.java).putExtra(ID, id).putExtra(WAV, wav.path))
                .also { if (!it) inWork -= wav.path }
        }

        private fun start(context: Context, intent: Intent) =
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start", it) }.isSuccess

        /** Recordings a crash or a kill left before they were written out, kept as voice memos. */
        fun keepLeftovers(context: Context) {
            val app = context.applicationContext
            val left = folder(app).listFiles { f -> f.extension == "wav" && f.path !in inWork }.orEmpty()
            for (f in left) { inWork += f.path; jobs.execute { try { memo(app, f, t("it was interrupted")) } finally { inWork -= f.path } } }
        }

        private fun note(app: Context, text: String?, wav: File?) {
            val due = NewDue.date(app)
            val words = text ?: wav?.let { written(app, it) } ?: return
            val note = if (words.isBlank()) null else {
                _work.value = Work.Tidying
                tidied(app, words) ?: NoteSplit.titleAndBody(words)
            }
            if (note == null) { wav?.delete(); toast(app, t("Nothing heard")); return }
            Sync.open(app).noteText(note.title, note.body, due)
            wav?.delete()
            toast(app, t("Note saved"))
        }

        /** The words in [wav], or null when it was kept as a voice memo instead. */
        private fun written(app: Context, wav: File): String? {
            val audio = runCatching { Wav.read(wav.readBytes()) }.getOrElse { wav.delete(); return null }
            if (Wav.silent(audio.samples, audio.rate)) return ""
            val why = when {
                !Whisper.available(app) -> t("speech to text failed")
                !Whisper.roomFor(app) -> t("not enough free memory")
                else -> null
            }
            if (why == null) {
                _work.value = Work.Writing(0)
                Whisper.transcribe(app, Wav.floats(audio.samples), I18n.language.code) { p ->
                    _work.value = Work.Writing(p)
                }?.let { return it }
            }
            memo(app, wav, why ?: if (timedOut) t("it took too long") else t("speech to text failed"))
            return null
        }

        /** The chosen AI's tidy version, or null when there is none or it failed. */
        private fun tidied(app: Context, words: String): NoteSplit.Note? {
            if (!AgentSettings.ready(app)) return null
            return runCatching {
                val client = Agent.clientFor(AgentSettings.load(app))
                try { Agent.tidySpeech(client, words) } finally { client.close() }
            }.onFailure { Log.i(TAG, "kept as heard: ${it.message}") }.getOrNull()
        }

        private fun memo(app: Context, wav: File, why: String) {
            runCatching { Sync.open(app).noteMedia("audio", wav.readBytes(), "audio/wav", t("Voice memo"), NewDue.date(app)) }
                .onSuccess { wav.delete(); toast(app, t("Kept as a voice memo: %s", why)) }
        }

        private fun toast(app: Context, text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}
