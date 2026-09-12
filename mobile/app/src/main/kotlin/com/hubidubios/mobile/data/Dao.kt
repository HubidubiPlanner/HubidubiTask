package com.hubidubios.mobile.data

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

@Database(
    entities = [EntityRow::class, OutboxRow::class, SyncStateRow::class, BlobUploadRow::class],
    version = 2,
    exportSchema = false,
)
abstract class HubidubiOsDatabase : RoomDatabase() {
    abstract fun entities(): EntityDao
    abstract fun outbox(): OutboxDao
    abstract fun syncState(): SyncStateDao
    abstract fun blobUploads(): BlobUploadDao
}
