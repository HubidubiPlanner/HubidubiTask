package com.hubidubitask.desktop.ui

import com.hubidubitask.desktop.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Secrets (API keys) where the system keeps them: the login Keychain on the
 * Mac, through its `security` tool; the Secret Service keyring on Linux,
 * through `secret-tool`. A Linux whose keyring can't take a key (none
 * installed, none running, or locked) gets a file only this user can read,
 * beside the app's data, rather than no place at all; a key kept there is
 * still found once a keyring appears.
 */
internal object Secrets : SecretStore(
    mac = Platform.isMac,
    hasSecretTool = { System.getenv("PATH").orEmpty().split(':').any { File(it, "secret-tool").canExecute() } },
    run = ::runTool,
    dir = { File(Platform.dataDir(), "secrets") },
)

internal open class SecretStore(
    private val mac: Boolean,
    hasSecretTool: () -> Boolean,
    /** A tool's output, or null when it failed; the second argument goes to its stdin. */
    private val run: (List<String>, String?) -> String?,
    private val dir: () -> File,
) {
    private val secretTool by lazy(hasSecretTool)

    fun get(account: String): String? = read(SERVICE, account)
        ?: read(OLD_SERVICE, account)?.also { put(account, it) }

    private fun read(service: String, account: String): String? = when {
        mac -> run(listOf("security", "find-generic-password", "-s", service, "-a", account, "-w"), null)
            ?.let(::fromKeychain)
        else -> (if (secretTool)
            run(listOf("secret-tool", "lookup", "service", service, "account", account), null) else null)
            ?.takeIf { it.isNotEmpty() }
            ?: if (service == SERVICE) file(account).takeIf { it.exists() }?.readText()?.trim() else null
    }?.takeIf { it.isNotEmpty() }

    /** Saves (or, blank, removes) the secret; false when the system refused to store it. */
    fun put(account: String, secret: String): Boolean {
        val blank = secret.isBlank()
        if (mac) {
            if (blank) { run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", account), null); return true }
            // The command goes in on stdin (`security -i`), so the secret never
            // shows in the process list; -X takes it as hex, so nothing needs
            // quoting; -U updates an existing item instead of failing on a duplicate.
            else {
                require(account.all { it.isLetterOrDigit() || it in "._-" }) { "account name" }
                val data = (PREFIX + hex(secret.toByteArray(Charsets.UTF_8))).toByteArray(Charsets.US_ASCII)
                return run(listOf("security", "-i"), "add-generic-password -U -s $SERVICE -a $account -X ${hex(data)}\n") != null
            }
        }
        if (blank) {
            if (secretTool) run(listOf("secret-tool", "clear", "service", SERVICE, "account", account), null)
            file(account).delete()
            return true
        }
        // secret-tool reads the secret from stdin, so it never shows in `ps`.
        val inKeyring = secretTool && run(listOf("secret-tool", "store", "--label", "$SERVICE $account",
            "service", SERVICE, "account", account), secret) != null
        if (inKeyring) { file(account).delete(); return true }
        file(account).let { f ->
            f.parentFile.mkdirs()
            runCatching { Files.setPosixFilePermissions(f.parentFile.toPath(), PosixFilePermissions.fromString("rwx------")) }
            // Made owner-only before a byte is written, then moved into place.
            val tmp = Files.createTempFile(f.parentFile.toPath(), ".new-", "",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(tmp, secret.toByteArray(Charsets.UTF_8))
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        return true
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * What `security -w` printed, as the secret. Our own items hold "hx:" and
     * the secret's UTF-8 in hex (plain ASCII, which `-w` prints as it is);
     * an older item holds the secret itself.
     */
    private fun fromKeychain(printed: String): String =
        if (printed.startsWith(PREFIX) && (printed.length - PREFIX.length) % 2 == 0 &&
            printed.drop(PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' })
            String(ByteArray((printed.length - PREFIX.length) / 2) {
                printed.substring(PREFIX.length + it * 2, PREFIX.length + it * 2 + 2).toInt(16).toByte()
            }, Charsets.UTF_8)
        else printed

    private fun file(account: String) = File(dir(), account)

    private companion object {
        const val SERVICE = "HubidubiTask"
        /** The app's name up to 1.7.0: keys saved then are found and moved over. */
        const val OLD_SERVICE = "HubidubiOS"
        const val PREFIX = "hx:"
    }
}

private fun runTool(cmd: List<String>, input: String?): String? = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
    p.outputStream.use { o -> input?.let { o.write(it.toByteArray()) } }
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
}.getOrNull()
