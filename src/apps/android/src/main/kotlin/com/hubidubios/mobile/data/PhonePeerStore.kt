package com.hubidubios.mobile.data

import com.hubidubios.core.EntityRecord
import com.hubidubios.core.peer.Cursor
import com.hubidubios.core.peer.Logged
import com.hubidubios.core.peer.PeerStore

/**
 * The phone's side of peer sync, over its Room database: what the paired
 * computer sends is stored here, logged with the computer as its origin so
 * it is never sent back.
 */
class PhonePeerStore(
    private val db: HubidubiOsDatabase,
    private val store: RoomLocalStore,
) : PeerStore {
    private val log = db.peerLog()
    private val state = db.syncState()

    override fun get(id: String): EntityRecord? = store.get(id)

    override fun applyFromPeer(record: EntityRecord, peer: String) {
        store.putFromPeer(record, peer)
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
