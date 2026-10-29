package com.hubidubitask.mobile.capture

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Speech to text inside the app: whisper.cpp with OpenAI's Whisper small,
 * multilingual, at 4 bits, read from the APK's assets. Nothing leaves the
 * phone. One model at a time, loaded for a note and freed after it: it holds
 * a few hundred MB while it works.
 */
internal object Whisper {
    const val MODEL = "whisper/ggml-small-q4_k.bin"
    private const val TAG = "Whisper"
    /** About what loading and running it takes, model and buffers. */
    private const val NEEDS = 600L shl 20

    private val library: Boolean by lazy {
        runCatching { System.loadLibrary("hubidubi_whisper") }
            .onFailure { Log.w(TAG, "whisper.cpp is not in this build", it) }.isSuccess
    }
    @Volatile private var started = false
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper-load") }
    private val lock = Any()
    /** The model loading or loaded, as a native handle (0: it failed). */
    private var model: Future<Long>? = null
    @Volatile private var busy = 0L

    /** Whether it can run here at all: its library loaded and the model in the APK. */
    fun available(context: Context): Boolean = library &&
        runCatching { context.assets.list("whisper")?.contains(MODEL.substringAfter('/')) == true }.getOrDefault(false)

    /** Whether there is the memory for it now. */
    fun roomFor(context: Context): Boolean {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return !mi.lowMemory && mi.availMem >= NEEDS
    }

    /** Starts loading the model, so it is ready when the recording ends. */
    fun preload(context: Context) {
        if (!available(context)) return
        val app = context.applicationContext
        synchronized(lock) {
            if (model == null) model = loader.submit<Long> {
                if (!started) { Log.i(TAG, WhisperNative.init(app.applicationInfo.nativeLibraryDir)); started = true }
                WhisperNative.load(app.assets, MODEL)
            }
        }
    }

    /**
     * [pcm] (16 kHz mono) as words in [language] ("pl"), or null when it
     * failed or was [cancel]led. [progress] hears 0 to 100 as it goes.
     * Blocks for as long as it takes: seconds to minutes.
     */
    fun transcribe(context: Context, pcm: FloatArray, language: String, progress: (Int) -> Unit): String? {
        preload(context)
        val handle = runCatching { synchronized(lock) { model }?.get() }.getOrNull() ?: 0L
        if (handle == 0L) { release(); return null }
        busy = handle
        return try {
            WhisperNative.transcribe(handle, pcm, language, threads()) { progress(it) }?.decodeToString()?.trim()
        } finally {
            busy = 0L
            release()
        }
    }

    /** Stops the note being written out, from another thread. */
    fun cancel() { busy.takeIf { it != 0L }?.let(WhisperNative::cancel) }

    /** Frees the model, unless a note is being written out with it (that frees it after). */
    fun release() {
        if (busy != 0L) return
        val m = synchronized(lock) { model.also { model = null } } ?: return
        loader.execute { runCatching { m.get() }.getOrNull()?.takeIf { it != 0L }?.let(WhisperNative::free) }
    }

    /** The big cores, more or less: half of them all, 2 to 4. */
    private fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
