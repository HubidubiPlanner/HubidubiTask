package com.hubidubitask.mobile.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hubidubitask.core.NoteSplit
import com.hubidubitask.core.i18n.I18n
import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.ui.AppSheet
import com.hubidubitask.mobile.ui.HubidubiTaskTheme
import com.hubidubitask.mobile.ui.NewDue
import com.hubidubitask.mobile.ui.PrimaryButton
import com.hubidubitask.mobile.ui.SecondaryButton
import com.hubidubitask.mobile.ui.SheetTitle
import com.hubidubitask.mobile.ui.ThemeChoice
import com.hubidubitask.mobile.ui.UiLanguage
import java.io.File
import java.util.Locale

/**
 * A note said aloud, from the home screen. The phone's own speech recognizer
 * writes it down as it is said, on the phone itself (its on-device mode: the
 * audio goes nowhere), and Stop saves the words as a text note, with the
 * date Settings gives new notes. A phone that can't write speech down here
 * (no recognizer, or none for the app's language yet) records a voice memo
 * instead, as the "+" does, so nothing said is lost.
 */
class VoiceNoteActivity : ComponentActivity() {
    private enum class Phase { WAIT, LISTEN, RECORD }

    private var phase by mutableStateOf(Phase.WAIT)
    /** What the recognizer has settled on, and what it is still unsure of. */
    private var said by mutableStateOf(listOf<String>())
    private var guess by mutableStateOf("")

    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var done = false
    private var memo: AudioRecorder? = null
    private var memoNote = ""
    private val main = Handler(Looper.getMainLooper())

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else { toast(t("A voice note needs the microphone.")); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiLanguage.apply(this)
        // The home screen shows through under the status bar too, not a black strip.
        enableEdgeToEdge()
        setContent {
            HubidubiTaskTheme(ThemeChoice.load(applicationContext)) { Sheet() }
        }
        if (savedInstanceState != null) { finish(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) start()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Composable
    private fun Sheet() {
        if (phase == Phase.WAIT) return
        val c = HubidubiTaskTheme.colors
        AppSheet(onDismiss = { stop() }) {
            SheetTitle(if (phase == Phase.LISTEN) t("Listening…") else t("Recording…"))
            // Said before a word is spoken, so no one dictates into a memo expecting text.
            if (phase == Phase.RECORD) Text(memoNote, style = MaterialTheme.typography.bodyMedium,
                color = c.muted, modifier = Modifier.padding(bottom = 18.dp))
            if (phase == Phase.LISTEN) {
                val text = (said + guess).filter { it.isNotBlank() }.joinToString(" ")
                Text(text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge,
                    color = if (text.isEmpty()) c.muted else c.ink,
                    modifier = Modifier.padding(bottom = 18.dp).heightIn(min = 48.dp, max = 240.dp)
                        .verticalScroll(rememberScrollState()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(t("Discard"), { discard() }, Modifier.weight(1f))
                PrimaryButton(t("Stop"), { stop() }, Modifier.weight(1f), icon = Icons.Outlined.Stop)
            }
        }
    }

    /** The recognizer when it can write this language down here; a memo otherwise. */
    private fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            record(t("This phone can't turn speech into text here, so this is kept as a voice memo.")); return
        }
        check()
    }

    /**
     * One recognizer, asked first whether it can write the app's language
     * down on this phone, then listening. One only: a second one closed
     * beside it takes the recognizer's service down with it, mid-sentence.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun check() {
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer = r
        r.checkRecognitionSupport(intentFor(null), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = support.installedOnDeviceLanguages.firstOrNull(::ours)
                when {
                    done -> {}
                    installed != null -> listen(r, installed)
                    (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages).any(::ours) -> {
                        // Fetched for next time; said now, kept as it was said.
                        runCatching { r.triggerModelDownload(intentFor(null)) }
                        letGo()
                        record(t("The speech pack for this language is still downloading, so this is kept as a voice memo."))
                    }
                    else -> {
                        letGo()
                        record(t("This phone can't turn speech into text here, so this is kept as a voice memo."))
                    }
                }
            }

            // A recognizer that can't say what it supports is simply tried.
            override fun onError(error: Int) { if (!done) listen(r, null) }
        })
    }

    private fun letGo() { recognizer?.destroy(); recognizer = null }

    /** Whether a recognizer's language tag ("pl-PL") is the app's language. */
    private fun ours(tag: String) = Locale.forLanguageTag(tag).language == I18n.language.code

    /** The app's language as a tag the recognizer knows: this phone's region first. */
    private fun fallbackTag(): String {
        val lang = I18n.language.code
        val here = Locale.getDefault()
        if (here.language == lang && here.country.isNotEmpty()) return "$lang-${here.country}"
        return when (lang) { "en" -> "en-US"; else -> "$lang-${lang.uppercase()}" }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun intentFor(tag: String?) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag ?: fallbackTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Punctuation and capitals, so the first sentence can be the title.
        putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        // One session for the whole note, in segments, ended by Stop rather than a pause.
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, LONGEST_MS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun listen(r: SpeechRecognizer, tag: String?) {
        val intent = intentFor(tag)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) { guess = best(partialResults) }

            override fun onSegmentResults(segmentResults: Bundle) {
                best(segmentResults).takeIf { it.isNotBlank() }?.let { said = said + it }
                guess = ""
            }

            override fun onEndOfSegmentedSession() = write()

            // A recognizer that ignores segments ends at a pause: keep going until Stop.
            override fun onResults(results: Bundle?) {
                best(results).takeIf { it.isNotBlank() }?.let { said = said + it }
                guess = ""
                if (stopping) write() else r.startListening(intent)
            }

            override fun onError(error: Int) {
                when {
                    done -> {}
                    stopping -> write()
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        r.startListening(intent)
                    said.isEmpty() && guess.isEmpty() -> {
                        // It can't listen after all: what is said now is kept as sound.
                        letGo()
                        record(t("This phone can't turn speech into text here, so this is kept as a voice memo."))
                    }
                    else -> write()
                }
            }
        })
        phase = Phase.LISTEN
        r.startListening(intent)
    }

    private fun best(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    /** Stop: the last words come in, then the note is written; a recognizer that goes quiet doesn't hold it up. */
    private fun stop() {
        if (done || stopping) return
        stopping = true
        when (phase) {
            Phase.LISTEN -> {
                recognizer?.stopListening()
                main.postDelayed({ write() }, LAST_WORDS_MS)
            }
            Phase.RECORD -> keepMemo()
            Phase.WAIT -> finish()
        }
    }

    /** The words heard, as a text note. */
    private fun write() {
        if (done) return
        done = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy(); recognizer = null
        val text = (said + guess).filter { it.isNotBlank() }.joinToString(" ").trim()
        val note = NoteSplit.titleAndBody(text)
        if (note == null) toast(t("Nothing heard"))
        else {
            val due = NewDue.date(applicationContext)
            saveInBackground(this, t("Note saved")) { it.noteText(note.title, note.body, due) }
        }
        finish()
    }

    /** No recognizer here: the sound itself, as the "+" records it. */
    private fun record(why: String) {
        if (done) return
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        runCatching { AudioRecorder(f).also { it.start(); memo = it } }
            .onSuccess { memoNote = why; phase = Phase.RECORD }
            .onFailure { done = true; toast(t("The microphone could not be started.")); finish() }
    }

    private fun keepMemo() {
        if (done) return
        done = true
        val f = memo?.stop(); memo = null
        if (f != null) {
            val due = NewDue.date(applicationContext)
            saveInBackground(this, t("Note saved")) {
                try { it.noteMedia("audio", f.readBytes(), "audio/mp4", t("Voice memo"), due) } finally { f.delete() }
            }
        }
        finish()
    }

    private fun discard() {
        done = true
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        memo?.stop()?.delete(); memo = null
        finish()
    }

    // Left for another app mid-note: what was said is kept, not lost.
    override fun onStop() {
        super.onStop()
        if (!done && !isChangingConfigurations) when (phase) {
            Phase.LISTEN -> write()
            Phase.RECORD -> keepMemo()
            Phase.WAIT -> {}
        }
    }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null
        if (!done) memo?.stop()?.delete()
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private companion object {
        /** The session is held open this long at most; Stop ends it sooner. */
        const val LONGEST_MS = 10 * 60 * 1000
        /** How long Stop waits for the last words before writing what it has. */
        const val LAST_WORDS_MS = 2500L
    }
}
