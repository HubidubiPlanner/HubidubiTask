package com.hubidubitask.desktop

import com.hubidubitask.core.peer.PeerPairing
import com.hubidubitask.desktop.ui.SecretStore
import com.hubidubitask.desktop.ui.Secrets
import java.util.concurrent.ConcurrentHashMap

/** Each paired phone's sync key, by phone id. */
interface PeerKeys {
    fun get(id: String): ByteArray?
    fun put(id: String, key: ByteArray)
    fun remove(id: String)
}

/**
 * Sync keys where the system keeps secrets: the login Keychain on the Mac,
 * the Secret Service on Linux (see [Secrets]), never in the database. On a
 * Linux with no keyring they fall back, like API keys, to owner-only files in
 * the data folder. Read once and then kept in memory (the phone comes every
 * minute); a failed read is tried again next time, never taken as "unpaired".
 */
internal class SecretPeerKeys(private val secrets: SecretStore = Secrets) : PeerKeys {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    private fun account(id: String): String {
        require(PeerPairing.isDeviceId(id))
        return "peer-key-$id"
    }

    override fun get(id: String): ByteArray? {
        if (!PeerPairing.isDeviceId(id)) return null
        cache[id]?.let { return it }
        return secrets.get(account(id))?.let(::unhex)?.takeIf { it.size == 32 }?.also { cache[id] = it }
    }

    /** Throws when the system won't store it: pairing must not be confirmed without its key. */
    override fun put(id: String, key: ByteArray) {
        check(secrets.put(account(id), key.joinToString("") { "%02x".format(it) })) { "the key could not be stored" }
        cache[id] = key
    }

    override fun remove(id: String) {
        if (!PeerPairing.isDeviceId(id)) return
        cache.remove(id)
        secrets.put(account(id), "")
    }

    private fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Keys held in memory only: for tests. */
class MemoryPeerKeys : PeerKeys {
    private val keys = ConcurrentHashMap<String, ByteArray>()
    override fun get(id: String) = keys[id]
    override fun put(id: String, key: ByteArray) { keys[id] = key }
    override fun remove(id: String) { keys.remove(id) }
}
