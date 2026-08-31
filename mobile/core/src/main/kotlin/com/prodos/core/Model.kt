package com.prodos.core

/**
 * An entity as the mobile mirror sees it: stable id, type, RFC 3339 `updated`,
 * and a flat map of fields (the entity JSON minus id/type/updated). Deletions
 * are tombstones carried as [deleted] = true.
 *
 * Kept deliberately framework-free: this type and everything in :core runs on
 * the JVM with no Android dependency, so the sync and conflict logic — the
 * app's most important correctness — is unit-tested without an emulator.
 */
data class EntityRecord(
    val id: String,
    val type: String,
    val updated: String,
    val fields: Map<String, String?> = emptyMap(),
    val deleted: Boolean = false,
) {
    val isStructured: Boolean get() = type == "task" || type == "event" || type == "inbox_item"
    val isNote: Boolean get() = type == "note"

    fun tombstone(at: String = updated): EntityRecord =
        copy(fields = emptyMap(), deleted = true, updated = at)
}

/** RFC 3339 timestamps compare correctly as strings when in UTC ("...Z"). */
internal fun newer(a: String, b: String): Boolean = a >= b

/** One change as delivered by the workstation's cursor-based feed. */
data class RemoteChange(
    val seq: Long,
    val id: String,
    val type: String,
    val op: String,          // "upsert" | "delete"
    val updated: String,
    val record: EntityRecord?, // null for deletes
)

data class ChangeBatch(
    val changes: List<RemoteChange>,
    val cursor: Long,
    val hasMore: Boolean,
)
