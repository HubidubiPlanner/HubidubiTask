package com.hubidubitask.desktop

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings › Export to a file, then Import › HubidubiTask backup into an empty app: all of it comes back. */
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
        val file = Files.createTempFile("backup", ".zip").toFile()
        val (items, media) = HubidubiTaskViewModel(home, PeerServer(home, "Mac") {}).exportTo(file)
        assertEquals(3 to 1, items to media)

        val fresh = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(fresh, PeerServer(fresh, "Mac") {})
        val plan = vm.planBackup(file)
        assertEquals(3, plan.added)
        assertEquals(3, vm.restoreBackup(file, plan))
        assertEquals(home.all().sortedBy { it.id }, fresh.all().sortedBy { it.id })
        assertEquals("a photo of the tiles", fresh.blob(sha)!!.readText())

        // Restoring the same file again changes nothing.
        assertEquals(0, vm.planBackup(file).incoming.size)
    }
}
