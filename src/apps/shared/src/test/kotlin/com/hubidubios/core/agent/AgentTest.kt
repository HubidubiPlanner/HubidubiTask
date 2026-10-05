package com.hubidubios.core.agent

import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AgentTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 case 1`() {
        val okm = Crypto.hkdf(hex("0b".repeat(22)), hex("f0f1f2f3f4f5f6f7f8f9"), 42,
            salt = hex("000102030405060708090a0b0c"))
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"), okm)
    }

    @Test
    fun `hkdf with an empty salt matches RFC 5869 case 3`() {
        // selfhostedai derives with salt=b"" — our default of HashLen zeros is the same key.
        val okm = Crypto.hkdf(hex("0b".repeat(22)), ByteArray(0), 42)
        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun `encrypt and decrypt round trip`() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("""{"token":"zażółć"}""", Crypto.decrypt(key, Crypto.encrypt(key, """{"token":"zażółć"}""")))
    }

    @Test
    fun `both sides of the key exchange reach the same key`() {
        // Play the server: our side gets its point, it gets ours.
        val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val (clientKey, clientPoint) = Crypto.agree(Crypto.x962(server.public as ECPublicKey))
        val clientPub = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(
                java.math.BigInteger(1, clientPoint.copyOfRange(1, 33)),
                java.math.BigInteger(1, clientPoint.copyOfRange(33, 65))),
                (server.public as ECPublicKey).params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(server.private); doPhase(clientPub, true); generateSecret() }
        assertContentEquals(Crypto.hkdf(shared, "session-key".toByteArray(), 32), clientKey)
    }

    @Test
    fun `reasoning blocks are dropped, an unfinished one takes the rest`() {
        assertEquals("Answer.", Text.stripReasoning("<think>hmm</think>Answer."))
        assertEquals("Start", Text.stripReasoning("Start<think>and never finished"))
    }

    @Test
    fun `echoed template and second answers are cut`() {
        assertEquals("Buy milk.", Text.trimEcho("Buy milk.\nCurrent prompt: blah"))
        assertEquals("One.", Text.trimEcho("One.\nAnswer: Two."))
    }

    @Test
    fun `flatten keeps the question and trims the instructions`() {
        val p = Text.flatten(listOf(Message("system", "x".repeat(5000)), Message("user", "What next?")), 300)
        assert(p.endsWith("What next?\n\nAnswer:"))
        assert(p.length <= 300)
    }
}
