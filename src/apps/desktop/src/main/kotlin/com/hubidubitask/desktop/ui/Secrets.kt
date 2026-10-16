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
        else -> (if (secretTool)
            run(listOf("secret-tool", "lookup", "service", service, "account", account), null) else null)
            ?.takeIf { it.isNotEmpty() }
            ?: if (service == SERVICE) file(account).takeIf { it.exists() }?.readText()?.trim() else null
    }?.takeIf { it.isNotEmpty() }

    fun put(account: String, secret: String) {
        val blank = secret.isBlank()
        if (mac) {
            if (blank) run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", account), null)
            // -U updates an existing item instead of failing on a duplicate.
            else run(listOf("security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", secret), null)
            return
        }
        if (blank) {
            if (secretTool) run(listOf("secret-tool", "clear", "service", SERVICE, "account", account), null)
            file(account).delete()
            return
        }
        // secret-tool reads the secret from stdin, so it never shows in `ps`.
        val inKeyring = secretTool && run(listOf("secret-tool", "store", "--label", "$SERVICE $account",
            "service", SERVICE, "account", account), secret) != null
        if (inKeyring) { file(account).delete(); return }
        file(account).let { f ->
            f.parentFile.mkdirs()
            f.writeText(secret)
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
    }

    private fun file(account: String) = File(dir(), account)

    private companion object {
        const val SERVICE = "HubidubiTask"
        /** The app's name up to 1.7.0: keys saved then are found and moved over. */
        const val OLD_SERVICE = "HubidubiOS"
    }
}

private fun runTool(cmd: List<String>, input: String?): String? = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
    p.outputStream.use { o -> input?.let { o.write(it.toByteArray()) } }
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else null
}.getOrNull()
