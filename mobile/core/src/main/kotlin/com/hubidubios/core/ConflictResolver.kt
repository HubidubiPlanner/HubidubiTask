package com.hubidubios.core

/**
 * Conflict resolution (PLAN.md Phase 5), applied when a local edit and a
 * remote change touch the same entity while disconnected.
 *
 * - Structured entities (task, event, inbox_item): three-way field-level
 *   merge against the last-synced base. A field only one side changed takes
 *   that side; a field both changed is last-write-wins by entity `updated`.
 *   Two devices editing different fields both win.
 * - Note bodies: never auto-merged. The server version keeps the canonical
 *   path; the local body is preserved as a conflict copy and surfaced in the
 *   UI. User-typed text is never silently discarded.
 * - Deletion beats edit: a tombstone on either side always wins over a
 *   concurrent modification.
 */
object ConflictResolver {

    data class NoteResolution(
        val canonical: EntityRecord,
        val conflictCopy: EntityRecord? = null,
    )

    /** Merge for structured entities. `base` is the last-synced state, or null
     *  if this entity was created independently on both sides. */
    fun mergeStructured(base: EntityRecord?, local: EntityRecord, remote: EntityRecord): EntityRecord {
        require(local.isStructured) { "mergeStructured called on ${local.type}" }
        tombstoneWinner(local, remote)?.let { return it }

        val keys = buildSet {
            base?.let { addAll(it.fields.keys) }
            addAll(local.fields.keys)
            addAll(remote.fields.keys)
        }
        val localWins = newer(local.updated, remote.updated)
        val merged = HashMap<String, String?>(keys.size)
        for (k in keys) {
            val bv = base?.fields?.get(k)
            val lv = local.fields[k]
            val rv = remote.fields[k]
            val localChanged = if (base == null) lv != null else lv != bv
            val remoteChanged = if (base == null) rv != null else rv != bv
            merged[k] = when {
                localChanged && remoteChanged -> if (localWins) lv else rv
                localChanged -> lv
                remoteChanged -> rv
                else -> bv
            }
        }
        val updated = if (localWins) local.updated else remote.updated
        return local.copy(fields = merged, updated = updated, deleted = false)
    }

    /** Resolve a note. Metadata (title, tags) is last-write-wins; the body is
     *  never auto-merged. */
    fun resolveNote(
        base: EntityRecord?,
        local: EntityRecord,
        remote: EntityRecord,
        deviceId: String,
        date: String,
    ): NoteResolution {
        require(local.isNote) { "resolveNote called on ${local.type}" }
        tombstoneWinner(local, remote)?.let { return NoteResolution(it) }

        val localBody = local.fields["body"]
        val remoteBody = remote.fields["body"]
        val baseBody = base?.fields?.get("body")
        val localChanged = if (base == null) true else localBody != baseBody
        val remoteChanged = if (base == null) true else remoteBody != baseBody

        // Metadata merges by whole-entity LWW; the body decides canonicity.
        val canonicalMeta = if (newer(local.updated, remote.updated)) local else remote

        if (localBody == remoteBody) {
            return NoteResolution(canonicalMeta) // agree; no conflict
        }
        if (localChanged && remoteChanged) {
            // Real divergence: server body is canonical, local body preserved.
            val canonical = remote.copy(
                fields = remote.fields.toMutableMap().apply {
                    // keep whichever metadata is newer, but the server's body
                    put("title", canonicalMeta.fields["title"])
                    put("tags", canonicalMeta.fields["tags"])
                },
            )
            val copy = conflictCopy(local, deviceId, date)
            return NoteResolution(canonical, copy)
        }
        // Only one side changed the body.
        return NoteResolution(if (localChanged) local else remote)
    }

    /** Build the alongside copy: `<slug>.conflict-<device>-<date>.md`, a new
     *  independent note id so it never collides with the canonical one. */
    fun conflictCopy(local: EntityRecord, deviceId: String, date: String): EntityRecord {
        val baseTitle = local.fields["title"] ?: "untitled"
        val fields = local.fields.toMutableMap()
        fields["title"] = "$baseTitle (conflict $deviceId $date)"
        fields["conflict_of"] = local.id
        return local.copy(id = "${local.id}.conflict-$deviceId-$date", fields = fields)
    }

    /** If either side is a tombstone, the tombstone wins. */
    private fun tombstoneWinner(local: EntityRecord, remote: EntityRecord): EntityRecord? {
        if (remote.deleted && local.deleted) return remote
        if (remote.deleted) return remote
        if (local.deleted) return local
        return null
    }
}
