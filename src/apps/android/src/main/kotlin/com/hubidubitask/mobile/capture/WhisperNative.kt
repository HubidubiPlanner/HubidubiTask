package com.hubidubitask.mobile.capture

import android.content.res.AssetManager

/** whisper.cpp, built into the app (src/main/cpp/whisper_jni.cpp). Used only through [Whisper]. */
internal object WhisperNative {
    /** Finds ggml's CPU libraries in [nativeLibDir], the best for this phone; returns what it found, for the log. */
    @JvmStatic external fun init(nativeLibDir: String): String

    /** The model at [path] in the APK's assets; 0 when it couldn't be loaded. */
    @JvmStatic external fun load(assets: AssetManager, path: String): Long

    /** [pcm] (16 kHz mono, -1 to 1) written out in [language], as UTF-8; null when it failed or was stopped. */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, language: String, threads: Int,
                                       listener: Listener?): ByteArray?

    /** Stops a [transcribe] under way, from another thread. */
    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun free(handle: Long)

    /** Called by name from native code: kept by proguard-rules.pro. */
    fun interface Listener { fun onProgress(percent: Int) }
}
