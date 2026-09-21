package com.hubidubios.mobile.browse

import com.hubidubios.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rec(
    id: String = "i_1",
    type: String = "inbox_item",
    fields: Map<String, String?> = emptyMap(),
) = EntityRecord(id = id, type = type, updated = "2026-09-21T09:00:00Z", fields = fields)

class ItemDetailTest {

    @Test
    fun `a dated item reports its date`() {
        assertEquals("2026-09-25", ItemDetail.dueOf(rec(fields = mapOf("due" to "2026-09-25"))))
    }

    @Test
    fun `an undated item reports none`() {
        assertNull(ItemDetail.dueOf(rec()))
        assertNull(ItemDetail.dueOf(rec(fields = mapOf("due" to ""))))
    }

    @Test
    fun `a stored review comes back`() {
        val r = rec(fields = mapOf("review" to "Next action: call the shop."))
        assertEquals("Next action: call the shop.", ItemDetail.reviewOf(r))
        assertTrue(ItemDetail.hasReview(r))
    }

    @Test
    fun `a cleared review reads as no review`() {
        // Clearing writes an empty string rather than dropping the field, so
        // that the deletion itself has something to travel in.
        val r = rec(fields = mapOf("review" to "   "))
        assertNull(ItemDetail.reviewOf(r))
        assertFalse(ItemDetail.hasReview(r))
    }

    @Test
    fun `multi-line prose survives intact`() {
        val prose = "It is about the report.\n\nNext action: email the form."
        assertEquals(prose, ItemDetail.reviewOf(rec(fields = mapOf("review" to prose))))
    }

    @Test
    fun `media items are named by what they are`() {
        assertEquals("Photo task", ItemDetail.typeLabel(rec(fields = mapOf("kind" to "image"))))
        assertEquals("Voice note",
            ItemDetail.typeLabel(rec(type = "note", fields = mapOf("kind" to "audio"))))
        assertEquals("Document",
            ItemDetail.typeLabel(rec(type = "note", fields = mapOf("kind" to "document"))))
        assertEquals("Task", ItemDetail.typeLabel(rec()))
        assertEquals("Note", ItemDetail.typeLabel(rec(type = "note")))
    }

    @Test
    fun `an unknown type is shown readably rather than raw`() {
        assertEquals("waiting for", ItemDetail.typeLabel(rec(type = "waiting_for")))
    }

    @Test
    fun `the subtitle carries the date only when there is one`() {
        assertEquals("Task", ItemDetail.subtitle(rec()))
        assertEquals("Task · due 2026-09-25",
            ItemDetail.subtitle(rec(fields = mapOf("due" to "2026-09-25"))))
    }
}
