package com.hubidubitask.mobile.data

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.LocalStore

/** core.LocalStore backed by Room/SQLCipher. */
class RoomLocalStore(private val db: HubidubiTaskDatabase) : LocalStore {
    private val dao = db.entities()
    private val state = db.syncState()
    private val log = db.peerLog()

    override fun get(id: String): EntityRecord? =
        dao.get(id)?.takeIf { !it.is_conflict_copy }?.let { rowToRecord(it) }

    override fun put(record: EntityRecord) {
        val existing = dao.get(record.id)
        dao.put(
            EntityRow(
                id = record.id,
                type = record.type,
                updated = record.updated,
                fields_json = Json.fieldsToJson(record.fields),
                deleted = record.deleted,
                base_json = existing?.base_json,
                is_conflict_copy = existing?.is_conflict_copy ?: false,
            )
        )
        // Every change, from any source, is logged for the Mac. One taken in
        // from the Mac itself is logged by [putFromPeer] with its origin instead.
        log.add(PeerLogRow(id = record.id, origin = null))
    }

    /** A record that arrived from a peer: stored like any other, logged with its origin. */
    fun putFromPeer(record: EntityRecord, peer: String) {
        val existing = dao.get(record.id)
        dao.put(EntityRow(record.id, record.type, record.updated, Json.fieldsToJson(record.fields),
            record.deleted, existing?.base_json, existing?.is_conflict_copy ?: false))
        log.add(PeerLogRow(id = record.id, origin = peer))
    }

    override fun base(id: String): EntityRecord? =
        dao.get(id)?.base_json?.let { Json.recordFromJson(it) }

    override fun setBase(record: EntityRecord) {
        val existing = dao.get(record.id) ?: return put(record).also { setBase(record) }
        dao.put(existing.copy(base_json = Json.recordToJson(record)))
    }

    override fun addConflictCopy(record: EntityRecord) {
        dao.put(
            EntityRow(
                id = record.id, type = record.type, updated = record.updated,
                fields_json = Json.fieldsToJson(record.fields),
                deleted = false, base_json = null, is_conflict_copy = true,
            )
        )
    }

    override fun conflictCopies(): List<EntityRecord> =
        dao.conflictCopies().map { rowToRecord(it) }

    override fun cursor(): Long = state.get(KEY_CURSOR)?.toLongOrNull() ?: 0L
    override fun setCursor(value: Long) = state.set(SyncStateRow(KEY_CURSOR, value.toString()))

    fun byType(type: String): List<EntityRecord> = dao.byType(type).map { rowToRecord(it) }

    private fun rowToRecord(r: EntityRow) = EntityRecord(
        id = r.id, type = r.type, updated = r.updated,
        fields = Json.fieldsFromJson(r.fields_json), deleted = r.deleted,
    )

    companion object { const val KEY_CURSOR = "cursor" }
}

