package com.hubidubios.desktop

import java.io.File

/** The few things that differ between the Mac and Linux builds of the desktop app. */
object Platform {
    private val os = System.getProperty("os.name").lowercase()
    val isMac = os.contains("mac")
    val isLinux = os.contains("linux")

    /** Where the app keeps its database, attachments and settings. */
    fun dataDir(): File = if (isMac)
        File(System.getProperty("user.home"), "Library/Application Support/HubidubiOS")
    else File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.local/share"), "hubidubios")

    /** The name this computer shows phones when they look for it. */
    fun computerName(): String = runCatching {
        if (isMac) ProcessBuilder("scutil", "--get", "ComputerName").start()
            .inputStream.bufferedReader().readText().trim()
        else File("/etc/hostname").takeIf { it.exists() }?.readText()?.trim()
            ?: java.net.InetAddress.getLocalHost().hostName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (isMac) "Mac" else "Computer"
}
