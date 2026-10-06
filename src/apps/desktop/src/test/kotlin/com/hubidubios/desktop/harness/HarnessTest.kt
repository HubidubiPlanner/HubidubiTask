package com.hubidubios.desktop.harness

import com.hubidubios.core.EntityRecord
import com.hubidubios.core.agent.Message
import com.hubidubios.desktop.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessTest {
    private val dir = Files.createTempDirectory("harness").toFile()
    private val store = Store(dir)
    private val items = Items(store)
    private val today = LocalDate.now()

    init {
        store.put(EntityRecord("p_web", "project", "2026-10-01T00:00:00Z", mapOf("title" to "Website", "status" to "active")))
        store.put(EntityRecord("t_1", "next_action", "2026-10-01T00:00:00Z",
            mapOf("text" to "Pick a hosting provider", "parent" to "p_web", "project" to "website", "due" to today.toString())))
        store.put(EntityRecord("t_2", "next_action", "2026-10-01T00:00:00Z",
            mapOf("text" to "Draft the landing copy", "parent" to "p_web", "project" to "website")))
        store.put(EntityRecord("i_1", "inbox_item", "2026-10-01T00:00:00Z", mapOf("text" to "Look into a standing desk")))
    }

    private val mcp = AppMcp(items).start()
    @AfterTest fun stop() = mcp.stop()

    private fun post(body: JSONObject, token: String? = mcp.token): HttpResponse<String> =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:${mcp.port}/mcp"))
            .header("Content-Type", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString())

    private fun rpc(method: String, params: JSONObject = JSONObject()) =
        JSONObject(post(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)).body())

    private fun tool(name: String, args: JSONObject = JSONObject()) =
        rpc("tools/call", JSONObject().put("name", name).put("arguments", args)).getJSONObject("result")

    private fun textOf(r: JSONObject) = r.getJSONArray("content").getJSONObject(0).getString("text")

    @Test fun `the MCP endpoint wants its token`() {
        assertEquals(401, post(JSONObject().put("id", 1).put("method", "ping"), token = null).statusCode())
        assertEquals(401, post(JSONObject().put("id", 1).put("method", "ping"), token = "wrong").statusCode())
        assertEquals(202, post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")).statusCode())
    }

    @Test fun `it offers the item tools`() {
        val names = (rpc("tools/list").getJSONObject("result").getJSONArray("tools") as JSONArray)
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(listOf("search", "upcoming", "projects", "project", "inbox", "add_task", "capture", "mark_done"), names)
    }

    @Test fun `the tools read the user's items`() {
        assertTrue(textOf(tool("search", JSONObject().put("query", "hosting"))).contains("Pick a hosting provider · due $today · in Website"))
        assertTrue(textOf(tool("upcoming")).contains("Pick a hosting provider"))
        assertTrue(textOf(tool("projects")).contains("Website · 2 open"))
        assertTrue(textOf(tool("project", JSONObject().put("name", "website"))).contains("Draft the landing copy"))
        assertTrue(textOf(tool("inbox")).contains("standing desk"))
    }

    @Test fun `the tools change items only as asked, and say what is wrong`() {
        assertTrue(textOf(tool("add_task", JSONObject().put("text", "Buy a domain").put("project", "Website")
            .put("due", "2026-10-20"))).startsWith("Added:"))
        assertTrue(items.inProject(items.findProject("Website")!!).any { items.title(it) == "Buy a domain" })
        assertTrue(textOf(tool("capture", JSONObject().put("text", "Call Ana"))).startsWith("Captured:"))
        assertTrue(textOf(tool("mark_done", JSONObject().put("id", "t_2"))).startsWith("Done:"))
        assertEquals("true", store.get("t_2")!!.fields["done"])
        assertTrue(tool("add_task", JSONObject().put("text", "x").put("project", "Nope")).getBoolean("isError"))
        assertTrue(tool("add_task", JSONObject().put("text", "x").put("due", "next week")).getBoolean("isError"))
        assertTrue(tool("Bash").getBoolean("isError"))
    }

    @Test fun `the Markdown copy holds projects, inbox and calendar`() {
        val view = File(dir, "view")
        View.write(items, view)
        val web = File(view, "projects/website.md").readText()
        assertTrue(web.contains("- [ ] Pick a hosting provider (due $today)"))
        assertTrue(File(view, "inbox.md").readText().contains("Look into a standing desk"))
        assertTrue(File(view, "README.md").exists() && File(view, "calendar.md").exists())
    }

    /** A stand-in `claude` that writes down how it was started. */
    private val seen = File(dir, "seen.txt")
    private fun fakeClaude(): String {
        val f = File(dir, "claude")
        f.writeText("#!/bin/sh\ncat > /dev/null\n{ pwd; printf '%s\\n' \"\$@\"; } > '${seen.path}'\n" +
            "printf '{\"is_error\":false,\"result\":\"ok\"}'\n")
        f.setExecutable(true)
        return f.path
    }

    /** (working folder, arguments one per element) of the last stand-in run. */
    private fun lastRun(): Pair<String, List<String>> = seen.readLines().let { it.first() to it.drop(1) }

    @Test fun `with tools it runs restricted in the copy, with only the chosen tools and our MCP`() {
        assertEquals("ok", Harness(items, dir, fakeClaude()).ask(listOf(Message("user", "What next?")), tools = true))
        val (cwd, args) = lastRun()
        assertTrue(cwd.endsWith("harness/view"))
        assertTrue("--restricted" in args && "--strict-mcp-config" in args)
        assertEquals("none", args[args.indexOf("--permission-prompts") + 1])
        assertEquals("Read,Glob,Grep,WebFetch,Task", args[args.indexOf("--tools") + 1])
        assertEquals("Read,Glob,Grep,WebFetch,Task,mcp__hubidubios", args[args.indexOf("--allowedTools") + 1])
        val server = JSONObject(args[args.indexOf("--mcp-config") + 1]).getJSONObject("mcpServers").getJSONObject("hubidubios")
        assertTrue(server.getString("url").startsWith("http://127.0.0.1:"))
        assertFalse(args.any { it.contains("Bash") })
    }

    @Test fun `without tools every tool is off and it runs in an empty folder`() {
        Harness(items, dir, fakeClaude()).ask(listOf(Message("system", "Be brief."), Message("user", "x")), tools = false)
        val (cwd, args) = lastRun()
        assertTrue(cwd.endsWith("harness/empty"))
        assertEquals("", args[args.indexOf("--tools") + 1])
        assertFalse("--mcp-config" in args || "--allowedTools" in args)
        assertEquals("Be brief.", args[args.indexOf("--system-prompt") + 1])
    }

    /** Against the real Claude Code; run with HUBIDUBIOS_LIVE_HARNESS=1. */
    @Test fun `live - it answers from the user's items`() {
        if (System.getenv("HUBIDUBIOS_LIVE_HARNESS") != "1") return
        val answer = Harness(items, dir).ask(listOf(Message("system", "Answer in one sentence."),
            Message("user", "What is due today in my Website project?")), tools = true)
        println("LIVE: $answer")
        assertTrue(answer.contains("hosting", ignoreCase = true))
    }
}
