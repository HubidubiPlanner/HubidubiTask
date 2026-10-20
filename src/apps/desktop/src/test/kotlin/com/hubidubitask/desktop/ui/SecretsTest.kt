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

    /** A pretend `security`: the login Keychain, holding what `-X` gives it. */
    private class Keychain {
        val items = HashMap<String, ByteArray>()
        val commands = mutableListOf<List<String>>()
        fun run(cmd: List<String>, input: String?): String? {
            commands += cmd
            if (cmd == listOf("security", "-i")) {
                val words = input!!.trim().split(" ")
                val key = words[words.indexOf("-s") + 1] + "/" + words[words.indexOf("-a") + 1]
                val hex = words[words.indexOf("-X") + 1]
                items[key] = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                return ""
            }
            val key = cmd[cmd.indexOf("-s") + 1] + "/" + cmd[cmd.indexOf("-a") + 1]
            return when (cmd[1]) {
                // Like the real tool, plain ASCII is printed as it is and anything else as hex.
                "find-generic-password" -> items[key]?.let { b ->
                    if (b.all { it in 32..126 }) String(b, Charsets.US_ASCII)
                    else b.joinToString("") { "%02x".format(it) }
                }
                "delete-generic-password" -> { items.remove(key); "" }
                else -> null
            }
        }
    }

    @Test
    fun `on the Mac the secret goes in on stdin, never on the command line, and comes back whole`() {
        val k = Keychain()
        val mac = SecretStore(true, { false }, k::run, { dir })
        val secret = "sk-ant zażółć \"quoted\" \$x"
        mac.put("claude_api_key", secret)
        assertTrue(k.commands.none { cmd -> cmd.any { secret in it || "sk-ant" in it } })
        assertEquals(secret, mac.get("claude_api_key"))
    }

    @Test
    fun `an item saved by an older version still reads as it was`() {
        val k = Keychain()
        k.items["HubidubiTask/claude_api_key"] = "sk-old".toByteArray()
        assertEquals("sk-old", SecretStore(true, { false }, k::run, { dir }).get("claude_api_key"))
    }
}
