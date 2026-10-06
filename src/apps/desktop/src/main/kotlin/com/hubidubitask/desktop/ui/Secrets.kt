package com.hubidubitask.desktop.ui

import com.hubidubitask.desktop.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Secrets (API keys) where the system keeps them: the login Keychain on the
 * Mac, through its `security` tool; the Secret Service keyring on Linux,
 * through `secret-tool`. A Linux without a keyring gets a file only this user
 * can read, beside the app's data, rather than no place at all.
 */
internal object Secrets {
    private const val SERVICE = "HubidubiTask"
    /** The app's name up to 1.7.0: keys saved then are found and moved over. */
    private const val OLD_SERVICE = "HubidubiOS"

    fun get(account: String): String? = read(SERVICE, account)
        ?: read(OLD_SERVICE, account)?.also { put(account, it) }

    private fun read(service: String, account: String): String? = when {
        Platform.isMac -> run("security", "find-generic-password", "-s", service, "-a", account, "-w")
        hasSecretTool() -> run("secret-tool", "lookup", "service", service, "account", account)
        else -> if (service == SERVICE) file(account).takeIf { it.exists() }?.readText()?.trim() else null
    }?.takeIf { it.isNotEmpty() }

    fun put(account: String, secret: String) {
        val blank = secret.isBlank()
        when {
            Platform.isMac ->
                if (blank) run("security", "delete-generic-password", "-s", SERVICE, "-a", account)
                // -U updates an existing item instead of failing on a duplicate.
                else run("security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", secret)
            hasSecretTool() ->
                if (blank) run("secret-tool", "clear", "service", SERVICE, "account", account)
                // secret-tool reads the secret from stdin, so it never shows in `ps`.
                else run("secret-tool", "store", "--label", "$SERVICE $account", "service", SERVICE,
                    "account", account, input = secret)
            else -> file(account).let { f ->
                if (blank) { f.delete(); return }
                f.parentFile.mkdirs()
                f.writeText(secret)
                runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }
        }
    }

    private fun file(account: String) = File(Platform.dataDir(), "secrets/$account")

    private val secretTool by lazy {
        System.getenv("PATH").orEmpty().split(':').any { File(it, "secret-tool").canExecute() }
    }
    private fun hasSecretTool() = secretTool

    private fun run(vararg cmd: String, input: String? = null): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        p.outputStream.use { o -> input?.let { o.write(it.toByteArray()) } }
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0) out else null
    }.getOrNull()
}
