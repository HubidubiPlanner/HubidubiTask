package com.hubidubitask.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings is a list of dropdowns: the AI agent, the devices, the due date and
 * the language. Choosing changes what is shown: the Model field only for
 * SelfHostedAI, the API key only for Claude.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsDropdownTest {
    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) {
        val dir = File(System.getenv("REVIEW_SHOTS") ?: return).apply { mkdirs() }
        val roots = onAllNodes(isRoot())
        ImageIO.write(roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage(), "png",
            File(dir, "$name.png"))
    }

    @Test
    fun `the dropdowns choose, and show only what the choice needs`() = runComposeUiTest {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        setContent { App(vm, "settings", dark = false) }
        waitForIdle()
        shot("settings-top")

        // SelfHostedAI, the default: its address and its model.
        onNodeWithText("Model").assertExists()
        onNodeWithText("SelfHostedAI").performClick()
        waitForIdle()
        shot("settings-ai-open")
        onAllNodesWithText("Claude").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        onNodeWithText("Claude API key").assertExists()
        assertEquals(0, onAllNodesWithText("Model").fetchSemanticsNodes().size)
        // OpenAI: an address, and no model asked for.
        onNodeWithText("Claude").performClick()
        onAllNodesWithText("OpenAI").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        onNodeWithText("Server address").assertExists()
        assertEquals(0, onAllNodesWithText("Model").fetchSemanticsNodes().size)

        // The language, from its list.
        onNodeWithText("English").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Polski").performClick()
        waitForIdle()
        assertEquals(Language.PL, vm.language.value)
        onAllNodes(hasText("JĘZYK")).fetchSemanticsNodes().isNotEmpty()
        vm.setLanguage(Language.EN)
        waitForIdle()
        onNodeWithText("Pair device".uppercase()).performScrollTo()
        shot("settings-bottom")
    }
}
