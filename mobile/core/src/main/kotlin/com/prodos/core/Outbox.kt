package com.prodos.core

/**
 * The outbox: local mutations queued for push, flushed on reconnect.
 *
 * "The outbox must survive app kill and reboot" — the single most important
 * correctness property of the app (PLAN.md Phase 5). The durability lives in
 * [OutboxStore], which the Android module backs with Room/SQLite; this class
 * is only the ordering/lifecycle logic over it, so a kill is modelled in tests
 * by discarding the Outbox and rebuilding it over the same store.
 */
class Outbox(private val store: OutboxStore) {

    fun enqueueUpsert(record: EntityRecord) {
        store.add(OutboxEntry(op = "upsert", record = record))
    }

    fun enqueueDelete(record: EntityRecord) {
        store.add(OutboxEntry(op = "delete", record = record.tombstone()))
    }

    /** Entries awaiting delivery, oldest first. */
    fun pending(): List<OutboxEntry> = store.pending()

    fun isEmpty(): Boolean = store.pending().isEmpty()

    /** Remove an entry once the server has durably accepted it. Called only
     *  after a successful push response, so a crash mid-push replays rather
     *  than loses the change. */
    fun ack(seq: Long) = store.remove(seq)
}

data class OutboxEntry(
    val op: String,             // "upsert" | "delete"
    val record: EntityRecord,
    val seq: Long = 0,          // assigned by the store on add
)

/** Persistence boundary. In-memory for tests; Room-backed in the app. */
interface OutboxStore {
    fun add(entry: OutboxEntry): Long
    fun pending(): List<OutboxEntry>
    fun remove(seq: Long)
}

/** A reference in-memory store. The Android app's Room DAO satisfies the same
 *  contract; because kill-durability is the store's job, tests point two
 *  Outbox instances at one store to model a process restart. */
class InMemoryOutboxStore : OutboxStore {
    private val entries = LinkedHashMap<Long, OutboxEntry>()
    private var nextSeq = 1L

    override fun add(entry: OutboxEntry): Long {
        val seq = nextSeq++
        entries[seq] = entry.copy(seq = seq)
        return seq
    }

    override fun pending(): List<OutboxEntry> = entries.values.sortedBy { it.seq }

    override fun remove(seq: Long) {
        entries.remove(seq)
    }
}
