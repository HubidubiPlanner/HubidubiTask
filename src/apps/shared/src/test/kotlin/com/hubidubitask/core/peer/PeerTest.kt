package com.hubidubitask.core.peer

import com.hubidubitask.core.EntityRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PeerTest {

    private val phone = MemoryPeerStore()
    private val mac = MemoryPeerStore()
    private val responder = PeerResponder(mac)
    // Through the wire format, as it really travels.
    private val syncer = PeerSyncer(phone, peer = "mac", self = "phone", send = { req ->
        PeerJson.response(PeerJson.response(responder.handle("phone",
            PeerJson.request(PeerJson.request(req)))))
    })

    private fun rec(id: String, at: String, text: String, deleted: Boolean = false) =
        EntityRecord(id, "next_action", at, if (deleted) emptyMap() else mapOf("text" to text), deleted)

    @Test
    fun `edits on either side reach the other`() {
        phone.put(rec("t1", "2026-09-27T10:00:00Z", "from phone"))
        mac.put(rec("t2", "2026-09-27T10:00:01Z", "from mac"))

        syncer.round()

        assertEquals(setOf("t1", "t2"), phone.all().map { it.id }.toSet())
        assertEquals(setOf("t1", "t2"), mac.all().map { it.id }.toSet())
    }

    @Test
    fun `the newer edit of the same item wins on both sides`() {
        phone.put(rec("t", "2026-09-27T10:00:00Z", "first")); syncer.round()
        phone.put(rec("t", "2026-09-27T10:05:00Z", "phone, later"))
        mac.put(rec("t", "2026-09-27T10:03:00Z", "mac, earlier"))

        syncer.round()

        assertEquals("phone, later", phone.get("t")!!.fields["text"])
        assertEquals("phone, later", mac.get("t")!!.fields["text"])
    }

    @Test
    fun `an older edit arriving does not overwrite a newer one`() {
        phone.put(rec("t", "2026-09-27T10:00:00Z", "old")); syncer.round()
        mac.put(rec("t", "2026-09-27T11:00:00Z", "mac newer"))
        phone.put(rec("t", "2026-09-27T10:30:00Z", "phone older"))

        syncer.round()

        assertEquals("mac newer", phone.get("t")!!.fields["text"])
        assertEquals("mac newer", mac.get("t")!!.fields["text"])
    }

    @Test
    fun `a deletion travels like any change`() {
        phone.put(rec("t", "2026-09-27T10:00:00Z", "x")); syncer.round()
        mac.put(rec("t", "2026-09-27T10:10:00Z", "", deleted = true))

        syncer.round()

        assertTrue(phone.get("t")!!.deleted)
    }

    @Test
    fun `nothing is echoed back to where it came from`() {
        phone.put(rec("t1", "2026-09-27T10:00:00Z", "x"))
        val first = syncer.round()
        val second = syncer.round()
        assertEquals(1, first.sent)
        assertEquals(0, second.sent, "the phone resent what the Mac already has")
        assertEquals(0, second.received, "the Mac echoed the phone's own change back")
    }

    @Test
    fun `a large backlog goes over in batches`() {
        repeat(1200) { phone.put(rec("t$it", "2026-09-27T10:00:00Z", "n$it")) }
        repeat(700) { mac.put(rec("m$it", "2026-09-27T10:00:00Z", "n$it")) }

        syncer.round()

        assertEquals(1900, phone.all().size)
        assertEquals(1900, mac.all().size)
    }

    @Test
    fun `a tie is settled the same way on both sides`() {
        val a = rec("t", "2026-09-27T10:00:00Z", "a")
        val b = rec("t", "2026-09-27T10:00:00Z", "b")
        assertNotEquals(Lww.incomingWins(a, b), Lww.incomingWins(b, a))
        assertFalse(Lww.incomingWins(a, a))
    }

    @Test
    fun `codes are eight characters of the code alphabet`() {
        val code = PeerPairing.newCode()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code))
        assertTrue(PeerPairing.isValidCode(code))
        assertTrue(PeerPairing.isValidCode(code.lowercase().replace("-", " ")))
        assertFalse(PeerPairing.isValidCode("ABCD-EFG"))         // too short
        assertFalse(PeerPairing.isValidCode("ABCD-EFGHJ"))       // too long
        assertFalse(PeerPairing.isValidCode("ABCD-EFG0"))        // 0 is not in the alphabet
        assertFalse(PeerPairing.isValidCode("ABCD-EFGĄ"))        // nor is any other letter
        assertFalse(PeerPairing.isValidCode("ABCD_EFGH"))
    }
}
