package com.prodos.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxTest {

    private fun task(id: String, text: String) =
        EntityRecord(id, "task", "2026-08-31T10:00:00Z", mapOf("text" to text))

    @Test
    fun `survives app kill - entries persist across a new Outbox over the same store`() {
        val store = InMemoryOutboxStore() // stands in for the Room-backed store
        val outbox = Outbox(store)
        outbox.enqueueUpsert(task("t_1", "capture one"))
        outbox.enqueueUpsert(task("t_2", "capture two"))

        // "kill": drop the Outbox object; the store (durable rows) remains.
        val afterRestart = Outbox(store)
        val pending = afterRestart.pending()
        assertEquals(2, pending.size)
        assertEquals(listOf("t_1", "t_2"), pending.map { it.record.id })
    }

    @Test
    fun `ack removes only the acknowledged entry`() {
        val store = InMemoryOutboxStore()
        val outbox = Outbox(store)
        outbox.enqueueUpsert(task("t_1", "one"))
        outbox.enqueueUpsert(task("t_2", "two"))
        val first = outbox.pending().first()

        outbox.ack(first.seq)
        assertEquals(listOf("t_2"), outbox.pending().map { it.record.id })
        assertFalse(outbox.isEmpty())
    }

    @Test
    fun `unacked entry replays - a crash between send and ack loses nothing`() {
        val store = InMemoryOutboxStore()
        val outbox = Outbox(store)
        outbox.enqueueUpsert(task("t_1", "one"))
        // simulate: pushed, server accepted, but app crashed before ack
        val stillPending = Outbox(store).pending()
        assertEquals(1, stillPending.size) // will be re-pushed; server upsert is idempotent by id
    }

    @Test
    fun `enqueueDelete stores a tombstone`() {
        val store = InMemoryOutboxStore()
        val outbox = Outbox(store)
        outbox.enqueueDelete(task("t_1", "doomed"))
        val entry = outbox.pending().single()
        assertEquals("delete", entry.op)
        assertTrue(entry.record.deleted)
    }

    @Test
    fun `preserves FIFO order`() {
        val store = InMemoryOutboxStore()
        val outbox = Outbox(store)
        repeat(5) { outbox.enqueueUpsert(task("t_$it", "x")) }
        assertEquals((0..4).map { "t_$it" }, outbox.pending().map { it.record.id })
    }
}
