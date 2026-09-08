package com.hubidubios.mobile.data

import android.content.Context
import androidx.room.Room
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
            .build()
    }
}
