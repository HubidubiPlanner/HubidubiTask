package com.hubidubitask.mobile.ui

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * What the Voice note widget does with what is said, chosen in Settings and
 * kept on this phone: Android's own speech recognizer writes it down on the
 * phone, the words showing as they are said; or None, and it is kept as a
 * voice memo to play back. Where Android can't (no on-device recognizer, or
 * none yet for the app's language), it is kept as a voice memo too.
 */
object SpeechChoice {
    const val ANDROID = "android"
    const val NONE = "none"
    /** Android by its own name; None in the app's language. */
    val OPTIONS = listOf(ANDROID to "Android", NONE to "None")

    private const val PREFS = "ui"
    private const val KEY = "speech"

    // 3.2.0 had Whisper here, now gone: a voice memo is the nearest to it.
    fun load(context: Context): String =
        when (prefs(context).getString(KEY, null)) { NONE, "whisper" -> NONE; else -> ANDROID }

    fun save(context: Context, choice: String) {
        prefs(context).edit().putString(KEY, if (choice == NONE) NONE else ANDROID).apply()
    }

    /** Whether this phone has Android's recognizer working on the phone itself (Android 13 on). */
    fun androidAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
