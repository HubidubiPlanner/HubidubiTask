package com.hubidubitask.core.peer

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The few primitives pairing and sync are built from, in one place: HKDF and
 * HMAC over SHA-256, AES-256-GCM, ML-KEM-768 (post-quantum), and a transcript
 * that hashes length-prefixed fields so two different field lists can never
 * hash the same. BouncyCastle is used through its own API only, never
 * registered as a JCA provider (Android has its own "BC").
 */
internal object PeerCrypto {
    val random = SecureRandom()

    // ML-KEM-768 sizes (FIPS 203).
    const val EK_BYTES = 1184
    const val CT_BYTES = 1088
    private val KEM = MLKEMParameters.ml_kem_768

    fun bytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String, length: Int = 32): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
        return ByteArray(length).also { g.generateBytes(it, 0, length) }
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** AES-256-GCM with a 128-bit tag; the tag rides at the end of the result. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /** The plaintext, or null when anything about [sealed] was changed. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        if (sealed.size < 16) null
        else runCatching { gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed) }.getOrNull()

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32 && nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    // ---- ML-KEM-768

    fun kemPair(): AsymmetricCipherKeyPair = MLKEMKeyPairGenerator().apply {
        init(MLKEMKeyGenerationParameters(random, KEM))
    }.generateKeyPair()

    fun kemPublic(pair: AsymmetricCipherKeyPair): ByteArray =
        (pair.public as MLKEMPublicKeyParameters).encoded

    /** (ciphertext, shared secret) for the encapsulation key [ek]. */
    fun encapsulate(ek: ByteArray): Pair<ByteArray, ByteArray> {
        require(ek.size == EK_BYTES)
        val s = MLKEMGenerator(random).generateEncapsulated(MLKEMPublicKeyParameters(KEM, ek))
        return s.encapsulation to s.secret
    }

    fun decapsulate(pair: AsymmetricCipherKeyPair, ct: ByteArray): ByteArray {
        require(ct.size == CT_BYTES)
        return MLKEMExtractor(pair.private as MLKEMPrivateKeyParameters).extractSecret(ct)
    }

    // ---- encodings

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** Exactly [size] bytes from base64, or an exception: lengths are checked before any maths. */
    fun unb64(s: String, size: Int? = null): ByteArray {
        val b = Base64.getDecoder().decode(s)
        require(size == null || b.size == size) { "wrong length" }
        return b
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** [v] as exactly [size] unsigned big-endian bytes. */
    fun fixed(v: BigInteger, size: Int): ByteArray {
        require(v.signum() >= 0 && v.bitLength() <= size * 8)
        val raw = v.toByteArray()
        val out = ByteArray(size)
        val start = if (raw.size > size) raw.size - size else 0
        System.arraycopy(raw, start, out, size - (raw.size - start), raw.size - start)
        return out
    }

    fun long(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

    fun long(b: ByteArray, at: Int = 0): Long =
        (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xff) }

    fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    fun int(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
            (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)
}

/** Fields hashed as length || bytes, in a fixed order: never re-serialised JSON. */
internal class Transcript(label: String) {
    private val out = ByteArrayOutputStream()

    init { add(label) }

    fun add(b: ByteArray) = apply { out.write(PeerCrypto.int(b.size)); out.write(b) }
    fun add(s: String) = add(s.toByteArray(Charsets.UTF_8))
    fun bytes(): ByteArray = out.toByteArray()
    fun hash(): ByteArray = PeerCrypto.sha256(bytes())
}
