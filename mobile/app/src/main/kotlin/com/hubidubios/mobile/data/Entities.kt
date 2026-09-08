package com.hubidubios.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room rows. Entity fields are stored as a JSON blob so the schema is stable
 * as plugins add types (the mirror never needs a migration when a plugin ships
 * a new entity type). `base_json` is the last-synced server state, retained for
 * three-way conflict merges.
 */
@Entity(tableName = "entities")
data class EntityRow(
    @PrimaryKey val id: String,
    val type: String,
    val updated: String,
    val fields_json: String,
    val deleted: Boolean,
    val base_json: String?,   // last-synced snapshot, null until first sync
    val is_conflict_copy: Boolean = false,
)

/** The durable outbox. Its survival across app kill and reboot is the app's
 *  single most important correctness property (PLAN.md Phase 5). */
@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val op: String,
    val record_json: String,
)

@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val key: String,
    val value: String,
)
