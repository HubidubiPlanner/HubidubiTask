package com.hubidubitask.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewItemDueTest {
    private val today = LocalDate.of(2026, 12, 31)

    @Test
    fun `tomorrow is the default`() {
        assertEquals(NewItemDue.TOMORROW, NewItemDue.of(null))
        assertEquals(NewItemDue.TOMORROW, NewItemDue.of("someday"))
        assertEquals("2027-01-01", NewItemDue.due(null, today))
    }

    @Test
    fun `no date gives none`() {
        assertEquals(NewItemDue.NONE, NewItemDue.of(NewItemDue.NONE))
        assertNull(NewItemDue.due(NewItemDue.NONE, today))
    }

    @Test
    fun `every choice has a label`() {
        assertEquals(listOf(NewItemDue.TOMORROW, NewItemDue.NONE), NewItemDue.OPTIONS.map { it.first })
    }
}
