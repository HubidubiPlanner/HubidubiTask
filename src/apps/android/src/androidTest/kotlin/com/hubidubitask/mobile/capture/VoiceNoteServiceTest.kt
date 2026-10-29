package com.hubidubitask.mobile.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubidubitask.mobile.sync.Sync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A recording handed to the service comes out as a text note, written by Whisper; the recording goes. */
@RunWith(AndroidJUnit4::class)
class VoiceNoteServiceTest {
    @Test fun aRecordingBecomesATextNote() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("jfk.wav").use { it.readBytes() }
        val id = System.currentTimeMillis()
        val wav = File(VoiceNoteService.folder(app), "voice_$id.wav").apply { writeBytes(bytes) }
        val before = Sync.open(app).notes().map { it.id }.toSet()

        assertTrue("the service did not start", VoiceNoteService.recording(app, id, wav))
        val deadline = System.currentTimeMillis() + 180_000
        while (VoiceNoteService.finished.value < id && System.currentTimeMillis() < deadline) Thread.sleep(200)

        val made = Sync.open(app).notes().filter { it.id !in before }
        assertTrue("no note: $made", made.size == 1)
        val f = made.single().fields
        val text = "${f["title"]} ${f["body"]}".lowercase()
        assertTrue(text, "country" in text)
        assertTrue(f.toString(), f["kind"].isNullOrEmpty() || f["kind"] == "text")
        assertFalse("the recording was left behind", wav.exists())
    }
}
