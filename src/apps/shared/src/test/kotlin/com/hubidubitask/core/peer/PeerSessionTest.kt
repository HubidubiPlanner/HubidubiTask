package com.hubidubitask.core.peer

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PeerSessionTest {
    private val key = PeerCrypto.bytes(32)
    private var now = 0L

    private fun open(k: ByteArray = key): Pair<PeerSession.Phone, PeerSession.Computer> {
        val opener = PeerSession.Opener(k, "phone-1", "mac-1")
        val (answer, computer) = assertNotNull(PeerSession.accept(key, "phone-1", "mac-1", opener.hello, now))
        return assertNotNull(opener.open(answer)) to computer
    }

    @Test
    fun `a request and its answer travel both ways`() {
        val (phone, computer) = open()
        val (seq, body) = phone.seal(JSONObject().put("op", "sync"), "hello".toByteArray())
        val (got, frame) = assertNotNull(computer.openRequest(body))
        assertEquals(seq, got)
        assertEquals("sync", frame.meta.getString("op"))
        assertContentEquals("hello".toByteArray(), frame.payload)
        val answer = computer.sealAnswer(seq, JSONObject().put("status", 200), "back".toByteArray())
        val back = assertNotNull(phone.openAnswer(seq, answer))
        assertEquals(200, back.meta.getInt("status"))
        assertContentEquals("back".toByteArray(), back.payload)
    }

    @Test
    fun `a hello under another key is not answered`() {
        val opener = PeerSession.Opener(PeerCrypto.bytes(32), "phone-1", "mac-1")
        assertNull(PeerSession.accept(key, "phone-1", "mac-1", opener.hello, now))
        // Nor one claiming to be another phone.
        assertNull(PeerSession.accept(key, "phone-2", "mac-1", PeerSession.Opener(key, "phone-1", "mac-1").hello, now))
    }

    @Test
    fun `an answer to someone else's hello is refused`() {
        val first = PeerSession.Opener(key, "phone-1", "mac-1")
        val second = PeerSession.Opener(key, "phone-1", "mac-1")
        val (answer, _) = assertNotNull(PeerSession.accept(key, "phone-1", "mac-1", first.hello, now))
        assertNull(second.open(answer))
    }

    @Test
    fun `any changed byte is refused`() {
        val (phone, computer) = open()
        val (_, body) = phone.seal(JSONObject().put("op", "sync"), ByteArray(100))
        for (i in body.indices step 7) {
            val bad = body.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(computer.openRequest(bad), "byte $i")
        }
        assertNotNull(computer.openRequest(body))
    }

    @Test
    fun `a request is taken once and only upward`() {
        val (phone, computer) = open()
        val (_, first) = phone.seal(JSONObject().put("op", "a"))
        val (_, second) = phone.seal(JSONObject().put("op", "b"))
        assertNotNull(computer.openRequest(second))
        assertNull(computer.openRequest(first))     // older than what was taken
        assertNull(computer.openRequest(second))    // played again
    }

    @Test
    fun `a request from another session is refused`() {
        val (old, _) = open()
        val (_, computer) = open()
        assertNull(computer.openRequest(old.seal(JSONObject().put("op", "sync")).second))
    }

    @Test
    fun `answers can't be swapped or reflected`() {
        val (phone, computer) = open()
        val (s0, r0) = phone.seal(JSONObject().put("op", "a"))
        val (s1, r1) = phone.seal(JSONObject().put("op", "b"))
        computer.openRequest(r0); computer.openRequest(r1)
        val a0 = computer.sealAnswer(s0, JSONObject().put("n", 0))
        assertNull(phone.openAnswer(s1, a0))                         // the answer to another request
        assertNull(phone.openAnswer(s1, r1.copyOfRange(8, r1.size))) // the phone's own request sent back
        assertNotNull(phone.openAnswer(s0, a0))
    }

    @Test
    fun `two sessions of one phone run side by side`() {
        val (p1, c1) = open()
        val (p2, c2) = open()
        val sessions = PeerSessions { now }
        sessions.add("phone-1", c1); sessions.add("phone-1", c2)
        val (s1, b1) = p1.seal(JSONObject().put("op", "x"))
        val (s2, b2) = p2.seal(JSONObject().put("op", "y"))
        assertEquals(s1, s2)   // both start at 0: separate sessions, separate keys
        assertNotNull(sessions.find("phone-1", p2.sidHex)!!.openRequest(b2))
        assertNotNull(sessions.find("phone-1", p1.sidHex)!!.openRequest(b1))
    }

    @Test
    fun `sessions are capped per phone and expire`() {
        val sessions = PeerSessions { now }
        val opened = (0..PeerSessions.PER_PEER).map { open() }
        opened.forEach { sessions.add("phone-1", it.second) }
        assertNull(sessions.find("phone-1", opened.first().first.sidHex))   // the oldest went
        assertNotNull(sessions.find("phone-1", opened.last().first.sidHex))
        assertNull(sessions.find("phone-2", opened.last().first.sidHex))    // another phone's id
        // In use, a session lives on; idle, it goes; and never past its maximum.
        now += PeerSessions.IDLE_MS - 1
        assertNotNull(sessions.find("phone-1", opened.last().first.sidHex))
        now += PeerSessions.IDLE_MS - 1
        assertNotNull(sessions.find("phone-1", opened.last().first.sidHex))
        now += PeerSessions.IDLE_MS
        assertNull(sessions.find("phone-1", opened.last().first.sidHex))

        val busy = open()
        sessions.add("phone-1", busy.second.also { it.usedAt = now })
        repeat((PeerSessions.MAX_MS / PeerSessions.IDLE_MS).toInt() + 1) {
            now += PeerSessions.IDLE_MS - 1
            sessions.find("phone-1", busy.first.sidHex)
        }
        assertNull(sessions.find("phone-1", busy.first.sidHex))
    }
}
