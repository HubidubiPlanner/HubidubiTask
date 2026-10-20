package com.hubidubitask.core.peer

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/** Where a computer was found: by Bonjour or typed by hand. */
data class PeerAddress(val name: String, val host: String, val port: Int)

/**
 * The phone's side of talking to one computer over HTTPS: pairing, and sync
 * rounds in sealed sessions (see [PairV2], [PeerSession]). The computer's
 * certificate is self-signed; [pin] (its sha256) is checked on every call
 * once known, and [seen] reports what the last call was shown.
 */
class PeerClient(val host: String, val port: Int, private val pin: String?) {
    @Volatile var seen: String? = null; private set
    private val base = "https://${if (':' in host) "[$host]" else host}:$port"

    private inner class Trust(private val pin: String?) : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = throw SSLPeerUnverifiedException("no")
        override fun checkServerTrusted(chain: Array<out X509Certificate>, a: String?) {
            val fp = PeerPairing.sha256Hex(chain[0].encoded)
            seen = fp
            if (pin != null && fp != pin) throw SSLPeerUnverifiedException("not the paired computer")
        }
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private fun http(pin: String?, readSeconds: Long = 30): OkHttpClient {
        val tm = Trust(pin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm)
            // A LAN address has no name a certificate could carry: the pin is the identity.
            .hostnameVerifier { _, _ -> true }
            // A dead kept-alive connection (the computer restarted) is retried on a new
            // one. Safe: a sealed request goes once (a repeat is refused by its number),
            // and a repeated pairing finish gets the same answer.
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(readSeconds, TimeUnit.SECONDS).build()
    }

    // ---- pairing

    /** Whether the computer has a code open: true, false, or null when it can't be reached. */
    fun pairingOpen(): Boolean? = runCatching {
        http(pin, 10).newCall(Request.Builder().url("$base/peer/v2/pair/open").build()).execute().use { it.code == 200 }
    }.getOrNull()

    sealed interface PairOutcome {
        data class Paired(val computerId: String, val name: String, val fingerprint: String, val key: ByteArray) : PairOutcome
        /** Reached, but no code is open there (yet). Not a failed try. */
        data object NotReady : PairOutcome
        /** The code went out and no valid confirmation came back: a failed try, counted. */
        data object WrongCode : PairOutcome
        /** Nothing was sent that depends on the code. */
        data object Unreachable : PairOutcome
    }

    /** One pairing try. Network; off the main thread. */
    fun pair(code: String, phoneId: String, name: String): PairOutcome {
        // The certificate seen first is the one every later step is pinned to.
        val fp = pin ?: run { pairingOpen(); seen } ?: return PairOutcome.Unreachable
        val http = http(fp)
        val attempt = PairPhone(code, phoneId, name)
        val started = try {
            post(http, "/peer/v2/pair/start", attempt.start.json()).use { r ->
                when (r.code) {
                    200 -> PairStarted.of(r.bytes(PAIR_LIMIT).decodeToString())
                    409, 429 -> return PairOutcome.NotReady
                    else -> return PairOutcome.Unreachable
                }
            }
        } catch (e: Exception) { return PairOutcome.Unreachable }
        // From here on the code is in play: anything but a valid confirmation is a failed try.
        val finish = try { attempt.finish(started, fp) } catch (e: Exception) { return PairOutcome.WrongCode }
        // Sent once more, the very same, if the answer was lost on the way: the
        // computer answers a repeated finish as it did the first time.
        repeat(2) { round ->
            try {
                post(http, "/peer/v2/pair/finish", finish.json()).use { r ->
                    if (r.code != 200) return PairOutcome.WrongCode
                    val key = attempt.done(PairFinished.of(r.bytes(PAIR_LIMIT).decodeToString()))
                        ?: return PairOutcome.WrongCode
                    return PairOutcome.Paired(started.computerId, started.name, fp, key)
                }
            } catch (e: IOException) {
                if (round == 1) return PairOutcome.WrongCode
            } catch (e: Exception) { return PairOutcome.WrongCode }
        }
        return PairOutcome.WrongCode
    }

    private fun post(http: OkHttpClient, path: String, json: String): Response =
        http.newCall(Request.Builder().url(base + path).post(json.toRequestBody(JSON)).build()).execute()

    /** The answer's body, refused past [limit] bytes: a stranger on the network can't fill the phone's memory. */
    private fun Response.bytes(limit: Int): ByteArray {
        val source = body?.source() ?: throw IOException("no body")
        if (source.request(limit + 1L)) throw IOException("answer too long")
        return source.buffer.readByteArray()
    }

    // ---- sync

    /** The computer doesn't know this phone (any more): pair again. */
    class NotPaired : IOException("the computer no longer knows this phone")

    /** An open sync session with the paired computer; a new one is opened if the computer let it go. */
    inner class Connection internal constructor(private val http: OkHttpClient, private val phoneId: String,
                                                private val reopen: () -> PeerSession.Phone) {
        private var session = reopen()

        private fun call(meta: JSONObject, payload: ByteArray = ByteArray(0)): PeerSession.Frame {
            repeat(2) { round ->
                val s = session
                val (seq, body) = s.seal(meta, payload)
                http.newCall(Request.Builder().url("$base/peer/v2/call")
                    .header("X-Peer", phoneId).header("X-Session", s.sidHex)
                    .post(body.toRequestBody(BINARY)).build()).execute().use { r ->
                    when (r.code) {
                        200 -> return s.openAnswer(seq, r.bytes(ANSWER_LIMIT)) ?: throw IOException("garbled answer")
                        401 -> throw NotPaired()
                        // The session ended (idle, or the computer restarted): the request wasn't taken.
                        410 -> if (round == 0) session = reopen() else throw IOException("session ended")
                        else -> throw IOException("the computer answered ${r.code}")
                    }
                }
            }
            throw IOException("session ended")
        }

        private fun ok(f: PeerSession.Frame): PeerSession.Frame {
            val status = f.meta.optInt("status", 0)
            if (status != 200) throw IOException("the computer answered $status")
            return f
        }

        fun sync(req: SyncRequest): SyncResponse =
            PeerJson.response(ok(call(JSONObject().put("op", "sync"),
                PeerJson.request(req).toByteArray(Charsets.UTF_8))).payload.toString(Charsets.UTF_8))

        /**
         * Sends [file], named by its hash [sha], in sealed chunks, carrying on
         * from what the computer already holds of it.
         */
        fun putBlob(sha: String, file: File) {
            require(isSha(sha))
            RandomAccessFile(file, "r").use { f ->
                val size = f.length()
                val have = ok(call(JSONObject().put("op", "blob_have").put("sha", sha))).meta.optLong("part", 0)
                var offset = if (have in 1 until size) have else 0L
                do {
                    val n = minOf(CHUNK.toLong(), size - offset).toInt()
                    f.seek(offset)
                    val chunk = ByteArray(n).also { f.readFully(it) }
                    val last = offset + n == size
                    ok(call(JSONObject().put("op", "blob_put").put("sha", sha).put("offset", offset)
                        .put("last", last), chunk))
                    offset += n
                } while (!last)
            }
        }

        /** Fetches the blob [sha] into [dest], checked against its hash; false when the computer lacks it. */
        fun getBlob(sha: String, dest: File): Boolean {
            require(isSha(sha))
            dest.parentFile?.mkdirs()
            // A file of its own: two computers may send the same blob at once.
            val part = File.createTempFile(".part-", null, dest.parentFile)
            try {
                part.outputStream().use { out ->
                    var offset = 0L
                    while (true) {
                        val f = call(JSONObject().put("op", "blob_get").put("sha", sha).put("offset", offset))
                        when (f.meta.optInt("status")) {
                            200 -> {}
                            404 -> return false
                            else -> throw IOException("the computer answered ${f.meta.optInt("status")}")
                        }
                        val size = f.meta.getLong("size")
                        if (size > MAX_BLOB || offset + f.payload.size > size) throw IOException("too big")
                        out.write(f.payload); offset += f.payload.size
                        if (f.meta.optBoolean("last")) break
                        if (f.payload.isEmpty()) throw IOException("stalled")
                    }
                }
                // Named by its hash, so it must be its hash.
                if (sha256Hex(part) != sha) return false
                if (!part.renameTo(dest)) { dest.delete(); if (!part.renameTo(dest)) return false }
                return true
            } finally { part.delete() }
        }
    }

    /** A sealed session for one round. Throws [NotPaired] when the computer doesn't know this phone. */
    fun connect(phoneId: String, computerId: String, key: ByteArray): Connection {
        val http = http(pin, 60)
        return Connection(http, phoneId) {
            val opener = PeerSession.Opener(key, phoneId, computerId)
            http.newCall(Request.Builder().url("$base/peer/v2/hello").header("X-Peer", phoneId)
                .post(opener.hello.toRequestBody(BINARY)).build()).execute().use { r ->
                if (r.code == 401) throw NotPaired()
                if (r.code != 200) throw IOException("the computer answered ${r.code}")
                opener.open(r.bytes(HELLO_LIMIT)) ?: throw NotPaired()
            }
        }
    }

    data class Round(val sent: Int, val received: Int)

    /** A photo, recording or file the computer held and this phone now has too. */
    data class Fetched(val sha: String, val file: File, val mime: String, val recordId: String)

    /**
     * One sync round: changes both ways, then the bytes of any photo, memo or
     * file either side now names but lacks. Throws when the computer can't be
     * reached or refuses; nothing is lost, cursors only move on answers.
     */
    fun round(store: PeerStore, phoneId: String, computerId: String, key: ByteArray, blobsDir: File,
              onFetched: (Fetched) -> Unit = {}): Round {
        val c = connect(phoneId, computerId, key)
        val wanted = LinkedHashMap<String, Pair<String, String>>()   // sha -> (mime, record id)
        val watching = object : PeerStore by store {
            override fun applyFromPeer(record: com.hubidubitask.core.EntityRecord, peer: String) {
                store.applyFromPeer(record, peer)
                val sha = record.fields["media_sha256"]
                if (!record.deleted && sha != null && isSha(sha) && !File(blobsDir, sha).exists())
                    wanted[sha] = (record.fields["media_mime"] ?: "application/octet-stream") to record.id
            }
        }
        val report = PeerSyncer(watching, peer = computerId, self = phoneId, send = c::sync,
            blobsOf = { changes ->
                changes.mapNotNull { it.record.fields["media_sha256"] }
                    .filter { isSha(it) && File(blobsDir, it).exists() }.distinct()
            },
            upload = { shas ->
                shas.filter(::isSha).forEach { sha ->
                    val f = File(blobsDir, sha)
                    if (f.exists()) runCatching { c.putBlob(sha, f) }
                }
            },
        ).round()
        wanted.forEach { (sha, meta) ->
            runCatching {
                val f = File(blobsDir, sha)
                if (c.getBlob(sha, f)) onFetched(Fetched(sha, f, meta.first, meta.second))
            }
        }
        return Round(report.sent, report.received)
    }

    companion object {
        /** Blob bytes per sealed request. */
        const val CHUNK = 4 shl 20
        const val MAX_BLOB = 1L shl 30
        private const val PAIR_LIMIT = 64 * 1024
        private const val HELLO_LIMIT = 4 * 1024
        /** A sync batch or one blob chunk, sealed. */
        private const val ANSWER_LIMIT = 32 shl 20
        private val JSON = "application/json".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()

        fun isSha(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun sha256Hex(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The phone trying to pair until a computer takes the code, the code's ten
 * minutes run out, or [PeerPairing.MAX_ATTEMPTS] tries fail — counted across
 * every computer it tries, and across runs with the same code (typed again
 * after a refusal), so a fake one on the network gets no more guesses than
 * the real one would allow. Only computers with a code open are tried.
 */
class PhonePairing(
    private val code: String,
    private val phoneId: String,
    private val name: String,
    /** The code is shown on this phone and typed on the computer (else typed here). */
    private val shownHere: Boolean,
    private val reach: (PeerAddress) -> PeerClient = { PeerClient(it.host, it.port, null) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: () -> Unit = { Thread.sleep(2500) },
) {
    sealed interface Result {
        data class Paired(val at: PeerAddress, val outcome: PeerClient.PairOutcome.Paired) : Result
        /** Typed here and refused. */
        data object WrongCode : Result
        /** Too many failed tries: stopped. */
        data object GaveUp : Result
        data object TimedOut : Result
    }

    fun run(candidates: () -> List<PeerAddress>, active: () -> Boolean = { true }): Result {
        val until = clock() + PeerPairing.CODE_TTL_MS
        val key = PeerPairing.normalize(code)
        if (failures(key) >= PeerPairing.MAX_ATTEMPTS) return Result.GaveUp
        while (clock() < until && active()) {
            var refused = false
            for (at in candidates()) {
                val client = reach(at)
                if (client.pairingOpen() != true) continue
                when (val out = client.pair(code, phoneId, name)) {
                    is PeerClient.PairOutcome.Paired -> return Result.Paired(at, out)
                    PeerClient.PairOutcome.WrongCode -> {
                        if (failed(key) >= PeerPairing.MAX_ATTEMPTS) return Result.GaveUp
                        refused = true
                    }
                    else -> {}
                }
            }
            if (refused && !shownHere) return Result.WrongCode
            if (!active()) break
            pause()
        }
        return Result.TimedOut
    }

    /** Wrong tries per code in this app's life, each kept for the code's ten minutes. */
    private fun failures(code: String): Int = synchronized(FAILED) {
        val now = clock()
        FAILED.values.removeAll { now - it.first >= PeerPairing.CODE_TTL_MS }
        FAILED[code]?.second ?: 0
    }

    private fun failed(code: String): Int = synchronized(FAILED) {
        val n = failures(code) + 1
        FAILED[code] = (FAILED[code]?.first ?: clock()) to n
        n
    }

    private companion object {
        val FAILED = HashMap<String, Pair<Long, Int>>()
    }
}
