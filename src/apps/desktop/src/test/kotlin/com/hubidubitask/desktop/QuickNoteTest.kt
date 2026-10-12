package com.hubidubitask.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.core.NewItemDue
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A long press on the + is a note in two taps: the cursor is in it, and Done
 * saves. New notes and tasks are due on the day Settings gives them, and
 * nothing asks for a date when one is made.
 */
@OptIn(ExperimentalTestApi::class)
class QuickNoteTest {
    @Test
    fun `long press on the plus writes a note`() = runComposeUiTest {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        fun note(title: String) = store.all().firstOrNull { it.type == "note" && it.fields["title"] == title }
        for (tab in listOf("next", "projects")) {
            setContent { App(vm, tab, dark = false) }
            onNodeWithContentDescription("New project or task").performTouchInput { longClick() }
            waitForIdle()
            onNode(isFocused() and hasSetTextAction()).performTextInput("Call the plumber from $tab")
            // Done on the phone's keyboard; Return on the computer's.
            if (tab == "next") onNode(isFocused() and hasSetTextAction()).performImeAction()
            else onNode(isFocused() and hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 5_000) { note("Call the plumber from $tab") != null }
            // Settings' default for new notes: tomorrow.
            assertEquals(LocalDate.now().plusDays(1).toString(), note("Call the plumber from $tab")!!.fields["due"])
        }
        assertEquals(2, store.all().count { it.type == "note" && it.fields["kind"] == "text" })

        // A task to the inbox: no date to choose, and due tomorrow too.
        setContent { App(vm, "next", dark = false) }
        onNodeWithContentDescription("New project or task").performClick()
        onNodeWithText("A task").performClick()
        onNodeWithText("No project").performClick()
        waitForIdle()
        onAllNodesWithText("Add a date").assertCountEquals(0)
        onNode(hasSetTextAction()).performTextInput("Buy stamps")
        onNodeWithText("Add").performClick()
        fun task(text: String) = store.all().firstOrNull { it.type == "inbox_item" && it.fields["text"] == text }
        waitUntil(timeoutMillis = 5_000) { task("Buy stamps") != null }
        assertEquals(LocalDate.now().plusDays(1).toString(), task("Buy stamps")!!.fields["due"])

        // With "No date" chosen, a new note has none.
        vm.setNewDue(NewItemDue.NONE)
        setContent { App(vm, "next", dark = false) }
        onNodeWithContentDescription("New project or task").performTouchInput { longClick() }
        waitForIdle()
        onNode(isFocused() and hasSetTextAction()).performTextInput("Undated")
        onNode(isFocused() and hasSetTextAction()).performImeAction()
        waitUntil(timeoutMillis = 5_000) { note("Undated") != null }
        assertNull(note("Undated")!!.fields["due"])
        vm.capture("Someday, maybe")
        waitUntil(timeoutMillis = 5_000) { task("Someday, maybe") != null }
        assertNull(task("Someday, maybe")!!.fields["due"])
    }
}
