package com.hubidubitask.core.peer

import com.hubidubitask.core.EntityRecord
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device-to-device sync: the phone and the Mac keep each other up to date
 * directly, over the LAN, with nothing in between.
 *
 * Every change a device makes (or takes in) is written to its change log with
 * the next local sequence number. Each round — every 60 seconds — the phone
 * sends the Mac everything past what the Mac last acknowledged and gets the
 * Mac's changes back the same way. A record that both sides changed is
 * settled by its modification time: the newer one wins, whole. Deletions are
 * records too (tombstones), so they travel and win the same way.
 *
 * A change taken in from a peer is logged with that peer as its origin and is
 * never sent back to it — no echo — while still going on to everyone else.
 */

/** One logged change: a local sequence number and the record as it now stands. */
data class Logged(val seq: Long, val record: EntityRecord)

/** What a device must provide for peer sync. */
interface PeerStore {
    fun get(id: String): EntityRecord?
    /** Take in a record that arrived from [peer]: store it and log it with that origin. */
    fun applyFromPeer(record: EntityRecord, peer: String)
    /** Logged changes after [seq], oldest first, leaving out those that came from [exclude]. */
    fun changesSince(seq: Long, exclude: String?, limit: Int): List<Logged>
    fun lastSeq(): Long
    fun cursor(peer: String): Cursor
    fun setCursor(peer: String, cursor: Cursor)
}

/**
 * How far two devices have got with each other: [theirs] is the peer's
 * highest sequence we have applied; [mine] is our highest the peer confirmed.
 */
data class Cursor(val theirs: Long = 0, val mine: Long = 0)

/** Newer modification wins. Ties are settled the same way on both sides. */
object Lww {
    fun incomingWins(incoming: EntityRecord, local: EntityRecord?): Boolean {
        if (local == null) return true
        if (incoming.updated != local.updated) return incoming.updated > local.updated
        if (incoming == local) return false
        // Same second, different content: a deletion beats an edit, then an
        // arbitrary but symmetric order, so both devices settle on one record.
        if (incoming.deleted != local.deleted) return incoming.deleted
        return fingerprint(incoming) > fingerprint(local)
    }

    private fun fingerprint(r: EntityRecord) =
        r.type + "|" + r.fields.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
}

data class SyncRequest(
    val from: String,
    /** Highest sequence of the responder's that the sender has applied. */
    val ack: Long,
    val changes: List<Logged>,
    /** Blob hashes the sender is offering to upload (media it holds). */
    val blobs: List<String> = emptyList(),
)

data class SyncResponse(
    val ack: Long,
    val changes: List<Logged>,
    /** Whether the responder has more past [changes] (another round follows at once). */
    val more: Boolean,
    /** Of the offered blobs, the ones the responder lacks and wants uploaded. */
    val want: List<String> = emptyList(),
)

/**
 * The side that answers (the Mac). Stateless apart from the store, so the HTTP
 * layer just decodes, calls [handle] and encodes.
 */
class PeerResponder(private val store: PeerStore, private val hasBlob: (String) -> Boolean = { true }) {

    fun handle(peer: String, req: SyncRequest): SyncResponse {
        var applied = store.cursor(peer).theirs
        for (c in req.changes) {
            if (Lww.incomingWins(c.record, store.get(c.record.id))) store.applyFromPeer(c.record, peer)
            applied = maxOf(applied, c.seq)
        }
        val out = store.changesSince(req.ack, exclude = peer, limit = BATCH + 1)
        store.setCursor(peer, Cursor(theirs = applied, mine = req.ack))
        return SyncResponse(
            ack = applied,
            changes = out.take(BATCH),
            more = out.size > BATCH,
            want = req.blobs.filterNot(hasBlob),
        )
    }

    companion object { const val BATCH = 500 }
}

/**
 * The side that asks (the phone): one [round] per minute. [send] is the
 * network call; it throws when the peer can't be reached, and nothing is lost —
 * the cursors only move once an answer has come back.
 */
class PeerSyncer(
    private val store: PeerStore,
    private val peer: String,
    private val self: String,
    private val send: (SyncRequest) -> SyncResponse,
    private val blobsOf: (List<Logged>) -> List<String> = { emptyList() },
    private val upload: (List<String>) -> Unit = {},
) {
    data class Report(val sent: Int, val received: Int)

    fun round(): Report {
        var sent = 0; var received = 0
        var rounds = 0
        do {
            val cur = store.cursor(peer)
            val mine = store.changesSince(cur.mine, exclude = peer, limit = PeerResponder.BATCH)
            val offered = blobsOf(mine)
            val resp = send(SyncRequest(self, cur.theirs, mine, offered))
            var theirs = cur.theirs
            for (c in resp.changes) {
                if (Lww.incomingWins(c.record, store.get(c.record.id))) store.applyFromPeer(c.record, peer)
                theirs = maxOf(theirs, c.seq)
            }
            // The peer applied everything we sent before answering, so our
            // cursor moves to the last change in this batch.
            store.setCursor(peer, Cursor(theirs = theirs, mine = mine.lastOrNull()?.seq ?: cur.mine))
            // Only what was offered: the peer can't name other files to have sent.
            val wanted = resp.want.filter(offered::contains)
            if (wanted.isNotEmpty()) upload(wanted)
            sent += mine.size; received += resp.changes.size
            // Keep going while either side still has a full batch waiting.
        } while ((resp.more || mine.size == PeerResponder.BATCH) && ++rounds < 50)
        return Report(sent, received)
    }
}

/**
 * The typed pairing code and small helpers around it. Whichever device starts
 * pairing shows the code; it is typed on the other. What the code does is in
 * [PairV2]: it is a PAKE password, so it can't be guessed offline.
 */
object PeerPairing {
    // No 0/O, 1/I/L: a code read off one screen and typed on another.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private val random = SecureRandom()

    const val CODE_TTL_MS = 10 * 60 * 1000L
    /** The port every computer listens on, so a phone can find one without Bonjour. */
    const val PORT = 5244
    /** Wrong tries a code survives, on the computer and on the phone alike. */
    const val MAX_ATTEMPTS = 5

    fun newCode(): String = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }
        .joinToString("").let { it.take(4) + "-" + it.drop(4) }

    /** "abcd efgh", "ABCD-EFGH" and "abcdefgh" are the same code. */
    fun normalize(code: String) = code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** Exactly eight characters of the code alphabet, spaces and dashes aside. */
    fun isValidCode(code: String): Boolean {
        if (code.any { !(it.isLetterOrDigit() || it == ' ' || it == '-') }) return false
        val n = normalize(code)
        return n.length == LENGTH && n.all { it in ALPHABET } && code.count { it.isLetterOrDigit() } == LENGTH
    }

    fun nonce(): String = ByteArray(16).also(random::nextBytes).hex()

    /** Device ids are "phone-…" / "mac-…" with hex; anything else is refused at the door. */
    fun isDeviceId(s: String) = s.length in 1..64 && s.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" }

    /** Constant-time comparison, so a wrong guess can't be timed toward a right one. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** Wire format. */
object PeerJson {
    fun record(r: EntityRecord) = JSONObject().put("id", r.id).put("type", r.type)
        .put("updated", r.updated).put("deleted", r.deleted)
        .put("fields", JSONObject().apply { r.fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })

    fun record(o: JSONObject): EntityRecord {
        val f = o.optJSONObject("fields") ?: JSONObject()
        return EntityRecord(o.getString("id"), o.getString("type"), o.getString("updated"),
            f.keys().asSequence().associateWith { k -> if (f.isNull(k)) null else f.get(k).toString() },
            o.optBoolean("deleted"))
    }

    private fun changes(list: List<Logged>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("seq", it.seq).put("record", record(it.record))) }
    }

    private fun changes(a: JSONArray?) = (0 until (a?.length() ?: 0)).map {
        val o = a!!.getJSONObject(it); Logged(o.getLong("seq"), record(o.getJSONObject("record")))
    }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    fun request(r: SyncRequest) = JSONObject().put("from", r.from).put("ack", r.ack)
        .put("changes", changes(r.changes)).put("blobs", JSONArray(r.blobs)).toString()

    fun request(s: String) = JSONObject(s).let {
        SyncRequest(it.getString("from"), it.optLong("ack"), changes(it.optJSONArray("changes")),
            strings(it.optJSONArray("blobs")))
    }

    fun response(r: SyncResponse) = JSONObject().put("ack", r.ack).put("changes", changes(r.changes))
        .put("more", r.more).put("want", JSONArray(r.want)).toString()

    fun response(s: String) = JSONObject(s).let {
        SyncResponse(it.optLong("ack"), changes(it.optJSONArray("changes")), it.optBoolean("more"),
            strings(it.optJSONArray("want")))
    }
}

/** A simple in-memory [PeerStore]: the reference behaviour, used by the tests. */
class MemoryPeerStore : PeerStore {
    private val records = LinkedHashMap<String, EntityRecord>()
    private val log = ArrayList<Triple<Long, String, String?>>()   // seq, id, origin
    private val cursors = HashMap<String, Cursor>()
    private var seq = 0L

    /** A local edit. */
    fun put(r: EntityRecord) { records[r.id] = r; log += Triple(++seq, r.id, null) }
    fun all(): List<EntityRecord> = records.values.toList()

    override fun get(id: String) = records[id]
    override fun applyFromPeer(record: EntityRecord, peer: String) {
        records[record.id] = record; log += Triple(++seq, record.id, peer)
    }
    override fun changesSince(seq: Long, exclude: String?, limit: Int): List<Logged> {
        // Only the latest entry per record matters: it carries the current state.
        val latest = log.filter { it.first > seq }.associateBy { it.second }.values
            .sortedBy { it.first }
        return latest.filter { it.third == null || it.third != exclude }
            .take(limit).map { Logged(it.first, records.getValue(it.second)) }
    }
    override fun lastSeq() = seq
    override fun cursor(peer: String) = cursors[peer] ?: Cursor()
    override fun setCursor(peer: String, cursor: Cursor) { cursors[peer] = cursor }
}
