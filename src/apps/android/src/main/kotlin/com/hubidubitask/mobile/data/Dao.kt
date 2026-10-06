package com.hubidubitask.mobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

@Dao
interface EntityDao {
    @Query("SELECT * FROM entities WHERE id = :id")
    fun get(id: String): EntityRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: EntityRow)

    @Query("SELECT * FROM entities WHERE is_conflict_copy = 1")
    fun conflictCopies(): List<EntityRow>

    @Query("SELECT * FROM entities WHERE type = :type AND deleted = 0 AND is_conflict_copy = 0 ORDER BY updated DESC")
    fun byType(type: String): List<EntityRow>
}

@Dao
interface OutboxDao {
    @Insert
    fun add(row: OutboxRow): Long

    @Query("SELECT * FROM outbox ORDER BY seq ASC")
    fun pending(): List<OutboxRow>

    @Query("DELETE FROM outbox WHERE seq = :seq")
    fun remove(seq: Long)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE key = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun set(row: SyncStateRow)
}

@Dao
interface BlobUploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(row: BlobUploadRow)

    @Query("SELECT * FROM blob_uploads")
    fun pending(): List<BlobUploadRow>

    @Query("DELETE FROM blob_uploads WHERE sha256 = :sha")
    fun remove(sha: String)
}

@Dao
interface PeerLogDao {
    @Insert
    fun add(row: PeerLogRow): Long

    /** Each entity's latest log entry past [since] — it carries the current state. */
    @Query("SELECT * FROM peer_log l WHERE l.seq > :since AND l.seq = (SELECT MAX(seq) FROM peer_log WHERE id = l.id) ORDER BY l.seq")
    fun latestSince(since: Long): List<PeerLogRow>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM peer_log")
    fun lastSeq(): Long
}

@Database(
    entities = [EntityRow::class, OutboxRow::class, SyncStateRow::class, BlobUploadRow::class,
                PeerLogRow::class],
    version = 3,
    exportSchema = false,
)
abstract class HubidubiTaskDatabase : RoomDatabase() {
    abstract fun entities(): EntityDao
    abstract fun outbox(): OutboxDao
    abstract fun syncState(): SyncStateDao
    abstract fun blobUploads(): BlobUploadDao
    abstract fun peerLog(): PeerLogDao
}
