package com.hubidubitask.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A long press on the + is a note in two taps: the cursor is in it, and Done saves. */
@OptIn(ExperimentalTestApi::class)
class QuickNoteTest {
    @Test
    fun `long press on the plus writes a note`() = runComposeUiTest {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        for (tab in listOf("next", "projects")) {
            setContent { App(vm, tab, dark = false) }
            onNodeWithContentDescription("New project or task").performTouchInput { longClick() }
            waitForIdle()
            onNode(isFocused() and hasSetTextAction()).performTextInput("Call the plumber from $tab")
            // Done on the phone's keyboard; Return on the computer's.
            if (tab == "next") onNode(isFocused() and hasSetTextAction()).performImeAction()
            else onNode(isFocused() and hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { store.all().any { it.type == "note" && it.fields["title"] == "Call the plumber from $tab" } }
        }
        assertEquals(2, store.all().count { it.type == "note" && it.fields["kind"] == "text" })
    }
}
