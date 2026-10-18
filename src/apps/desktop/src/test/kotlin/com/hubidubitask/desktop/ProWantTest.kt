package com.hubidubitask.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.core.pro.ProInterest
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings › Pro: one click sends one answer, once; then the card says thanks. */
@OptIn(ExperimentalTestApi::class)
class ProWantTest {
    private class Form(var code: Int) : AutoCloseable {
        val bodies = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                synchronized(bodies) { bodies += ex.requestBody.readBytes().decodeToString() }
                ex.sendResponseHeaders(code, -1); ex.close()
            }
            start()
        }
        val form get() = ProInterest.Form("http://127.0.0.1:${server.address.port}/forms/d/e/F/formResponse",
            "entry.1", "entry.2", "entry.3")
        override fun close() = server.stop(0)
    }

    @Test
    fun `Sign up for Pro is sent once, and thanked`() = runComposeUiTest {
        val store = Store(Files.createTempDirectory("hubi").toFile())
        Form(500).use { f ->
            val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {}).apply { proForm = f.form }
            setContent { App(vm, "settings", dark = false) }
            // The form refuses it: said so, and the button stays.
            onNodeWithText("Sign up for Pro: sync this computer with your phone").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("That didn't go through", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            f.code = 200
            onNodeWithText("Sign up for Pro: sync this computer with your phone").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Thanks, you're signed up for Pro", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, onAllNodes(hasText("Sign up for Pro", substring = true)).fetchSemanticsNodes().size)
            val sent = f.bodies.last().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            assertTrue(sent["entry.1"] in setOf("mac", "linux"))
            assertEquals("dev", sent["entry.2"]); assertEquals("en", sent["entry.3"])
            assertEquals(setOf("entry.1", "entry.2", "entry.3"), sent.keys)
        }
        // Opened again: it remembers, and asks nothing.
        val again = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
        setContent { App(again, "settings", dark = false) }
        waitForIdle()
        onNodeWithText("Thanks, you're signed up for Pro", substring = true).assertExists()
        assertEquals(0, onAllNodes(hasText("Sign up for Pro", substring = true)).fetchSemanticsNodes().size)
    }
}
