package com.hubidubios.mobile.data

import com.hubidubios.core.EntityRecord
import com.hubidubios.core.Outbox
import com.hubidubios.core.peer.Cursor
import com.hubidubios.core.peer.Logged
import com.hubidubios.core.peer.PeerStore

/**
 * The phone's side of peer sync, over the Room mirror. What comes in from the
 * Mac is also queued for the workstation, so a change made on the Mac reaches
 * every device — the phone is the one that talks to both.
 */
class PhonePeerStore(
    private val db: HubidubiOsDatabase,
    private val store: RoomLocalStore,
    private val outbox: Outbox,
) : PeerStore {
    private val log = db.peerLog()
    private val state = db.syncState()

    override fun get(id: String): EntityRecord? = store.get(id)

    override fun applyFromPeer(record: EntityRecord, peer: String) {
        db.runInTransaction {
            store.putFromPeer(record, peer)
            if (record.deleted) outbox.enqueueDelete(record) else outbox.enqueueUpsert(record)
        }
    }

    override fun changesSince(seq: Long, exclude: String?, limit: Int): List<Logged> =
        log.latestSince(seq).asSequence()
            .filter { exclude == null || it.origin != exclude }
            .mapNotNull { row -> store.get(row.id)?.let { Logged(row.seq, it) } }
            .take(limit).toList()

    override fun lastSeq(): Long = log.lastSeq()

    override fun cursor(peer: String) = Cursor(
        state.get("peer_theirs:$peer")?.toLongOrNull() ?: 0,
        state.get("peer_mine:$peer")?.toLongOrNull() ?: 0,
    )

    override fun setCursor(peer: String, cursor: Cursor) {
        state.set(SyncStateRow("peer_theirs:$peer", cursor.theirs.toString()))
        state.set(SyncStateRow("peer_mine:$peer", cursor.mine.toString()))
    }
}
