package com.hubidubios.core

/**
 * The local mirror the sync engine reads and writes. Backed by Room/SQLCipher
 * in the app; an in-memory implementation drives the core tests.
 *
 * `base` is the last-synced server state per entity, retained so conflict
 * resolution can do a three-way merge (what did *this* side change since we
 * last agreed). Without it, "two devices editing different fields both win"
 * is impossible.
 */
interface LocalStore {
    fun get(id: String): EntityRecord?
    fun put(record: EntityRecord)

    fun base(id: String): EntityRecord?
    fun setBase(record: EntityRecord)

    fun addConflictCopy(record: EntityRecord)
    fun conflictCopies(): List<EntityRecord>

    fun cursor(): Long
    fun setCursor(value: Long)
}

class InMemoryLocalStore : LocalStore {
    private val records = HashMap<String, EntityRecord>()
    private val bases = HashMap<String, EntityRecord>()
    private val conflicts = ArrayList<EntityRecord>()
    private var cursor = 0L

    override fun get(id: String) = records[id]
    override fun put(record: EntityRecord) { records[record.id] = record }

    override fun base(id: String) = bases[id]
    override fun setBase(record: EntityRecord) { bases[record.id] = record }

    override fun addConflictCopy(record: EntityRecord) { conflicts.add(record) }
    override fun conflictCopies(): List<EntityRecord> = conflicts.toList()

    override fun cursor() = cursor
    override fun setCursor(value: Long) { cursor = value }

    /** Test convenience: everything currently mirrored. */
    fun all(): List<EntityRecord> = records.values.toList()
}
