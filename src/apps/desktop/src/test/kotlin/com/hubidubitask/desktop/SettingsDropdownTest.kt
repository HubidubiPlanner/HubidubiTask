package com.hubidubitask.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.core.i18n.Language
import com.hubidubitask.desktop.ui.ColorTheme
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings is a table of the due date, the language, the theme and the
 * devices, each row opening its list, and the AI agent under it as before. Choosing changes what is shown: the Model field only for
 * SelfHostedAI, the API key only for Claude; there is no Save, a choice keeps itself.
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

        // Claude, the default: its key's field alone, untitled; no address, no model, no Save.
        assertEquals(0, onAllNodesWithText("Claude API key").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Model").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Save").fetchSemanticsNodes().size)
        onNodeWithText("Claude").performClick()
        waitForIdle()
        shot("settings-ai-open")
        // SelfHostedAI: its address and its model, untitled; the model empty, its default as the placeholder.
        onAllNodesWithText("SelfHostedAI").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        assertEquals(2, onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
        onNodeWithText("Model: fast").assertExists()
        assertEquals(0, onAllNodesWithText("Server address").fetchSemanticsNodes().size)
        // OpenAI: an address alone, and no model asked for.
        onNodeWithText("SelfHostedAI").performClick()
        onAllNodesWithText("OpenAI").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForIdle()
        assertEquals(1, onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Model", substring = true).fetchSemanticsNodes().size)
        // The choice keeps itself, a moment later.
        mainClock.advanceTimeBy(1_500)
        waitUntil(timeoutMillis = 5_000) { vm.agentConfig().protocol == "openai" }

        // The language, from its list.
        onNodeWithText("English").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Polski").performClick()
        waitForIdle()
        assertEquals(Language.PL, vm.language.value)
        onNodeWithText("Język").assertExists()
        vm.setLanguage(Language.EN)
        waitForIdle()
        // Devices: each with when it last synced and a tick once it has, and a
        // button on its right that unpairs it; New device after them.
        store.addPeer(Store.Peer("phone-1", "Pixel", "2026-10-18T09:00:00Z", null))
        store.addPeer(Store.Peer("phone-2", "Phone", "2026-10-18T09:00:00Z", "2026-10-18T10:00:00Z"))
        vm.refresh()
        waitUntil(timeoutMillis = 5_000) { vm.state.value.peers.size == 2 }
        onNodeWithText("Devices").performScrollTo()
        assertEquals(0, onAllNodesWithText("not synced yet").fetchSemanticsNodes().size)
        onNodeWithText("Devices").performClick()
        waitForIdle()
        onNodeWithText("not synced yet").assertExists()
        assertEquals(1, onAllNodes(hasContentDescription("Synced")).fetchSemanticsNodes().size)
        onNodeWithText("New device").assertExists()
        shot("settings-devices-open")
        onNode(hasContentDescription("Unpair Pixel")).performClick()
        waitUntil(timeoutMillis = 5_000) { vm.state.value.peers.map { it.name } == listOf("Phone") }

        // The colour theme, from its list, each saying whether it is light or dark; kept.
        onNodeWithText("Theme").performScrollTo()
        onNodeWithText("Sand").performClick()
        waitForIdle()
        onNodeWithText("Follows the system").assertExists()
        assertEquals(2, onAllNodesWithText("Dark").fetchSemanticsNodes().size)
        shot("settings-theme-open")
        onNodeWithText("Night").performClick()
        waitForIdle()
        assertEquals(ColorTheme.NIGHT, vm.theme.value)
        assertEquals("night", store.kv("theme"))
        assertEquals(ColorTheme.NIGHT, HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {}).theme.value)
        shot("settings-night")
    }
}
