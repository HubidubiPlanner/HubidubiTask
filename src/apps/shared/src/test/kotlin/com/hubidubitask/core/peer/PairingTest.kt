package com.hubidubitask.core.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingTest {
    private var now = 0L
    private val desk = PairingDesk { now }

    @Test
    fun `the same code on both sides pairs, and both can verify it`() {
        val code = PeerPairing.newCode(); desk.open(code)
        val req = PairingClient.request(code.lowercase(), "phone-1", "Pixel", "fp")
        val out = desk.answer(PairRequest.of(req.json()), "fp", "mac-1", "MacBook")
        val resp = PairResponse.of(assertIs<PairingDesk.Outcome.Paired>(out).response.json())
        assertTrue(PairingClient.verify(code, req, resp, "fp"))
        assertEquals(PairingDesk.Outcome.NotOpen, desk.answer(req, "fp", "mac-1", "MacBook"),
            "a code must work once")
    }

    @Test
    fun `a machine in the middle, showing another certificate, is refused`() {
        val code = PeerPairing.newCode(); desk.open(code)
        val req = PairingClient.request(code, "phone-1", "Pixel", seenFingerprint = "attacker-fp")
        assertEquals(PairingDesk.Outcome.Rejected, desk.answer(req, "real-fp", "mac-1", "Mac"))
    }

    @Test
    fun `five wrong codes shut the desk, and an old code expires`() {
        desk.open("ABCD-EFGH")
        repeat(5) { desk.answer(PairingClient.request("ZZZZ-ZZZZ", "p", "p", "fp"), "fp", "m", "m") }
        assertEquals(PairingDesk.Outcome.NotOpen,
            desk.answer(PairingClient.request("ABCD-EFGH", "p", "p", "fp"), "fp", "m", "m"))

        desk.open("ABCD-EFGH"); now += PeerPairing.CODE_TTL_MS + 1
        assertEquals(PairingDesk.Outcome.NotOpen,
            desk.answer(PairingClient.request("ABCD-EFGH", "p", "p", "fp"), "fp", "m", "m"))
    }

    @Test
    fun `polling before the code is typed does not burn attempts`() {
        repeat(20) { desk.answer(PairingClient.request("ABCD-EFGH", "p", "p", "fp"), "fp", "m", "m") }
        desk.open("ABCD-EFGH")
        assertIs<PairingDesk.Outcome.Paired>(
            desk.answer(PairingClient.request("ABCD-EFGH", "p", "p", "fp"), "fp", "m", "m"))
    }
}
