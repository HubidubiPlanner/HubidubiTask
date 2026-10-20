package com.hubidubitask.desktop

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings › Export, sealed with a password, then Import › HubidubiTask backup into an empty app: all of it comes back. */
class BackupRoundTripTest {
    @Test
    fun `an exported backup restores every item and its photo`() = runBlocking {
        val home = Store(Files.createTempDirectory("hubi").toFile())
        val sha = home.putBlob("a photo of the tiles".toByteArray())
        home.put(EntityRecord("p_1", "project", "2026-10-01T10:00:00Z", mapOf("title" to "Kitchen", "review" to "Going well.")))
        home.put(EntityRecord("t_1", "next_action", "2026-10-02T10:00:00Z",
            mapOf("text" to "Order the worktop", "parent" to "p_1", "done" to "true")))
        home.put(EntityRecord("n_1", "note", "2026-10-03T10:00:00Z",
            mapOf("title" to "Tiles", "kind" to "image", "media_sha256" to sha, "parent" to "p_1")))
        val file = Files.createTempFile("backup", ".hubidubitask").toFile()
        val (items, media) = HubidubiTaskViewModel(home, PeerServer(home, "Mac") {})
            .exportTo(file, "a long secret".toCharArray())
        assertEquals(3 to 1, items to media)
        // Sealed: none of it can be read from the file without the password.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue("Kitchen" !in raw && "Order the worktop" !in raw && "backup.json" !in raw)

        val fresh = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(fresh, PeerServer(fresh, "Mac") {})
        val plan = vm.planBackup(file, "a long secret".toCharArray())
        assertEquals(3, plan.added)
        assertEquals(3, vm.restoreBackup(file, plan, "a long secret".toCharArray()))
        assertEquals(home.all().sortedBy { it.id }, fresh.all().sortedBy { it.id })
        assertEquals("a photo of the tiles", fresh.blob(sha)!!.readText())

        // Restoring the same file again changes nothing.
        assertEquals(0, vm.planBackup(file, "a long secret".toCharArray()).incoming.size)
    }

    @Test
    fun `a backup sealed with a shorter password from before still opens`() = runBlocking {
        val home = Store(Files.createTempDirectory("hubi").toFile())
        home.put(EntityRecord("t_1", "next_action", "2026-10-02T10:00:00Z", mapOf("text" to "Order the worktop")))
        val file = Files.createTempFile("backup", ".hubidubitask").toFile()
        // Export asks for 12 characters now; files sealed when 8 were enough must still come back.
        HubidubiTaskViewModel(home, PeerServer(home, "Mac") {}).exportTo(file, "8 chars!".toCharArray())

        val fresh = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(fresh, PeerServer(fresh, "Mac") {})
        val plan = vm.planBackup(file, "8 chars!".toCharArray())
        assertEquals(1, vm.restoreBackup(file, plan, "8 chars!".toCharArray()))
        assertEquals("Order the worktop", fresh.get("t_1")!!.fields["text"])
    }
}
