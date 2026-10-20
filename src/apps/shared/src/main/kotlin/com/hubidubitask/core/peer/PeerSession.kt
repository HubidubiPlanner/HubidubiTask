package com.hubidubitask.core.peer

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.json.JSONObject

/**
 * Sync sessions: everything after pairing travels sealed inside the TLS.
 *
 * Each sync round starts with a hello, sealed with the pairing key: the phone
 * sends a fresh ML-KEM-768 key, the computer answers with a ciphertext and a
 * session id. The session's two keys (one each way) come from the pairing key
 * and that fresh ML-KEM secret, so
 *  - a recording of a round stays closed to a future quantum computer, and
 *  - a pairing key stolen later still can't open rounds recorded before
 *    (the ML-KEM private key lived only for that round).
 *
 * Every request carries a sequence number the computer takes only once and
 * only upward; every answer is sealed to its request's number, so nothing
 * recorded can be played again or swapped for something else.
 *
 *   hello:   nonce(12) || AES-GCM(helloKey, nonce, n(16) || ek)
 *   answer:  nonce(12) || AES-GCM(helloKey', nonce, sid(16) || ct)
 *   request: seq(8) || AES-GCM(phoneKey, 0^4||seq, frame)
 *   answer:  AES-GCM(computerKey, 0^4||seq, frame)
 *   frame:   metaLength(4) || meta JSON || payload bytes
 */
object PeerSession {
    const val SID_BYTES = 16
    private const val NONCE = 16

    internal fun helloKeys(key: ByteArray, phoneId: String, computerId: String): Pair<ByteArray, ByteArray> {
        val salt = Transcript("hubidubitask v2 hello").add(phoneId).add(computerId).hash()
        return PeerCrypto.hkdf(key, salt, "hello phone->computer") to PeerCrypto.hkdf(key, salt, "hello computer->phone")
    }

    private fun aad(label: String, vararg parts: ByteArray) =
        parts.fold(Transcript(label)) { t, p -> t.add(p) }.bytes()

    private fun sessionKeys(key: ByteArray, kemSecret: ByteArray, phoneId: String, computerId: String,
                            n: ByteArray, ek: ByteArray, ct: ByteArray, sid: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = Transcript("hubidubitask v2 session").add(phoneId).add(computerId)
            .add(n).add(ek).add(ct).add(sid).hash()
        val ikm = key + kemSecret
        return PeerCrypto.hkdf(ikm, salt, "session phone->computer") to PeerCrypto.hkdf(ikm, salt, "session computer->phone")
    }

    private fun nonce(seq: Long) = ByteArray(4) + PeerCrypto.long(seq)

    /** The phone opening one session: [hello] goes out, [open] takes the answer. */
    class Opener(private val key: ByteArray, private val phoneId: String, private val computerId: String) {
        private val kem: AsymmetricCipherKeyPair = PeerCrypto.kemPair()
        private val ek = PeerCrypto.kemPublic(kem)
        private val n = PeerCrypto.bytes(NONCE)
        private val keys = helloKeys(key, phoneId, computerId)

        val hello: ByteArray = PeerCrypto.bytes(12).let { iv ->
            iv + PeerCrypto.seal(keys.first, iv, aad("hello", phoneId.toByteArray(), computerId.toByteArray()), n + ek)
        }

        /** The session, or null when the answer isn't the computer's answer to this hello. */
        fun open(answer: ByteArray): Phone? {
            if (answer.size < 12 + 16) return null
            val plain = PeerCrypto.open(keys.second, answer.copyOf(12),
                aad("hello answer", phoneId.toByteArray(), computerId.toByteArray(), n),
                answer.copyOfRange(12, answer.size)) ?: return null
            if (plain.size != SID_BYTES + PeerCrypto.CT_BYTES) return null
            val sid = plain.copyOf(SID_BYTES)
            val ct = plain.copyOfRange(SID_BYTES, plain.size)
            val ss = runCatching { PeerCrypto.decapsulate(kem, ct) }.getOrNull() ?: return null
            val (send, receive) = sessionKeys(key, ss, phoneId, computerId, n, ek, ct, sid)
            return Phone(sid, send, receive)
        }
    }

    /** The phone's side of an open session. One round at a time uses it. */
    class Phone internal constructor(val sid: ByteArray, private val send: ByteArray, private val receive: ByteArray) {
        private var seq = 0L
        val sidHex: String get() = PeerCrypto.hex(sid)

        /** A sealed request and its number, for [openAnswer]. */
        @Synchronized fun seal(meta: JSONObject, payload: ByteArray = ByteArray(0)): Pair<Long, ByteArray> {
            val s = seq++
            return s to (PeerCrypto.long(s) + PeerCrypto.seal(send, nonce(s), aad("request", sid, PeerCrypto.long(s)),
                frame(meta, payload)))
        }

        fun openAnswer(seq: Long, body: ByteArray): Frame? =
            PeerCrypto.open(receive, nonce(seq), aad("answer", sid, PeerCrypto.long(seq)), body)?.let(::unframe)
    }

    /** The computer's side of one session. */
    class Computer internal constructor(val sid: ByteArray, private val receive: ByteArray, private val send: ByteArray,
                                        val createdAt: Long) {
        private var next = 0L
        /** When a request was last taken: a session lives while it is used. */
        @Volatile var usedAt = createdAt; internal set

        /** The request's number and frame, or null when it is forged, changed or played again. */
        @Synchronized fun openRequest(body: ByteArray): Pair<Long, Frame>? {
            if (body.size < 8 + 16) return null
            val seq = PeerCrypto.long(body, 0)
            if (seq < next || seq == Long.MAX_VALUE) return null
            val plain = PeerCrypto.open(receive, nonce(seq), aad("request", sid, PeerCrypto.long(seq)),
                body.copyOfRange(8, body.size)) ?: return null
            val frame = unframe(plain) ?: return null
            next = seq + 1
            return seq to frame
        }

        fun sealAnswer(seq: Long, meta: JSONObject, payload: ByteArray = ByteArray(0)): ByteArray =
            PeerCrypto.seal(send, nonce(seq), aad("answer", sid, PeerCrypto.long(seq)), frame(meta, payload))
    }

    /**
     * The computer answering a hello from [phoneId] with the pairing [key]:
     * the answer to send and the new session, or null when the hello isn't
     * sealed with that key.
     */
    fun accept(key: ByteArray, phoneId: String, computerId: String, hello: ByteArray, now: Long): Pair<ByteArray, Computer>? {
        if (hello.size < 12 + 16) return null
        val keys = helloKeys(key, phoneId, computerId)
        val plain = PeerCrypto.open(keys.first, hello.copyOf(12), aad("hello", phoneId.toByteArray(), computerId.toByteArray()),
            hello.copyOfRange(12, hello.size)) ?: return null
        if (plain.size != NONCE + PeerCrypto.EK_BYTES) return null
        val n = plain.copyOf(NONCE)
        val ek = plain.copyOfRange(NONCE, plain.size)
        val (ct, ss) = runCatching { PeerCrypto.encapsulate(ek) }.getOrNull() ?: return null
        val sid = PeerCrypto.bytes(SID_BYTES)
        val (receive, send) = sessionKeys(key, ss, phoneId, computerId, n, ek, ct, sid)
        val iv = PeerCrypto.bytes(12)
        val answer = iv + PeerCrypto.seal(keys.second, iv,
            aad("hello answer", phoneId.toByteArray(), computerId.toByteArray(), n), sid + ct)
        return answer to Computer(sid, receive, send, now)
    }

    /** What a sealed request or answer carries: a small JSON head and raw bytes. */
    class Frame(val meta: JSONObject, val payload: ByteArray)

    private fun frame(meta: JSONObject, payload: ByteArray): ByteArray {
        val m = meta.toString().toByteArray(Charsets.UTF_8)
        return PeerCrypto.int(m.size) + m + payload
    }

    private fun unframe(b: ByteArray): Frame? {
        if (b.size < 4) return null
        val n = PeerCrypto.int(b, 0)
        if (n < 0 || n > b.size - 4) return null
        val meta = runCatching { JSONObject(String(b, 4, n, Charsets.UTF_8)) }.getOrNull() ?: return null
        return Frame(meta, b.copyOfRange(4 + n, b.size))
    }
}

/**
 * The computer's open sessions: at most [PER_PEER] per phone, each until it
 * has been idle for [IDLE_MS] (a long upload keeps it alive) and never past
 * [MAX_MS]; the oldest goes first. Separate sessions let two rounds from the
 * same phone run side by side without sharing sequence numbers.
 */
class PeerSessions(private val clock: () -> Long = System::currentTimeMillis) {
    private val byPeer = HashMap<String, LinkedHashMap<String, PeerSession.Computer>>()

    @Synchronized fun add(peer: String, s: PeerSession.Computer) {
        val m = byPeer.getOrPut(peer) { LinkedHashMap() }
        tidy(m)
        while (m.size >= PER_PEER) m.remove(m.keys.first())
        m[PeerCrypto.hex(s.sid)] = s
    }

    @Synchronized fun find(peer: String, sidHex: String): PeerSession.Computer? =
        byPeer[peer]?.also(::tidy)?.get(sidHex)?.also { it.usedAt = clock() }

    @Synchronized fun drop(peer: String) { byPeer.remove(peer) }

    private fun tidy(m: MutableMap<String, PeerSession.Computer>) {
        val now = clock()
        m.values.removeAll { now - it.usedAt >= IDLE_MS || now - it.createdAt >= MAX_MS }
    }

    companion object {
        const val PER_PEER = 4
        const val IDLE_MS = 5 * 60_000L
        const val MAX_MS = 60 * 60_000L
    }
}
