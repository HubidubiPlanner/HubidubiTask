package com.hubidubitask.mobile.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * The microphone as Whisper hears it: 16 kHz mono 16-bit samples, kept in
 * memory (ten minutes is 19 MB). A phone that won't record at 16 kHz is
 * recorded at 48 kHz and every three samples averaged into one. At
 * [LONGEST_S] it stops by itself and [onFull] is called.
 */
internal class PcmRecorder(private val onFull: () -> Unit) {
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private var samples = ShortArray(RATE * 30)
    private var count = 0

    /** Throws when the microphone can't be opened. Needs RECORD_AUDIO, asked for by the caller. */
    @SuppressLint("MissingPermission")
    fun start() {
        val (r, every) = open(RATE)?.let { it to 1 } ?: open(RATE * 3)?.let { it to 3 }
            ?: throw IllegalStateException("the microphone could not be opened")
        record = r
        r.startRecording()
        running = true
        reader = thread(name = "voice-pcm") { read(r, every) }
    }

    @SuppressLint("MissingPermission")
    private fun open(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        return r
    }

    private fun read(r: AudioRecord, every: Int) {
        val chunk = ShortArray(4096 * every)
        var sum = 0; var n = 0
        while (running) {
            val got = r.read(chunk, 0, chunk.size)
            if (got < 0) break
            synchronized(this) {
                for (i in 0 until got) {
                    sum += chunk[i]
                    if (++n == every) { add((sum / every).toShort()); sum = 0; n = 0 }
                }
            }
            if (count >= RATE * LONGEST_S) { running = false; Handler(Looper.getMainLooper()).post(onFull) }
        }
    }

    private fun add(s: Short) {
        if (count >= RATE * LONGEST_S) return
        if (count == samples.size) samples = samples.copyOf(minOf(samples.size * 2, RATE * LONGEST_S))
        samples[count++] = s
    }

    /** What was recorded; the microphone is let go. */
    fun stop(): ShortArray {
        running = false
        record?.let { runCatching { it.stop() } }
        reader?.join(2000)
        record?.release(); record = null
        return synchronized(this) { samples.copyOf(count) }
    }

    companion object {
        const val RATE = 16000
        /** As long as a note dictated to the phone's own recognizer may be. */
        const val LONGEST_S = 10 * 60
    }
}
