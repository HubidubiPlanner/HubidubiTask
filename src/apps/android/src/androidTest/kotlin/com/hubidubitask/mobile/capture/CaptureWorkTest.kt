package com.hubidubitask.mobile.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hubidubitask.mobile.sync.Sync
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The widgets' background job, on a phone with no AI set up: the words are
 * saved as they were heard, and a photo is kept as a photo note.
 */
@RunWith(AndroidJUnit4::class)
class CaptureWorkTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun waitFor(what: () -> Boolean): Boolean {
        repeat(120) { if (what()) return true; Thread.sleep(500) }
        return false
    }

    @Test
    fun words_are_saved_as_heard_without_an_ai() {
        val words = "Buy seeds for the garden ${System.nanoTime()}"
        assertTrue(CaptureWork.words(app, words, null))
        assertTrue(waitFor { Sync.open(app).notes().any { (it.fields["title"] + it.fields["body"]).contains(words) } })
    }

    @Test
    fun a_photo_the_ai_cant_read_is_kept() {
        val sync = Sync.open(app)
        val before = sync.notes().count { it.fields["kind"] == "image" }
        val shot = File(app.cacheDir, "cam_test.jpg")
        Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, shot.outputStream())
        assertTrue(CaptureWork.photo(app, shot, null))
        assertTrue(waitFor { sync.notes().count { it.fields["kind"] == "image" } > before })
        assertTrue(waitFor { !shot.exists() })
    }
}
