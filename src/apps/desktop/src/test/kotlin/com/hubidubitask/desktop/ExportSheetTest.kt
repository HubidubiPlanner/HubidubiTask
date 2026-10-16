package com.hubidubitask.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test

/** Settings › Export asks for a password twice, and lets it through only when both agree and are long enough. */
@OptIn(ExperimentalTestApi::class)
class ExportSheetTest {
    @Test
    fun `export waits for a password typed twice`() = runComposeUiTest {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        setContent { App(vm, "settings", dark = false) }
        onNodeWithText("Export").performScrollTo().performClick()
        waitForIdle()
        // The sheet's two fields are the last ones: Settings has its own under it.
        val all = onAllNodes(hasSetTextAction())
        val count = all.fetchSemanticsNodes().size
        val fields = listOf(all[count - 2], all[count - 1])
        val sheetExport = onAllNodes(hasText("Export") and hasClickAction())
        fun exportButton() = sheetExport[sheetExport.fetchSemanticsNodes().size - 1]

        exportButton().assertIsNotEnabled()
        fields[0].performTextInput("short")
        onNodeWithText("At least 8 characters").assertExists()
        fields[0].performTextInput(" but long now")
        fields[1].performTextInput("short but long no")
        onNodeWithText("The passwords differ.").assertExists()
        exportButton().assertIsNotEnabled()
        fields[1].performTextInput("w")
        onAllNodesWithText("Without it, the backup can't be opened.").fetchSemanticsNodes().isNotEmpty()
        exportButton().assertIsEnabled()

        System.getenv("REVIEW_SHOTS")?.let { dir ->
            val roots = onAllNodes(isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            ImageIO.write(image.toAwtImage(), "png", File(File(dir).apply { mkdirs() }, "export-sheet.png"))
        }
    }
}
