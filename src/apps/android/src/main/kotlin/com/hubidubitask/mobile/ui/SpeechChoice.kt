package com.hubidubitask.mobile.ui

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * What writes a voice note down, chosen in Settings and kept on this phone:
 * Android's own speech recognizer, on the phone, the words showing as they
 * are said; or Whisper, inside the app, the words after Stop. Where Android
 * can't (no on-device recognizer, or none yet for the app's language),
 * Whisper writes it down instead.
 */
object SpeechChoice {
    const val ANDROID = "android"
    const val WHISPER = "whisper"
    /** Each by its own name. */
    val OPTIONS = listOf(ANDROID to "Android", WHISPER to "Whisper")

    private const val PREFS = "ui"
    private const val KEY = "speech"

    fun load(context: Context): String =
        if (prefs(context).getString(KEY, null) == WHISPER) WHISPER else ANDROID

    fun save(context: Context, choice: String) {
        prefs(context).edit().putString(KEY, if (choice == WHISPER) WHISPER else ANDROID).apply()
    }

    /** Whether this phone has Android's recognizer working on the phone itself (Android 13 on). */
    fun androidAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
