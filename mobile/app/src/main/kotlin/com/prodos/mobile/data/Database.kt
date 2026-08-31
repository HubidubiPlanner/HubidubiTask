package com.prodos.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.prodos.mobile.security.Keystore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The mirror lives in encrypted SQLite (SQLCipher), keyed by a random
 * passphrase sealed in the Android Keystore. Background sync must reach this
 * when no WebView is alive, which is exactly why the mirror is here and not in
 * IndexedDB (evictable, and gone when no page is running).
 */
object Database {
    @Volatile private var instance: ProdosDatabase? = null

    fun get(context: Context): ProdosDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): ProdosDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = Keystore.databasePassphrase(context)
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(context, ProdosDatabase::class.java, "prodos-mirror.db")
            .openHelperFactory(factory as SupportSQLiteOpenHelper.Factory)
            .build()
    }
}
