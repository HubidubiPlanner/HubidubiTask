package com.hubidubios.core

/**
 * The sync engine: pull remote changes and apply them (resolving conflicts
 * against pending local edits), then push the outbox.
 *
 * Ordering matters. We pull first so that a remote change to an entity we also
 * edited locally is merged before we push — otherwise our push would be judged
 * stale by the server and silently dropped. After a successful push the pushed
 * state becomes the new base.
 */
class SyncEngine(
    private val store: LocalStore,
    private val outbox: Outbox,
    private val transport: Transport,
    private val deviceId: String,
    private val clock: () -> String = { nowUtc() },
) {

    data class PullReport(val applied: Int, val conflicts: Int, val cursor: Long)

    fun pull(): PullReport {
        var applied = 0
        var conflicts = 0
        var cursor = store.cursor()
        do {
            val batch = transport.pull(cursor)
            for (change in batch.changes) {
                if (applyRemote(change)) conflicts++
                applied++
            }
            cursor = batch.cursor
            store.setCursor(cursor)
        } while (batch.hasMore)
        return PullReport(applied, conflicts, cursor)
    }

    /** @return true if a note conflict copy was created. */
    private fun applyRemote(change: RemoteChange): Boolean {
        val remote = when (change.op) {
            "delete" -> EntityRecord(change.id, change.type, change.updated, deleted = true)
            else -> change.record ?: return false
        }
        val local = store.get(change.id)
        val pendingLocalEdit = outbox.pending().any { it.record.id == change.id }

        // No local divergence: remote is authoritative. Advance base too.
        if (local == null || !pendingLocalEdit) {
            store.put(remote)
            store.setBase(remote)
            return false
        }

        val base = store.base(change.id)
        if (remote.isNote) {
            val res = ConflictResolver.resolveNote(base, local, remote, deviceId, today())
            store.put(res.canonical)
            store.setBase(remote)
            res.conflictCopy?.let {
                store.addConflictCopy(it)
                store.put(it)
                outbox.enqueueUpsert(it) // push the preserved copy back too
                return true
            }
            return false
        }

        val merged = ConflictResolver.mergeStructured(base, local, remote)
        store.put(merged)
        store.setBase(remote)
        // The merged result differs from what we queued; requeue it so the
        // server converges on the same merge.
        if (merged != local) outbox.enqueueUpsert(merged)
        return false
    }

    fun push(): Int {
        val entries = outbox.pending()
        if (entries.isEmpty()) return 0
        val result = transport.push(entries)
        var acked = 0
        for (entry in entries) {
            val status = result.results[entry.record.id]
            if (status == "applied" || status == "stale" || status == "rejected_tombstone" ||
                status == "unknown_type") {
                // "applied": landed. "stale"/"rejected_tombstone": the server
                // already has a newer/authoritative state, which the next pull
                // will deliver — dropping our entry is correct, not a loss.
                // "unknown_type": a record the workstation does not keep (a
                // feed, say); it lives on the phone and the Mac only, and
                // retrying would only keep it queued forever.
                outbox.ack(entry.seq)
                if (status == "applied") {
                    store.setBase(entry.record)
                    acked++
                }
            }
            // Any other status (or a missing one) leaves the entry queued to
            // retry on the next sync.
        }
        return acked
    }

    /** One full cycle: pull then push. Triggered on foreground, known-SSID, or
     *  the WorkManager periodic job. */
    fun sync(): PullReport {
        val report = pull()
        push()
        return report
    }

    private fun today(): String = clock().substring(0, 10)
}
