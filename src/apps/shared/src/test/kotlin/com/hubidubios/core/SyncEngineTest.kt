package com.hubidubios.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEngineTest {

    /** A scriptable transport: queued pull batches, recorded pushes. */
    private class FakeTransport(
        private val batches: MutableList<ChangeBatch> = mutableListOf(),
        var pushResponder: (List<OutboxEntry>) -> PushResult = { entries ->
            PushResult(entries.associate { it.record.id to "applied" }, cursor = 0)
        },
    ) : Transport {
        val pushed = mutableListOf<List<OutboxEntry>>()
        fun enqueue(batch: ChangeBatch) = batches.add(batch)
        override fun pull(since: Long): ChangeBatch =
            if (batches.isEmpty()) ChangeBatch(emptyList(), since, false) else batches.removeAt(0)
        override fun push(entries: List<OutboxEntry>): PushResult {
            pushed.add(entries)
            return pushResponder(entries)
        }
    }

    private fun task(id: String, updated: String, vararg f: Pair<String, String?>) =
        EntityRecord(id, "task", updated, f.toMap())

    private fun engine(store: LocalStore, outbox: Outbox, t: Transport) =
        SyncEngine(store, outbox, t, deviceId = "phone", clock = { "2026-08-31T12:00:00Z" })

    @Test
    fun `pull applies remote changes and advances cursor`() {
        val store = InMemoryLocalStore()
        val outbox = Outbox(InMemoryOutboxStore())
        val t = FakeTransport()
        t.enqueue(ChangeBatch(
            listOf(RemoteChange(1, "t_1", "task", "upsert", "2026-08-31T10:00:00Z",
                task("t_1", "2026-08-31T10:00:00Z", "text" to "from server"))),
            cursor = 1, hasMore = false))

        val report = engine(store, outbox, t).pull()
        assertEquals(1, report.applied)
        assertEquals(1, store.cursor())
        assertEquals("from server", store.get("t_1")!!.fields["text"])
    }

    @Test
    fun `pull follows hasMore across batches`() {
        val store = InMemoryLocalStore()
        val t = FakeTransport()
        t.enqueue(ChangeBatch(listOf(
            RemoteChange(1, "t_1", "task", "upsert", "t", task("t_1", "t", "text" to "a"))),
            cursor = 1, hasMore = true))
        t.enqueue(ChangeBatch(listOf(
            RemoteChange(2, "t_2", "task", "upsert", "t", task("t_2", "t", "text" to "b"))),
            cursor = 2, hasMore = false))
        val report = engine(store, Outbox(InMemoryOutboxStore()), t).pull()
        assertEquals(2, report.applied)
        assertEquals(2, store.cursor())
    }

    @Test
    fun `remote change to a locally-edited entity is merged, not overwritten`() {
        val store = InMemoryLocalStore()
        val outboxStore = InMemoryOutboxStore()
        val outbox = Outbox(outboxStore)

        // last-synced base, then a local offline edit of `due`
        val base = task("t_1", "2026-08-31T10:00:00Z",
            "text" to "plan", "due" to "2026-09-01", "context" to "@home")
        store.put(base); store.setBase(base)
        val localEdit = base.copy(updated = "2026-08-31T11:00:00Z",
            fields = base.fields + ("due" to "2026-09-09"))
        store.put(localEdit); outbox.enqueueUpsert(localEdit)

        // server changed a different field, context
        val t = FakeTransport()
        t.enqueue(ChangeBatch(listOf(RemoteChange(5, "t_1", "task", "upsert",
            "2026-08-31T10:30:00Z",
            base.copy(updated = "2026-08-31T10:30:00Z",
                fields = base.fields + ("context" to "@errands")))),
            cursor = 5, hasMore = false))

        engine(store, outbox, t).pull()
        val merged = store.get("t_1")!!
        assertEquals("2026-09-09", merged.fields["due"])     // local field kept
        assertEquals("@errands", merged.fields["context"])   // remote field applied
    }

    @Test
    fun `note edited on both sides while disconnected yields both versions`() {
        val store = InMemoryLocalStore()
        val outbox = Outbox(InMemoryOutboxStore())
        val base = EntityRecord("n_1", "note", "2026-08-31T10:00:00Z",
            mapOf("title" to "Sandbox", "body" to "start"))
        store.put(base); store.setBase(base)
        val local = base.copy(updated = "2026-08-31T12:00:00Z",
            fields = base.fields + ("body" to "start + phone"))
        store.put(local); outbox.enqueueUpsert(local)

        val t = FakeTransport()
        t.enqueue(ChangeBatch(listOf(RemoteChange(9, "n_1", "note", "upsert",
            "2026-08-31T11:00:00Z",
            base.copy(updated = "2026-08-31T11:00:00Z",
                fields = base.fields + ("body" to "start + workstation")))),
            cursor = 9, hasMore = false))

        val report = engine(store, outbox, t).pull()
        assertEquals(1, report.conflicts)
        assertEquals("start + workstation", store.get("n_1")!!.fields["body"]) // canonical
        val copies = store.conflictCopies()
        assertEquals(1, copies.size)
        assertEquals("start + phone", copies.single().fields["body"])          // preserved
    }

    @Test
    fun `push acks applied entries and sets base`() {
        val store = InMemoryLocalStore()
        val outboxStore = InMemoryOutboxStore()
        val outbox = Outbox(outboxStore)
        val rec = task("t_1", "2026-08-31T11:00:00Z", "text" to "push me")
        store.put(rec); outbox.enqueueUpsert(rec)
        val t = FakeTransport()

        val acked = engine(store, outbox, t).push()
        assertEquals(1, acked)
        assertTrue(outbox.isEmpty())
        assertEquals(rec, store.base("t_1"))
        assertEquals(1, t.pushed.size)
    }

    @Test
    fun `push keeps entries the server did not accept`() {
        val store = InMemoryLocalStore()
        val outbox = Outbox(InMemoryOutboxStore())
        val rec = task("t_1", "2026-08-31T11:00:00Z", "text" to "x")
        store.put(rec); outbox.enqueueUpsert(rec)
        val t = FakeTransport(pushResponder = { PushResult(emptyMap(), 0) }) // server error/no status

        engine(store, outbox, t).push()
        assertEquals(1, outbox.pending().size) // retained for retry
    }

    @Test
    fun `stale push is dropped - server already has newer state`() {
        val store = InMemoryLocalStore()
        val outbox = Outbox(InMemoryOutboxStore())
        val rec = task("t_1", "2000-01-01T00:00:00Z", "text" to "ancient")
        store.put(rec); outbox.enqueueUpsert(rec)
        val t = FakeTransport(pushResponder = { PushResult(mapOf("t_1" to "stale"), 0) })

        engine(store, outbox, t).push()
        assertTrue(outbox.isEmpty()) // not a loss; next pull brings the newer state
    }

    @Test
    fun `full sync cycle - offline edits reach the server`() {
        val store = InMemoryLocalStore()
        val outbox = Outbox(InMemoryOutboxStore())
        val rec = task("t_1", "2026-08-31T11:00:00Z", "text" to "made offline")
        store.put(rec); outbox.enqueueUpsert(rec)
        val t = FakeTransport()

        engine(store, outbox, t).sync()
        assertTrue(outbox.isEmpty())
        assertEquals(rec.fields, t.pushed.single().single().record.fields)
    }
}
