package com.hubidubitask.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Words from a photo or a voice note, made into a title and a body. */
class NoteSplitTest {
    private fun split(text: String) = NoteSplit.titleAndBody(text)

    @Test fun `the first line is the title and the rest the body`() {
        assertEquals(NoteSplit.Note("Shopping", "Milk\nBread"), split("Shopping\n\nMilk\nBread\n"))
    }

    @Test fun `markup a model puts on the title goes`() {
        assertEquals("Shopping", split("# Shopping\nMilk")!!.title)
        assertEquals("Shopping", split("**Shopping**\nMilk")!!.title)
        assertEquals("Shopping", split("Title: Shopping\nMilk")!!.title)
        assertEquals("Shopping", split("```\nShopping\nMilk\n```")!!.title)
    }

    @Test fun `a short line alone is a title with no body`() {
        assertEquals(NoteSplit.Note("kup mleko", ""), split("  kup mleko  "))
    }

    @Test fun `long dictation gives its first sentence as the title`() {
        val said = "Call the bank about the card. Then ask whether the old one still works " +
            "for the payments that are already set up and when the new one comes."
        assertEquals(NoteSplit.Note("Call the bank about the card",
            "Then ask whether the old one still works for the payments that are already set up and when the new one comes."),
            split(said))
    }

    @Test fun `long dictation with no short sentence keeps all of it below its first words`() {
        val said = "call the bank about the card and then ask whether the old one still works for " +
            "the payments that are already set up"
        val note = split(said)!!
        assertEquals(said, note.body)
        assert(note.title.endsWith("…") && note.title.length <= NoteSplit.MAX_TITLE + 1)
        assert(said.startsWith(note.title.removeSuffix("…")))
    }

    @Test fun `no words is no note`() {
        assertNull(split(""))
        assertNull(split("  \n \n"))
        assertNull(split("**\n#"))
    }
}
