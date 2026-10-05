package com.hubidubios.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.hubidubios.mobile.security.Keystore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The mirror lives in encrypted SQLite (SQLCipher), keyed by a random
 * passphrase sealed in the Android Keystore. Background sync must reach this
 * when no WebView is alive, which is exactly why the mirror is here and not in
 * IndexedDB (evictable, and gone when no page is running).
 */
object Database {
    @Volatile private var instance: HubidubiOsDatabase? = null

    fun get(context: Context): HubidubiOsDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): HubidubiOsDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = Keystore.databasePassphrase(context)
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, HubidubiOsDatabase::class.java, "hubidubios-mirror.db")
            .openHelperFactory(factory as SupportSQLiteOpenHelper.Factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    // v3 adds the change log peer sync with the Mac reads. Everything already
    // on the phone is logged once, so a newly paired Mac gets all of it.
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS peer_log (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, origin TEXT)")
            db.execSQL("INSERT INTO peer_log (id, origin) SELECT id, NULL FROM entities " +
                "WHERE is_conflict_copy = 0 ORDER BY updated")
        }
    }

    // v2 adds the durable media-blob upload queue; the mirror + outbox are kept.
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS blob_uploads (" +
                    "sha256 TEXT NOT NULL PRIMARY KEY, " +
                    "local_path TEXT NOT NULL, " +
                    "mime TEXT NOT NULL, " +
                    "note_id TEXT NOT NULL)"
            )
        }
    }
}
