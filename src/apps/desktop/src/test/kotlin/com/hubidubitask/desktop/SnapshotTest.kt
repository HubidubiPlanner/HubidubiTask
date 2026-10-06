package com.hubidubitask.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.nowUtc
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Renders each tab to PNG (SNAPSHOT_DIR) so the UI can be looked at without a screen. */
class SnapshotTest {
    @Test
    fun `render every tab`() {
        val out = System.getenv("SNAPSHOT_DIR")?.let(::File) ?: return
        out.mkdirs()
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val today = java.time.LocalDate.now().toString()
        store.put(EntityRecord("p_home", "project", nowUtc(), mapOf("title" to "Kitchen renovation")))
        store.put(EntityRecord("t_1", "next_action", nowUtc(), mapOf("text" to "Order worktop samples",
            "parent" to "p_home", "due" to today)))
        store.put(EntityRecord("t_2", "next_action", nowUtc(), mapOf("text" to "Call the electrician",
            "parent" to "p_home")))
        store.put(EntityRecord("n_1", "note", nowUtc(), mapOf("title" to "Oak, 3 m", "parent" to "t_1")))
        store.put(EntityRecord("t_3", "next_action", nowUtc(), mapOf("text" to "Buy printer toner")))
        val server = PeerServer(store, "Test Mac") {}
        val vm = HubidubiTaskViewModel(store, server,
            com.hubidubitask.desktop.harness.Harness(com.hubidubitask.desktop.harness.Items(store), store.dir))
        repeat(100) { if (vm.state.value.projects.isEmpty()) Thread.sleep(100) }
        for (tab in listOf("projects", "next", "feeds", "chat", "settings", "import")) {
            val scene = ImageComposeScene(1080, 780, Density(1f)) { App(vm, tab) }
            repeat(3) { scene.render(it * 16_000_000L) }
            val img = scene.render(100_000_000L)
            File(out, "$tab.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
