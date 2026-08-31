package com.prodos.mobile.sync

import android.content.Context
import com.prodos.core.EntityRecord
import com.prodos.core.Outbox
import com.prodos.core.SyncEngine
import com.prodos.mobile.data.Database
import com.prodos.mobile.data.RoomLocalStore
import com.prodos.mobile.data.RoomOutboxStore
import com.prodos.mobile.net.PairedServer
import com.prodos.mobile.net.PinnedTransport
import java.security.SecureRandom

/**
 * Wires the Room-backed stores to the pure-Kotlin core SyncEngine. Everything
 * here except the store/transport construction is tested in :core.
 */
class Sync private constructor(
    val store: RoomLocalStore,
    val outbox: Outbox,
    private val engine: SyncEngine?,
) {
    fun capture(text: String): String {
        // The never-fail path: append to the outbox, no network, no validation.
        val id = "i_" + randomSuffix()
        val record = EntityRecord(
            id = id, type = "inbox_item", updated = com.prodos.core.nowUtc(),
            fields = mapOf("text" to text, "time" to time(), "date" to date()),
        )
        store.put(record)
        outbox.enqueueUpsert(record)
        return id
    }

    /** Returns true if a sync cycle ran (a server is paired). */
    fun syncNow(): Boolean {
        val e = engine ?: return false
        e.sync()
        return true
    }

    companion object {
        fun open(context: Context): Sync {
            val db = Database.get(context)
            val store = RoomLocalStore(db)
            val outbox = Outbox(RoomOutboxStore(db))
            val server = PairedServer.load(context)
            val engine = server?.let {
                SyncEngine(store, outbox, PinnedTransport(it), deviceIdFor(context))
            }
            return Sync(store, outbox, engine)
        }

        private fun deviceIdFor(context: Context): String =
            com.prodos.mobile.security.Keystore.getSecret(context, "device-id") ?: "phone"
    }
}

private val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
private fun randomSuffix(): String {
    val r = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return r.joinToString("") { ALPHABET[it.toInt() and 31].toString() }
}
private fun time(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
private fun date(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
