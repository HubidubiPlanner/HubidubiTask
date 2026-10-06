package com.hubidubitask.desktop

import java.io.File

/** The few things that differ between the Mac and Linux builds of the desktop app. */
object Platform {
    private val os = System.getProperty("os.name").lowercase()
    val isMac = os.contains("mac")
    val isLinux = os.contains("linux")

    private fun base(): File = if (isMac) File(System.getProperty("user.home"), "Library/Application Support")
        else File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.local/share"))

    /**
     * Where the app keeps its database, attachments, settings and the TLS
     * identity phones pin. The app was called HubidubiOS up to 1.7.0: its
     * folder is moved over on first start, so items, pairings and the
     * certificate phones trust come along.
     */
    private val dir: File by lazy { dataDirIn(base(), isMac) }

    /** The data folder under [base], moving a HubidubiOS-era one into place first. */
    internal fun dataDirIn(base: File, mac: Boolean): File {
        val dir = File(base, if (mac) "HubidubiTask" else "hubidubitask")
        val old = File(base, if (mac) "HubidubiOS" else "hubidubios")
        if (!dir.exists() && old.isDirectory) old.renameTo(dir)
        return dir
    }

    fun dataDir(): File = dir

    /** The name this computer shows phones when they look for it. */
    fun computerName(): String = runCatching {
        if (isMac) ProcessBuilder("scutil", "--get", "ComputerName").start()
            .inputStream.bufferedReader().readText().trim()
        else File("/etc/hostname").takeIf { it.exists() }?.readText()?.trim()
            ?: java.net.InetAddress.getLocalHost().hostName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (isMac) "Mac" else "Computer"
}
