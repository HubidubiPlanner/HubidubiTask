package com.hubidubitask.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A file sealed with a password, so a backup is noise to anyone without it.
 *
 * AES-256-GCM over 1 MiB chunks. The key comes from the password by
 * PBKDF2-HMAC-SHA256 (600 000 rounds, a fresh 16-byte salt per file), done
 * here over the password's UTF-8 bytes so the phone and the computer derive
 * the same key whatever their crypto provider does with characters like ą.
 * Each chunk has its own nonce (a fresh 8-byte prefix and its number) and is
 * bound to the header and to whether it is the last: a chunk dropped,
 * reordered, changed or cut off makes the file fail to open, never half open.
 *
 * Layout: "HBTSEAL1", version 1, rounds (4 bytes), salt (16), nonce prefix (8);
 * then chunks, each a 4-byte length (the top bit marks the last) and that
 * many bytes of ciphertext with its 16-byte tag.
 */
object Sealed {
    private val MAGIC = "HBTSEAL1".toByteArray()
    private const val VERSION = 1
    private const val HEADER = 8 + 1 + 4 + 16 + 8
    const val ROUNDS = 600_000
    private const val MIN_ROUNDS = 100_000
    private const val MAX_ROUNDS = 10_000_000
    private const val CHUNK = 1 shl 20
    private const val TAG = 16
    private const val LAST = Int.MIN_VALUE

    /** The password is not this file's, or the first chunk was changed. */
    class WrongPassword : Exception("wrong password")
    /** The file was changed or cut short after its first chunk. */
    class Damaged : Exception("damaged or incomplete")

    fun isSealed(head: ByteArray) = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** What is written to the returned stream reaches [out] sealed; closing it finishes the file. */
    fun seal(out: OutputStream, password: CharArray, rounds: Int = ROUNDS, chunk: Int = CHUNK): OutputStream {
        require(password.isNotEmpty() && chunk in 1..CHUNK && rounds in MIN_ROUNDS..MAX_ROUNDS)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteArrayOutputStream().apply {
            write(MAGIC); write(VERSION); write(int(rounds)); write(salt); write(prefix)
        }.toByteArray()
        out.write(header)
        val key = key(password, salt, rounds)
        return object : OutputStream() {
            private val buf = ByteArray(chunk)
            private var n = 0
            private var index = 0
            private var closed = false

            override fun write(b: Int) {
                if (n == buf.size) emit(last = false)
                buf[n++] = b.toByte()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var left = len
                while (left > 0) {
                    if (n == buf.size) emit(last = false)
                    val k = minOf(left, buf.size - n)
                    System.arraycopy(b, o, buf, n, k)
                    n += k; o += k; left -= k
                }
            }

            private fun emit(last: Boolean) {
                val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, header, last).doFinal(buf, 0, n)
                out.write(int(if (last) sealed.size or LAST else sealed.size))
                out.write(sealed)
                n = 0
            }

            override fun close() {
                if (closed) return
                closed = true
                emit(last = true)
                out.close()
            }
        }
    }

    /** The contents of the sealed file read from [input]. Throws [WrongPassword] or [Damaged]. */
    fun open(input: InputStream, password: CharArray): InputStream {
        val header = exactly(input, HEADER) ?: throw Damaged()
        if (!isSealed(header) || header[8].toInt() != VERSION) throw Damaged()
        val rounds = int(header, 9)
        if (rounds !in MIN_ROUNDS..MAX_ROUNDS) throw Damaged()
        val salt = header.copyOfRange(13, 29)
        val prefix = header.copyOfRange(29, 37)
        val key = key(password, salt, rounds)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                // The end of the file before its last chunk: cut short.
                val length = int(exactly(input, 4) ?: throw Damaged(), 0)
                val last = length and LAST != 0
                val n = length and LAST.inv()
                if (n < TAG || n > CHUNK + TAG) throw Damaged()
                val sealed = exactly(input, n) ?: throw Damaged()
                plain = try {
                    cipher(Cipher.DECRYPT_MODE, key, prefix, index, header, last).doFinal(sealed)
                } catch (e: AEADBadTagException) {
                    throw if (index == 0) WrongPassword() else Damaged()
                }
                index++; pos = 0
                if (last) {
                    done = true
                    if (input.read() != -1) throw Damaged()
                }
                return true
            }

            override fun read(): Int {
                while (pos == plain.size) if (!next()) return -1
                return plain[pos++].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos == plain.size) if (!next()) return -1
                val k = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, k)
                pos += k
                return k
            }

            override fun close() = input.close()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, header: ByteArray, last: Boolean) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG * 8, prefix + int(index)))
            updateAAD(header)
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }

    private fun key(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        try {
            return SecretKeySpec(pbkdf2(bytes, salt, rounds), "AES")
        } finally {
            bytes.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 for one 32-byte block (RFC 8018), over bytes as given. */
    internal fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        val u = mac.doFinal()
        val t = u.copyOf()
        repeat(rounds - 1) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun int(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
            (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

    /** Exactly [n] bytes, or null when the stream ends first. */
    private fun exactly(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = input.read(b, got, n - got)
            if (k < 0) return null
            got += k
        }
        return b
    }
}
