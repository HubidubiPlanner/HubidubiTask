package com.hubidubios.mobile.capture

import android.media.MediaRecorder
import java.io.File

/**
 * Minimal AAC/m4a voice-memo recorder for audio notes. Writes an MPEG-4 file the
 * app then reads back and uploads as a media note.
 */
class AudioRecorder(val outputFile: File) {
    private var recorder: MediaRecorder? = null

    fun start() {
        @Suppress("DEPRECATION")  // Context ctor is 31+; no-arg works from 26
        val r = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recorder = r
    }

    /** Stop and return the finished file, or null if the recording was too short
     *  / failed. Always releases the recorder. */
    fun stop(): File? = try {
        recorder?.apply { stop(); release() }
        recorder = null
        outputFile.takeIf { it.exists() && it.length() > 0 }
    } catch (_: Exception) {
        runCatching { recorder?.release() }
        recorder = null
        outputFile.delete()
        null
    }
}
