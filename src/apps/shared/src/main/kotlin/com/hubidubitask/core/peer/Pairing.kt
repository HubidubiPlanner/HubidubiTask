package com.hubidubitask.core.peer

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.json.JSONObject
import java.math.BigInteger

/**
 * Pairing by the typed 8-character code, version 2.
 *
 * The code is the password of a PAKE (J-PAKE, RFC 8236): someone in the
 * middle learns nothing they could test guesses against offline, so the only
 * way in is to guess the code live, once per try, and each device gives up
 * after five wrong tries. The key that comes out is mixed with an ML-KEM-768
 * secret, so a recording of the pairing stays closed even to a future
 * quantum computer: breaking the J-PAKE maths later would reveal the code,
 * which by then is used up, but not the key.
 *
 *   phone -> computer  start  {phoneId, name, round 1, ML-KEM encapsulation key}
 *   computer -> phone         {sid, computerId, name, round 1, round 2, ML-KEM ciphertext}
 *   phone -> computer  finish {sid, round 2, confirmation}
 *   computer -> phone         {confirmation}
 *
 * Both sides hash everything above, plus the certificate fingerprint the
 * phone saw, into one transcript; the key and both confirmations come from
 * it. A machine in the middle shows the phone another certificate, so the
 * transcripts differ and the confirmations fail. The computer reveals
 * nothing that depends on the code before the phone has proved it holds it.
 */
object PairV2 {
    internal const val PHONE = "hubidubitask/v2/phone"
    internal const val COMPUTER = "hubidubitask/v2/computer"
    internal val GROUP = JPAKEPrimeOrderGroups.NIST_3072
    /** Bytes of a group element and of a scalar in [GROUP]. */
    internal const val ELEMENT = 384
    internal const val SCALAR = 32

    const val SID_BYTES = 16
    const val CONFIRM_BYTES = 32

    internal fun participant(role: String, code: String) =
        JPAKEParticipant(role, PeerPairing.normalize(code).toCharArray(), GROUP, SHA256Digest(), PeerCrypto.random)

    /** The secrets one finished exchange yields. Only [sync] is ever stored. */
    internal class Keys(val sync: ByteArray, val confirmPhone: ByteArray, val confirmComputer: ByteArray)

    internal fun keys(keyingMaterial: BigInteger, kemSecret: ByteArray, transcript: ByteArray): Keys {
        val ikm = PeerCrypto.fixed(keyingMaterial, ELEMENT) + kemSecret
        val t = PeerCrypto.sha256(transcript)
        val cp = PeerCrypto.hkdf(ikm, t, "hubidubitask v2 confirm phone")
        val cc = PeerCrypto.hkdf(ikm, t, "hubidubitask v2 confirm computer")
        return Keys(PeerCrypto.hkdf(ikm, t, "hubidubitask v2 sync key"),
            PeerCrypto.hmac(cp, t), PeerCrypto.hmac(cc, t))
    }

    internal fun transcript(start: PairStart, started: PairStarted, phoneRound2: Round2, fingerprint: String): ByteArray =
        Transcript("hubidubitask pair v2").add(started.sid).add(PHONE).add(COMPUTER)
            .add(start.phoneId).add(start.name).add(started.computerId).add(started.name)
            .add(PeerCrypto.unhex(fingerprint))
            .also { start.round1.into(it); started.round1.into(it); started.round2.into(it); phoneRound2.into(it) }
            .add(start.ek).add(started.ct)
            .bytes()
}

/** A zero-knowledge proof: g^v and r. */
data class Zkp(val gv: BigInteger, val r: BigInteger)

/** J-PAKE round 1 as it travels: no participant id (each side knows the other's role). */
data class Round1(val gx1: BigInteger, val gx2: BigInteger, val zkp1: Zkp, val zkp2: Zkp) {
    internal fun payload(role: String) = JPAKERound1Payload(role, gx1, gx2,
        arrayOf(zkp1.gv, zkp1.r), arrayOf(zkp2.gv, zkp2.r))

    internal fun into(t: Transcript) {
        listOf(gx1, gx2, zkp1.gv, zkp2.gv).forEach { t.add(PeerCrypto.fixed(it, PairV2.ELEMENT)) }
        listOf(zkp1.r, zkp2.r).forEach { t.add(PeerCrypto.fixed(it, PairV2.SCALAR)) }
    }

    internal fun json() = JSONObject().put("gx1", el(gx1)).put("gx2", el(gx2))
        .put("gv1", el(zkp1.gv)).put("r1", sc(zkp1.r)).put("gv2", el(zkp2.gv)).put("r2", sc(zkp2.r))

    companion object {
        internal fun of(p: JPAKERound1Payload) = Round1(p.gx1, p.gx2,
            Zkp(p.knowledgeProofForX1[0], p.knowledgeProofForX1[1]),
            Zkp(p.knowledgeProofForX2[0], p.knowledgeProofForX2[1]))

        internal fun of(o: JSONObject) = Round1(el(o, "gx1"), el(o, "gx2"),
            Zkp(el(o, "gv1"), sc(o, "r1")), Zkp(el(o, "gv2"), sc(o, "r2")))
    }
}

/** J-PAKE round 2 as it travels. */
data class Round2(val a: BigInteger, val zkp: Zkp) {
    internal fun payload(role: String) = JPAKERound2Payload(role, a, arrayOf(zkp.gv, zkp.r))

    internal fun into(t: Transcript) {
        t.add(PeerCrypto.fixed(a, PairV2.ELEMENT)).add(PeerCrypto.fixed(zkp.gv, PairV2.ELEMENT))
            .add(PeerCrypto.fixed(zkp.r, PairV2.SCALAR))
    }

    internal fun json() = JSONObject().put("a", el(a)).put("gv", el(zkp.gv)).put("r", sc(zkp.r))

    companion object {
        internal fun of(p: JPAKERound2Payload) = Round2(p.a, Zkp(p.knowledgeProofForX2s[0], p.knowledgeProofForX2s[1]))
        internal fun of(o: JSONObject) = Round2(el(o, "a"), Zkp(el(o, "gv"), sc(o, "r")))
    }
}

// A group element: exactly 384 bytes and 1 < x < p-1. A scalar: exactly 32 bytes
// and below q. Checked before any exponentiation, so a huge number can't stall the CPU.
private fun el(v: BigInteger) = PeerCrypto.b64(PeerCrypto.fixed(v, PairV2.ELEMENT))
private fun sc(v: BigInteger) = PeerCrypto.b64(PeerCrypto.fixed(v, PairV2.SCALAR))

private fun el(o: JSONObject, k: String): BigInteger {
    val v = BigInteger(1, PeerCrypto.unb64(o.getString(k), PairV2.ELEMENT))
    require(v > BigInteger.ONE && v < PairV2.GROUP.p - BigInteger.ONE) { "out of range" }
    return v
}

private fun sc(o: JSONObject, k: String): BigInteger {
    val v = BigInteger(1, PeerCrypto.unb64(o.getString(k), PairV2.SCALAR))
    require(v < PairV2.GROUP.q) { "out of range" }
    return v
}

private fun short(o: JSONObject, k: String, max: Int = 200): String =
    o.getString(k).also { require(it.length <= max) { "too long" } }

/** A device id: it names Keychain entries and travels in headers, so letters, digits, ._- only. */
private fun id(o: JSONObject, k: String): String =
    o.getString(k).also { require(PeerPairing.isDeviceId(it)) { "bad id" } }

data class PairStart(val phoneId: String, val name: String, val round1: Round1, val ek: ByteArray) {
    fun json(): String = JSONObject().put("phoneId", phoneId).put("name", name)
        .put("round1", round1.json()).put("ek", PeerCrypto.b64(ek)).toString()

    companion object {
        fun of(s: String) = JSONObject(s).let {
            PairStart(id(it, "phoneId"), short(it, "name"), Round1.of(it.getJSONObject("round1")),
                PeerCrypto.unb64(it.getString("ek"), PeerCrypto.EK_BYTES))
        }
    }
}

data class PairStarted(val sid: String, val computerId: String, val name: String,
                       val round1: Round1, val round2: Round2, val ct: ByteArray) {
    fun json(): String = JSONObject().put("sid", sid).put("computerId", computerId).put("name", name)
        .put("round1", round1.json()).put("round2", round2.json()).put("ct", PeerCrypto.b64(ct)).toString()

    companion object {
        fun of(s: String) = JSONObject(s).let {
            PairStarted(short(it, "sid", 64), id(it, "computerId"), short(it, "name"),
                Round1.of(it.getJSONObject("round1")), Round2.of(it.getJSONObject("round2")),
                PeerCrypto.unb64(it.getString("ct"), PeerCrypto.CT_BYTES))
        }
    }
}

data class PairFinish(val sid: String, val round2: Round2, val confirm: ByteArray) {
    fun json(): String = JSONObject().put("sid", sid).put("round2", round2.json())
        .put("confirm", PeerCrypto.b64(confirm)).toString()

    companion object {
        fun of(s: String) = JSONObject(s).let {
            PairFinish(short(it, "sid", 64), Round2.of(it.getJSONObject("round2")),
                PeerCrypto.unb64(it.getString("confirm"), PairV2.CONFIRM_BYTES))
        }
    }
}

data class PairFinished(val confirm: ByteArray) {
    fun json(): String = JSONObject().put("confirm", PeerCrypto.b64(confirm)).toString()

    companion object {
        fun of(s: String) = PairFinished(PeerCrypto.unb64(JSONObject(s).getString("confirm"), PairV2.CONFIRM_BYTES))
    }
}

/**
 * The phone's half of one pairing try. Single use: a new try is a new object.
 * [finish] and [done] throw on anything malformed or wrong.
 */
class PairPhone(code: String, val phoneId: String, val name: String) {
    private val me = PairV2.participant(PairV2.PHONE, code)
    private val kem: AsymmetricCipherKeyPair = PeerCrypto.kemPair()
    val start = PairStart(phoneId, name, Round1.of(me.createRound1PayloadToSend()), PeerCrypto.kemPublic(kem))
    private var keys: PairV2.Keys? = null
    /** The computer's id and name once [finish] has run. */
    var answer: PairStarted? = null; private set

    /** The answer to the computer's [started], pinned to the certificate [fingerprint] (sha256 hex) the phone saw. */
    fun finish(started: PairStarted, fingerprint: String): PairFinish {
        check(keys == null)
        me.validateRound1PayloadReceived(started.round1.payload(PairV2.COMPUTER))
        val mine = Round2.of(me.createRound2PayloadToSend())
        me.validateRound2PayloadReceived(started.round2.payload(PairV2.COMPUTER))
        val km = me.calculateKeyingMaterial()
        val ss = PeerCrypto.decapsulate(kem, started.ct)
        val k = PairV2.keys(km, ss, PairV2.transcript(start, started, mine, fingerprint))
        keys = k; answer = started
        return PairFinish(started.sid, mine, k.confirmPhone)
    }

    /** The sync key when the computer's confirmation is right; null when it isn't. */
    fun done(finished: PairFinished): ByteArray? {
        val k = keys ?: return null
        return k.sync.takeIf { PeerCrypto.same(finished.confirm, k.confirmComputer) }
    }
}

/**
 * The computer's half: one code at a time, open for [PeerPairing.CODE_TTL_MS]
 * and shut after [PeerPairing.MAX_ATTEMPTS] wrong confirmations. A code is
 * remembered for its ten minutes: opening the same one again keeps its count
 * of wrong tries, and one that paired or ran out of tries can't be opened
 * again. A try while no code is open is refused before anything is parsed
 * and doesn't count.
 *
 * At most [SESSIONS] tries are in flight, one per network address, each for
 * [SESSION_TTL_MS]; when all are taken a new one waits (Busy) rather than
 * pushing out someone else's. New tries are taken at most [STARTS_PER_SECOND]
 * a second, so a flood can't pin the CPU (each costs a few thousand-bit
 * exponentiations).
 */
class PairDesk(private val clock: () -> Long = System::currentTimeMillis) {
    private var code: String? = null
    private var openedAt = 0L
    private val sessions = LinkedHashMap<String, Session>()
    private val starts = ArrayDeque<Long>()
    /** Each code opened in the last [PeerPairing.CODE_TTL_MS]: its wrong tries and whether it paired. */
    private val history = HashMap<String, Record>()
    /** The last finished pairing, answered again for [SESSION_TTL_MS] if the same finish comes twice. */
    private val finished = LinkedHashMap<String, Pair<ByteArray, Outcome.Paired>>()
    private var finishedAt = 0L

    private class Record(val firstOpened: Long, var failures: Int = 0, var paired: Boolean = false)

    private class Session(val at: Long, val source: String, val me: JPAKEParticipant, val start: PairStart,
                          val started: PairStarted, val kemSecret: ByteArray, val fingerprint: String)

    /** How the desk stands, for the screen that shows the code. */
    enum class State { OPEN, CLOSED, RAN_OUT, TOO_MANY_TRIES }

    @Volatile private var closedBy = State.CLOSED

    private fun tidy(now: Long) {
        history.values.removeAll { now - it.firstOpened >= PeerPairing.CODE_TTL_MS }
        sessions.values.removeAll { now - it.at >= SESSION_TTL_MS }
        if (now - finishedAt >= SESSION_TTL_MS) finished.clear()
    }

    /**
     * Opens [code] for pairing. False (and nothing opened) when it isn't a
     * valid code, or it already paired or ran out of tries in the last ten minutes.
     */
    @Synchronized fun open(code: String): Boolean {
        if (!PeerPairing.isValidCode(code)) return false
        val now = clock(); tidy(now)
        val n = PeerPairing.normalize(code)
        val r = history.getOrPut(n) { Record(now) }
        if (r.paired || r.failures >= PeerPairing.MAX_ATTEMPTS) return false
        this.code = n; openedAt = now; sessions.clear(); closedBy = State.CLOSED
        return true
    }

    @Synchronized fun close() { code = null; sessions.clear(); finished.clear() }

    @Synchronized fun isOpen() = code != null && clock() - openedAt < PeerPairing.CODE_TTL_MS

    @Synchronized fun state(): State = when {
        isOpen() -> State.OPEN
        code != null -> State.RAN_OUT
        else -> closedBy
    }

    /** A phone was unpaired: a finish remembered for it must not pair it again. */
    @Synchronized fun forget(phoneId: String) { finished.values.removeAll { it.second.phoneId == phoneId } }

    sealed interface Outcome {
        data class Started(val answer: PairStarted) : Outcome
        data class Paired(val phoneId: String, val name: String, val key: ByteArray,
                          val answer: PairFinished) : Outcome
        /** No code open, or the try is unknown or expired. */
        data object NotOpen : Outcome
        /** Too many tries at once: come back in a moment. Not a wrong try. */
        data object Busy : Outcome
        /** The phone's confirmation was wrong (counted) or its values didn't check out. */
        data object Rejected : Outcome
    }

    /** A try from the phone at [source] (its network address), answered with this computer's values. */
    @Synchronized
    fun start(req: PairStart, fingerprint: String, computerId: String, name: String, source: String = ""): Outcome {
        val c = code?.takeIf { isOpen() } ?: return Outcome.NotOpen
        val now = clock(); tidy(now)
        while (starts.isNotEmpty() && now - starts.first() >= 1000) starts.removeFirst()
        if (starts.size >= STARTS_PER_SECOND) return Outcome.Busy
        // A new try from the same address replaces its own earlier one; others are left alone.
        sessions.values.removeAll { it.source == source }
        if (sessions.size >= SESSIONS) return Outcome.Busy
        starts.addLast(now)
        val me = PairV2.participant(PairV2.COMPUTER, c)
        // BouncyCastle's order: our round 1 exists before theirs is checked.
        val round1 = Round1.of(me.createRound1PayloadToSend())
        try { me.validateRound1PayloadReceived(req.round1.payload(PairV2.PHONE)) }
        catch (e: Exception) { return Outcome.Rejected }
        val round2 = Round2.of(me.createRound2PayloadToSend())
        val (ct, ss) = PeerCrypto.encapsulate(req.ek)
        val sid = PeerCrypto.hex(PeerCrypto.bytes(PairV2.SID_BYTES))
        val started = PairStarted(sid, computerId, name, round1, round2, ct)
        sessions[sid] = Session(now, source, me, req, started, ss, fingerprint)
        return Outcome.Started(started)
    }

    @Synchronized
    fun finish(req: PairFinish): Outcome {
        val now = clock(); tidy(now)
        finished[req.sid]?.let { (confirm, paired) ->
            if (PeerCrypto.same(confirm, req.confirm)) return paired
        }
        val c = code?.takeIf { isOpen() } ?: return Outcome.NotOpen
        // Taken out at once: a session is good for one finish, never two.
        val s = sessions.remove(req.sid) ?: return Outcome.NotOpen
        val keys = try {
            s.me.validateRound2PayloadReceived(req.round2.payload(PairV2.PHONE))
            PairV2.keys(s.me.calculateKeyingMaterial(), s.kemSecret,
                PairV2.transcript(s.start, s.started, req.round2, s.fingerprint))
        } catch (e: Exception) { null }
        val record = history.getOrPut(c) { Record(now) }
        if (keys == null || !PeerCrypto.same(req.confirm, keys.confirmPhone)) {
            if (++record.failures >= PeerPairing.MAX_ATTEMPTS) { close(); closedBy = State.TOO_MANY_TRIES }
            return Outcome.Rejected
        }
        record.paired = true
        code = null; sessions.clear()   // one code, one pairing
        val paired = Outcome.Paired(s.start.phoneId, s.start.name, keys.sync, PairFinished(keys.confirmComputer))
        finished.clear()
        finished[req.sid] = req.confirm to paired
        finishedAt = now
        return paired
    }

    companion object {
        const val SESSIONS = 4
        /** A try is start and finish a moment apart; longer only holds a slot. */
        const val SESSION_TTL_MS = 15_000L
        const val STARTS_PER_SECOND = 2
    }
}
