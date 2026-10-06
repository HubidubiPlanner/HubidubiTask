package com.hubidubitask.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rename from HubidubiOS must not leave anyone's items behind. */
class PlatformTest {
    @Test fun `a HubidubiOS data folder moves to the new name, with everything in it`() {
        val base = Files.createTempDirectory("base").toFile()
        File(base, "hubidubios").mkdirs(); File(base, "hubidubios/hubidubios.db").writeText("items")
        File(base, "hubidubios/tls.p12").writeText("identity")
        val dir = Platform.dataDirIn(base, mac = false)
        assertEquals("hubidubitask", dir.name)
        assertEquals("items", File(dir, "hubidubios.db").readText())
        assertEquals("identity", File(dir, "tls.p12").readText())
        assertFalse(File(base, "hubidubios").exists())
        val mac = Files.createTempDirectory("mac").toFile()
        File(mac, "HubidubiOS").mkdirs()
        assertTrue(Platform.dataDirIn(mac, mac = true).let { it.name == "HubidubiTask" && it.isDirectory })
    }

    @Test fun `an existing new folder is never overwritten`() {
        val base = Files.createTempDirectory("base").toFile()
        File(base, "hubidubios").mkdirs(); File(base, "hubidubitask").mkdirs()
        File(base, "hubidubitask/keep").writeText("new")
        val dir = Platform.dataDirIn(base, mac = false)
        assertEquals("new", File(dir, "keep").readText())
        assertTrue(File(base, "hubidubios").exists())
    }
}
