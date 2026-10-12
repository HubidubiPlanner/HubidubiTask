package com.hubidubitask.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.agent.AgentConfig
import com.hubidubitask.core.nowUtc
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reviewing a project sums up what happened in it from its tasks and notes;
 * the summary then stands under the project's title on a long press, and is
 * marked out of date once a task under it changes.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewNoteTest {
    /** With REVIEW_SHOTS set, each state is also saved there as a PNG, to be looked at. */
    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String) {
        val dir = File(System.getenv("REVIEW_SHOTS") ?: return).apply { mkdirs() }
        // The sheet is a window of its own: the last root is the one on top.
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(dir, "$name.png"))
    }

    private val summary = "The worktop is ordered; the electrician is still to be called."

    @Test
    fun `a project's review is a summary kept under its title`() = runComposeUiTest {
        val asked = mutableListOf<String>()
        // An OpenAI-shaped server on this machine that always answers the same.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                val out = when (ex.requestURI.path) {
                    "/v1/models" -> """{"data":[{"id":"m"}]}"""
                    "/v1/chat/completions" -> {
                        synchronized(asked) { asked += body }
                        JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                            .put("message", JSONObject().put("role", "assistant").put("content", summary)))).toString()
                    }
                    else -> "{}"
                }.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
            }
            start()
        }
        try {
            val store = Store(Files.createTempDirectory("hubi").toFile())
            store.put(EntityRecord("p_1", "project", nowUtc(), mapOf("title" to "Kitchen")))
            store.put(EntityRecord("t_1", "next_action", nowUtc(),
                mapOf("text" to "Order the worktop", "parent" to "p_1", "done" to "true")))
            store.put(EntityRecord("t_2", "next_action", nowUtc(), mapOf("text" to "Call the electrician", "parent" to "p_1")))
            val vm = HubidubiTaskViewModel(store, PeerServer(store, "Test Mac") {})
            vm.setAgentConfig(AgentConfig("http://127.0.0.1:${server.address.port}", protocol = "openai", model = "m"))
            kotlinx.coroutines.runBlocking { vm.checkInference() }
            waitUntil(timeoutMillis = 10_000) { vm.state.value.inference.connected && vm.state.value.projects.isNotEmpty() }

            // Never reviewed: the long press shows no review, not even its box.
            setContent { App(vm, "projects", dark = false) }
            onNodeWithText("Kitchen").performTouchInput { longClick() }
            waitForIdle()
            onAllNodesWithText("Review").assertCountEquals(0)

            // Reviewed: the agent is asked what happened, from the tasks, done ones too.
            onNodeWithText("Review with AI").performClick()
            waitUntil(timeoutMillis = 10_000) { store.get("p_1")?.fields?.get("review") == summary }
            val prompt = synchronized(asked) { asked.single() }
            assertTrue("what has happened in this project" in prompt, prompt)
            assertTrue("Order the worktop" in prompt && "Call the electrician" in prompt, prompt)

            // The long press now shows it under the title, up to date.
            setContent { App(vm, "projects", dark = false) }
            waitUntil(timeoutMillis = 5_000) { vm.state.value.reviews["p_1"]?.current == true }
            onNodeWithText("Kitchen").performTouchInput { longClick() }
            waitForIdle()
            onNodeWithText(summary).assertExists()
            onAllNodesWithText("Not up to date").assertCountEquals(0)
            shot("review-current")

            // A task added under it: the summary stays, marked out of date.
            vm.addTask(store.get("p_1")!!, "Buy tiles")
            waitUntil(timeoutMillis = 5_000) { vm.state.value.reviews["p_1"]?.current == false }
            setContent { App(vm, "projects", dark = false) }
            onNodeWithText("Kitchen").performTouchInput { longClick() }
            waitForIdle()
            onNodeWithText(summary).assertExists()
            onNodeWithText("Not up to date").assertExists()
            shot("review-stale")
        } finally {
            server.stop(0)
        }
    }
}
