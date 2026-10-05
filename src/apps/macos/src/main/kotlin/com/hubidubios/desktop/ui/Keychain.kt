package com.hubidubios.desktop.ui

/**
 * The login Keychain, through macOS's own `security` tool: secrets stay where
 * the system keeps them, locked with the user's login, not in our SQLite file.
 */
internal object Keychain {
    private const val SERVICE = "HubidubiOS"

    fun get(account: String): String? = runCatching {
        val p = ProcessBuilder("security", "find-generic-password", "-s", SERVICE, "-a", account, "-w")
            .redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    fun put(account: String, secret: String) {
        if (secret.isBlank()) {
            runCatching {
                ProcessBuilder("security", "delete-generic-password", "-s", SERVICE, "-a", account)
                    .start().waitFor()
            }
            return
        }
        // -U updates an existing item instead of failing on a duplicate.
        runCatching {
            ProcessBuilder("security", "add-generic-password", "-U", "-s", SERVICE, "-a", account,
                "-w", secret).start().waitFor()
        }
    }
}
