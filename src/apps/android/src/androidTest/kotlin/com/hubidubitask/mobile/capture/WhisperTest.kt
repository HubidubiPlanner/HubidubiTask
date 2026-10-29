package com.hubidubitask.mobile.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubidubitask.core.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Whisper as the app ships it, on a phone or emulator: whisper.cpp's own JFK sample, written out. */
@RunWith(AndroidJUnit4::class)
class WhisperTest {
    @Test fun writesOutASpeechInEnglish() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val audio = Wav.read(bytes)
        assertEquals(16000, audio.rate)
        assertTrue("whisper.cpp or its model is missing", Whisper.available(app))

        val heard = mutableListOf<Int>()
        val text = Whisper.transcribe(app, Wav.floats(audio.samples), "en") { heard += it }
        assertNotNull(text)
        assertTrue(text, text!!.lowercase().contains("ask not what your country can do for you"))
        assertTrue("no progress was said", heard.isNotEmpty())
    }
}
