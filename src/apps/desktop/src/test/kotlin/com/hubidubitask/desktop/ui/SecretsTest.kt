package com.hubidubitask.desktop.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linux: the keyring when it takes the key, else a file only this user can read. */
class SecretsTest {
    /** A pretend `secret-tool`: a keyring that may be locked. */
    private class Keyring(var locked: Boolean = false) {
        val keys = HashMap<String, String>()
        fun run(cmd: List<String>, input: String?): String? {
            if (cmd.first() != "secret-tool" || locked) return null
            val account = cmd[cmd.indexOf("account") + 1]
            val service = cmd[cmd.indexOf("service") + 1]
            return when (cmd[1]) {
                "store" -> { keys["$service/$account"] = input!!; "" }
                "lookup" -> keys["$service/$account"]
                "clear" -> { keys.remove("$service/$account"); "" }
                else -> null
            }
        }
    }

    private val dir = Files.createTempDirectory("secrets").toFile()
    private fun store(keyring: Keyring?, hasTool: Boolean = keyring != null) =
        SecretStore(false, { hasTool }, { c, i -> keyring?.run(c, i) }, { dir })

    @Test
    fun `a working keyring keeps the key, and no file is left`() {
        val k = Keyring()
        store(k).put("claude", "sk-1")
        assertEquals("sk-1", k.keys["HubidubiTask/claude"])
        assertFalse(File(dir, "claude").exists())
        assertEquals("sk-1", store(k).get("claude"))
    }

    @Test
    fun `a locked keyring falls back to a private file`() {
        val k = Keyring(locked = true)
        store(k).put("claude", "sk-2")
        val f = File(dir, "claude")
        assertEquals("sk-2", f.readText())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.toPath()))
        assertEquals("sk-2", store(k).get("claude"))
    }

    @Test
    fun `no secret-tool at all uses the file`() {
        store(null).put("agent", "tok")
        assertEquals("tok", store(null).get("agent"))
    }

    @Test
    fun `a key filed before the keyring came is still found, then moves in on the next save`() {
        store(null).put("claude", "sk-3")
        val k = Keyring()
        assertEquals("sk-3", store(k).get("claude"))
        store(k).put("claude", "sk-4")
        assertEquals("sk-4", k.keys["HubidubiTask/claude"])
        assertFalse(File(dir, "claude").exists())
    }

    @Test
    fun `a blank key clears both places`() {
        val k = Keyring()
        store(k).put("claude", "sk-5")
        k.locked = true; store(k).put("agent", "tok")
        k.locked = false
        store(k).put("claude", ""); store(k).put("agent", " ")
        assertNull(store(k).get("claude")); assertNull(store(k).get("agent"))
        assertTrue(k.keys.isEmpty())
    }
}
