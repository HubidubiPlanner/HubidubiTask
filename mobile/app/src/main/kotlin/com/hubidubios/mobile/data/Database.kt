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
            .addMigrations(MIGRATION_1_2)
            .build()
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
