package com.hubidubitask.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteDueTest {
    private val today = LocalDate.of(2026, 12, 31)

    @Test
    fun `tomorrow is the default`() {
        assertEquals(NoteDue.TOMORROW, NoteDue.of(null))
        assertEquals(NoteDue.TOMORROW, NoteDue.of("someday"))
        assertEquals("2027-01-01", NoteDue.due(null, today))
    }

    @Test
    fun `no date gives none`() {
        assertEquals(NoteDue.NONE, NoteDue.of(NoteDue.NONE))
        assertNull(NoteDue.due(NoteDue.NONE, today))
    }

    @Test
    fun `every choice has a label`() {
        assertEquals(listOf(NoteDue.TOMORROW, NoteDue.NONE), NoteDue.OPTIONS.map { it.first })
    }
}
