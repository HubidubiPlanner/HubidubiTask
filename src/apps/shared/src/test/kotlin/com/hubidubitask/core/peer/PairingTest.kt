package com.hubidubitask.core.peer

import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private var now = 0L
    private val desk = PairDesk { now }
    private val fp = "ab".repeat(32)

    /** The phone's try against [desk], through the wire format as it really travels. */
    private fun attempt(phoneCode: String, phoneSees: String = fp, computerIs: String = fp,
                        tamper: (PairStarted) -> PairStarted = { it }): Pair<PairPhone, PairDesk.Outcome> {
        now += 1000   // a real phone tries every few seconds, well inside the rate limit
        val phone = PairPhone(phoneCode, "phone-1", "Pixel")
        val started = desk.start(PairStart.of(phone.start.json()), computerIs, "mac-1", "MacBook")
        val answer = tamper(PairStarted.of(assertIs<PairDesk.Outcome.Started>(started).answer.json()))
        val finish = phone.finish(answer, phoneSees)
        return phone to desk.finish(PairFinish.of(finish.json()))
    }

    @Test
    fun `the same code pairs, both sides hold the same key, and the code is used up`() {
        val code = PeerPairing.newCode(); assertTrue(desk.open(code))
        val (phone, out) = attempt(code.lowercase())
        val paired = assertIs<PairDesk.Outcome.Paired>(out)
        assertEquals("phone-1", paired.phoneId); assertEquals("Pixel", paired.name)
        val key = assertNotNull(phone.done(PairFinished.of(paired.answer.json())))
        assertContentEquals(paired.key, key)
        assertEquals(32, key.size)
        assertEquals("mac-1", phone.answer!!.computerId)
        assertFalse(desk.isOpen())
        assertEquals(PairDesk.Outcome.NotOpen,
            desk.start(PairStart.of(PairPhone(code, "p2", "x").start.json()), fp, "mac-1", "MacBook"))
    }

    @Test
    fun `a wrong code is rejected, counted, and five close the desk`() {
        desk.open("ABCD-EFGH")
        repeat(PeerPairing.MAX_ATTEMPTS - 1) {
            assertEquals(PairDesk.Outcome.Rejected, attempt("ABCD-EFGJ").second)
            assertTrue(desk.isOpen())
        }
        assertEquals(PairDesk.Outcome.Rejected, attempt("ABCD-EFGJ").second)
        assertFalse(desk.isOpen())
        assertEquals(PairDesk.Outcome.NotOpen,
            desk.start(PairStart.of(PairPhone("ABCD-EFGH", "p", "x").start.json()), fp, "m", "M"))
    }

    @Test
    fun `a machine in the middle with its own certificate can't pair, even relaying the right code`() {
        desk.open("ABCD-EFGH")
        val (_, out) = attempt("ABCD-EFGH", phoneSees = "cd".repeat(32))
        assertEquals(PairDesk.Outcome.Rejected, out)
    }

    @Test
    fun `a swapped ciphertext or encapsulation key fails the confirmation`() {
        desk.open("ABCD-EFGH")
        val other = PeerCrypto.encapsulate(PeerCrypto.kemPublic(PeerCrypto.kemPair())).first
        assertEquals(PairDesk.Outcome.Rejected, attempt("ABCD-EFGH") { it.copy(ct = other) }.second)

        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val swapped = phone.start.copy(ek = PeerCrypto.kemPublic(PeerCrypto.kemPair()))
        val started = assertIs<PairDesk.Outcome.Started>(desk.start(swapped, fp, "mac-1", "MacBook")).answer
        assertEquals(PairDesk.Outcome.Rejected, desk.finish(phone.finish(started, fp)))
    }

    @Test
    fun `the phone refuses its own round reflected back as the computer's`() {
        desk.open("ABCD-EFGH")
        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val started = assertIs<PairDesk.Outcome.Started>(desk.start(phone.start, fp, "mac-1", "MacBook")).answer
        assertFailsWith<Exception> { phone.finish(started.copy(round1 = phone.start.round1), fp) }
    }

    @Test
    fun `a wrong confirmation from the computer gives the phone no key`() {
        desk.open("ABCD-EFGH")
        val (phone, out) = attempt("ABCD-EFGH")
        assertIs<PairDesk.Outcome.Paired>(out)
        assertNull(phone.done(PairFinished(ByteArray(32))))
    }

    @Test
    fun `the same finish twice gets the same answer and isn't counted`() {
        desk.open("ABCD-EFGH")
        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val started = assertIs<PairDesk.Outcome.Started>(desk.start(phone.start, fp, "mac-1", "MacBook")).answer
        val finish = phone.finish(started, fp)
        val pool = Executors.newFixedThreadPool(2)
        val both = pool.invokeAll(listOf(Callable { desk.finish(finish) }, Callable { desk.finish(finish) })).map { it.get() }
        pool.shutdown()
        val (a, b) = both.map { assertIs<PairDesk.Outcome.Paired>(it) }
        assertContentEquals(a.answer.confirm, b.answer.confirm)
        assertContentEquals(a.key, b.key)
        // A forged confirmation for the same session is not answered from memory.
        assertEquals(PairDesk.Outcome.NotOpen, desk.finish(finish.copy(confirm = ByteArray(32))))
    }

    @Test
    fun `a try expires, and so does the code`() {
        desk.open("ABCD-EFGH")
        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val started = assertIs<PairDesk.Outcome.Started>(desk.start(phone.start, fp, "mac-1", "MacBook")).answer
        now += PairDesk.SESSION_TTL_MS
        assertEquals(PairDesk.Outcome.NotOpen, desk.finish(phone.finish(started, fp)))
        now += PeerPairing.CODE_TTL_MS
        assertFalse(desk.isOpen())
    }

    @Test
    fun `starts are rate limited`() {
        desk.open("ABCD-EFGH")
        repeat(PairDesk.STARTS_PER_SECOND) {
            assertIs<PairDesk.Outcome.Started>(desk.start(PairPhone("ABCD-EFGH", "p", "x").start, fp, "m", "M"))
        }
        assertEquals(PairDesk.Outcome.Busy, desk.start(PairPhone("ABCD-EFGH", "p", "x").start, fp, "m", "M"))
        now += 1000
        assertIs<PairDesk.Outcome.Started>(desk.start(PairPhone("ABCD-EFGH", "p", "x").start, fp, "m", "M"))
    }

    @Test
    fun `only real codes open the desk`() {
        assertFalse(desk.open("ABCD"))
        assertFalse(desk.open("ABCD-EFG0"))
        assertFalse(desk.isOpen())
    }

    @Test
    fun `malformed numbers are refused before any maths, fast`() {
        val good = JSONObject(PairPhone("ABCD-EFGH", "p", "x").start.json())
        val p = PairV2.GROUP.p
        fun withRound1(k: String, v: String) = JSONObject(good.toString()).also {
            it.getJSONObject("round1").put(k, v)
        }.toString()
        fun element(v: BigInteger) = PeerCrypto.b64(PeerCrypto.fixed(v, PairV2.ELEMENT))
        val started = System.nanoTime()
        for (bad in listOf(BigInteger.ZERO, BigInteger.ONE, p - BigInteger.ONE)) {
            assertFailsWith<Exception> { PairStart.of(withRound1("gx1", element(bad))) }
        }
        assertFailsWith<Exception> { PairStart.of(withRound1("gx1", PeerCrypto.b64(ByteArray(PairV2.ELEMENT + 1) { -1 }))) }
        assertFailsWith<Exception> { PairStart.of(withRound1("r1", PeerCrypto.b64(ByteArray(1 shl 20) { 7 }))) }
        assertFailsWith<Exception> { PairStart.of(withRound1("r1", PeerCrypto.b64(PeerCrypto.fixed(PairV2.GROUP.q, 32)))) }
        assertFailsWith<Exception> {
            PairStart.of(JSONObject(good.toString()).put("ek", PeerCrypto.b64(ByteArray(PeerCrypto.EK_BYTES - 1))).toString())
        }
        assertTrue(System.nanoTime() - started < 2_000_000_000L)

        // In range but not in the group's subgroup: BouncyCastle's proof check refuses it.
        desk.open("ABCD-EFGH")
        val outside = PairStart.of(withRound1("gx1", element(BigInteger.TWO)))
        assertEquals(PairDesk.Outcome.Rejected, desk.start(outside, fp, "m", "M"))
    }

    @Test
    fun `fixed-width numbers keep their leading zeros`() {
        val v = BigInteger("00ff" + "11".repeat(10), 16)
        val b = PeerCrypto.fixed(v, 384)
        assertEquals(384, b.size)
        assertEquals(v, BigInteger(1, b))
        assertFailsWith<IllegalArgumentException> { PeerCrypto.fixed(BigInteger.ONE.shiftLeft(3072), 384) }
    }

    @Test
    fun `key derivation matches an independent implementation`() {
        // Computed with Python's hmac/hashlib (RFC 5869 HKDF by hand), not with this code.
        val km = BigInteger("00ff" + "11".repeat(100), 16)
        val keys = PairV2.keys(km, ByteArray(32) { 2 }, "golden transcript".toByteArray())
        assertEquals("08131221eaf60644d2c6a19c187d55d8f215b4b95438047fba6c05389773bb7b", PeerCrypto.hex(keys.sync))
        assertEquals("a039baf327f71ac9334705148194121cae6f2e5ec3a49eaf6f63e306aac127ef", PeerCrypto.hex(keys.confirmPhone))
        assertEquals("99967ba74e54eecc6c9390b37c031bd8088aac340915808798e26d90a62ab788", PeerCrypto.hex(keys.confirmComputer))
    }

    @Test
    fun `opening the same code again keeps its wrong tries, and a spent code stays shut`() {
        desk.open("ABCD-EFGH")
        repeat(3) { assertEquals(PairDesk.Outcome.Rejected, attempt("ABCD-EFGJ").second) }
        desk.close()
        assertTrue(desk.open("ABCD-EFGH"))                 // reopened: still 3 wrong tries behind it
        repeat(2) { assertEquals(PairDesk.Outcome.Rejected, attempt("ABCD-EFGJ").second) }
        assertFalse(desk.isOpen())
        assertEquals(PairDesk.State.TOO_MANY_TRIES, desk.state())
        assertFalse(desk.open("abcd efgh"))                // ran out of tries: not again
        assertTrue(desk.open("ABCD-EFGK"))                 // a new code is fine
        assertIs<PairDesk.Outcome.Paired>(attempt("ABCD-EFGK").second)
        assertFalse(desk.open("ABCD-EFGK"))                // paired once: not again
        now += PeerPairing.CODE_TTL_MS
        assertTrue(desk.open("ABCD-EFGH"))                 // ten minutes on, forgotten
    }

    @Test
    fun `a code that runs out says so`() {
        desk.open("ABCD-EFGH")
        assertEquals(PairDesk.State.OPEN, desk.state())
        now += PeerPairing.CODE_TTL_MS
        assertEquals(PairDesk.State.RAN_OUT, desk.state())
    }

    @Test
    fun `one address can't push out another's try, and full slots say busy`() {
        desk.open("ABCD-EFGH")
        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val mine = assertIs<PairDesk.Outcome.Started>(desk.start(phone.start, fp, "m", "M", source = "10.0.0.2")).answer
        repeat(PairDesk.SESSIONS - 1) { i ->
            now += 1000
            assertIs<PairDesk.Outcome.Started>(desk.start(PairPhone("ABCD-EFGH", "x", "x").start, fp, "m", "M", "10.0.0.${10 + i}"))
        }
        now += 1000
        assertEquals(PairDesk.Outcome.Busy, desk.start(PairPhone("ABCD-EFGH", "x", "x").start, fp, "m", "M", "10.0.0.99"))
        // The real phone's try is still there.
        assertIs<PairDesk.Outcome.Paired>(desk.finish(phone.finish(mine, fp)))
    }

    @Test
    fun `an unpaired phone isn't paired again by a remembered finish`() {
        desk.open("ABCD-EFGH")
        val phone = PairPhone("ABCD-EFGH", "phone-1", "Pixel")
        val started = assertIs<PairDesk.Outcome.Started>(desk.start(phone.start, fp, "mac-1", "MacBook")).answer
        val finish = phone.finish(started, fp)
        assertIs<PairDesk.Outcome.Paired>(desk.finish(finish))
        desk.forget("phone-1")
        assertEquals(PairDesk.Outcome.NotOpen, desk.finish(finish))
    }
}
