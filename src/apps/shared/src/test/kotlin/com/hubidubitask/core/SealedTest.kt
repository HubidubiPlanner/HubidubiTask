package com.hubidubitask.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SealedTest {
    private val password = "correct horse ąę".toCharArray()
    private val data = Random(7).nextBytes(10_000)

    /** Sealed in small chunks with the fewest rounds allowed, so the test is quick and crosses chunks. */
    private fun sealed(bytes: ByteArray = data): ByteArray = ByteArrayOutputStream().also { out ->
        Sealed.seal(out, password, rounds = 100_000, chunk = 1_000).use { it.write(bytes) }
    }.toByteArray()

    private fun open(file: ByteArray, pw: CharArray = password) =
        Sealed.open(ByteArrayInputStream(file), pw).use { it.readBytes() }

    @Test
    fun `what goes in comes out, across chunks and with an empty file`() {
        val file = sealed()
        assertTrue(Sealed.isSealed(file))
        assertFalse(String(file, Charsets.ISO_8859_1).contains(String(data.copyOf(64), Charsets.ISO_8859_1)))
        assertContentEquals(data, open(file))
        assertContentEquals(ByteArray(0), open(sealed(ByteArray(0))))
        // The same contents seal differently every time: a fresh salt and nonce.
        assertFalse(sealed().contentEquals(file))
    }

    @Test
    fun `a wrong password is said so`() {
        assertFailsWith<Sealed.WrongPassword> { open(sealed(), "correct horse ae".toCharArray()) }
    }

    @Test
    fun `a file changed or cut short does not open, not even in part`() {
        val file = sealed()
        // A byte changed in the third chunk.
        val changed = file.copyOf().also { it[37 + 2 * (4 + 1_016) + 10] = (it[37 + 2 * (4 + 1_016) + 10] + 1).toByte() }
        assertFailsWith<Sealed.Damaged> { open(changed) }
        // The last chunk dropped, and the file cut in the middle of one.
        assertFailsWith<Sealed.Damaged> { open(file.copyOf(file.size - (4 + 16))) }
        assertFailsWith<Sealed.Damaged> { open(file.copyOf(file.size - 500)) }
        // Something added after the end.
        assertFailsWith<Sealed.Damaged> { open(file + byteArrayOf(0)) }
        // A first chunk changed reads as the wrong password: the two cannot be told apart.
        val first = file.copyOf().also { it[45] = (it[45] + 1).toByte() }
        assertFailsWith<Sealed.WrongPassword> { open(first) }
    }

    @Test
    fun `the key is PBKDF2-HMAC-SHA256 over the password's UTF-8 bytes`() {
        val salt = "NaCl-and-more-16".toByteArray()
        for (pw in listOf("password", "zażółć gęślą jaźń")) {
            val ours = Sealed.pbkdf2(pw.toByteArray(Charsets.UTF_8), salt, 1_000)
            val java = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 1_000, 256)).encoded
            assertContentEquals(java, ours, pw)
        }
    }

    @Test
    fun `a sealed backup asks for its password, and an old bare one still reads`() {
        val records = listOf(EntityRecord("p_1", "project", "2026-10-01T10:00:00Z", mapOf("title" to "Kitchen")))
        val file = ByteArrayOutputStream().also {
            Backup.write(it, records, "1.3.0", password, rounds = 100_000) { null }
        }.toByteArray()
        assertTrue(Backup.isSealed(ByteArrayInputStream(file)))
        assertFailsWith<Backup.PasswordNeeded> { Backup.read(ByteArrayInputStream(file), null) }
        assertFailsWith<Sealed.WrongPassword> { Backup.read(ByteArrayInputStream(file), "nope nope".toCharArray()) }
        assertEquals(records, Backup.read(ByteArrayInputStream(file), password).records)

        val bare = ByteArrayOutputStream().also { Backup.write(it, records, "1.2.0", null) { null } }.toByteArray()
        assertFalse(Backup.isSealed(ByteArrayInputStream(bare)))
        assertEquals(records, Backup.read(ByteArrayInputStream(bare), null).records)
    }
}
