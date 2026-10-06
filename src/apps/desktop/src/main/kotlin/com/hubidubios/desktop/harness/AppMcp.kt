package com.hubidubios.desktop.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * The user's items as MCP tools, for the Claude Code the harness runs.
 *
 * MCP's streamable-HTTP transport, the plain-JSON half of it: each POST to
 * /mcp is one JSON-RPC message, answered with JSON. It listens on 127.0.0.1
 * only, and every request must carry [token], which changes each time the
 * app starts. Nothing here runs code: the tools read and change items.
 */
class AppMcp(private val items: Items) {
    val token: String = java.util.UUID.randomUUID().toString()
    private var server: HttpServer? = null
    val port: Int get() = server?.address?.port ?: 0

    @Synchronized fun start(): AppMcp {
        if (server != null) return this
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newFixedThreadPool(2)
            createContext("/mcp") { ex -> ex.use { serve(it) } }
            start()
        }
        return this
    }

    fun stop() { server?.stop(0); server = null }

    /** The `--mcp-config` entry that points Claude Code here. */
    fun config(): JSONObject = JSONObject().put("mcpServers", JSONObject().put(NAME, JSONObject()
        .put("type", "http").put("url", "http://127.0.0.1:$port/mcp")
        .put("headers", JSONObject().put("Authorization", "Bearer $token"))))

    private fun serve(ex: HttpExchange) {
        val auth = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(auth.toByteArray(), token.toByteArray())) return reply(ex, 401, null)
        if (ex.requestMethod != "POST") return reply(ex, 405, null)
        val msg = runCatching { JSONObject(ex.requestBody.readBytes().decodeToString()) }.getOrNull()
            ?: return reply(ex, 400, rpcError(null, -32700, "parse error"))
        val answer = handle(msg)
        if (answer == null) reply(ex, 202, null) else reply(ex, 200, answer)
    }

    /** One JSON-RPC message in; the answer, or null for a notification. */
    fun handle(msg: JSONObject): JSONObject? {
        if (!msg.has("id")) return null
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result: Any = try {
            when (msg.optString("method")) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion").ifBlank { VERSION })
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", NAME).put("version", "1.0"))
                    .put("instructions", INSTRUCTIONS)
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", JSONArray(TOOLS.map { it.json() }))
                "tools/call" -> call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
                else -> return rpcError(id, -32601, "unknown method ${msg.optString("method")}")
            }
        } catch (e: Exception) {
            return rpcError(id, -32603, e.message ?: e.javaClass.simpleName)
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    /** A tools/call result; failures come back as isError text the model can read. */
    fun call(name: String, a: JSONObject): JSONObject {
        val byId by lazy { items.all().associateBy { it.id } }
        fun lines(rs: List<com.hubidubios.core.EntityRecord>, empty: String) =
            text(if (rs.isEmpty()) empty else rs.joinToString("\n") { items.line(it, byId) })
        return when (name) {
            "search" -> {
                val q = a.optString("query").ifBlank { return text("search needs a query", true) }
                lines(items.search(q, a.optInt("limit", 20).coerceIn(1, 50)), "Nothing matches \"$q\".")
            }
            "upcoming" -> lines(items.upcoming(a.optInt("days", 7).coerceIn(0, 366)), "Nothing due or scheduled.")
            "projects" -> {
                val ps = items.projects()
                text(if (ps.isEmpty()) "No projects." else ps.joinToString("\n") { p ->
                    val open = items.inProject(p).count { items.isTask(it) && items.open(it) }
                    "${items.title(p)} · $open open (id ${p.id})" })
            }
            "project" -> {
                val p = items.findProject(a.optString("name")) ?: return text("No project called \"${a.optString("name")}\".", true)
                lines(items.inProject(p), "${items.title(p)} is empty.")
            }
            "inbox" -> lines(items.loose(), "The inbox is empty.")
            "add_task" -> {
                val t = a.optString("text").ifBlank { return text("add_task needs text", true) }
                val due = a.optString("due").takeIf { it.isNotBlank() }
                if (due != null && runCatching { LocalDate.parse(due) }.isFailure) return text("due must be YYYY-MM-DD", true)
                val pname = a.optString("project").takeIf { it.isNotBlank() }
                val p = pname?.let { items.findProject(it) ?: return text("No project called \"$it\".", true) }
                text("Added: " + items.line(items.addTask(t, p, due)))
            }
            "capture" -> {
                val t = a.optString("text").ifBlank { return text("capture needs text", true) }
                text("Captured: " + items.line(items.capture(t)))
            }
            "mark_done" -> {
                val done = items.markDone(a.optString("id")) ?: return text("No open task with id ${a.optString("id")}.", true)
                text("Done: ${items.title(done.takeIf { !it.deleted } ?: byId.getValue(done.id))}")
            }
            else -> text("No tool named $name.", true)
        }
    }

    private class Tool(val name: String, val description: String, val props: Map<String, Pair<String, String>>,
                       val required: List<String> = emptyList()) {
        fun json(): JSONObject = JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().apply {
                    props.forEach { (k, v) -> put(k, JSONObject().put("type", v.first).put("description", v.second)) }
                }).put("required", JSONArray(required)))
    }

    companion object {
        const val NAME = "hubidubios"
        private const val VERSION = "2025-06-18"
        private const val INSTRUCTIONS = "The user's HubidubiOS items: tasks, projects, notes, inbox and " +
            "calendar. Look things up before answering about them. add_task, capture and mark_done change " +
            "the user's items at once; use them only when the user asked for that change."
        private val TOOLS = listOf(
            Tool("search", "Find tasks, notes, events and projects whose text matches all the words given.",
                mapOf("query" to ("string" to "Words to look for"), "limit" to ("integer" to "At most this many, 1-50")),
                listOf("query")),
            Tool("upcoming", "Open tasks due within the next days (overdue included) and events in that time, soonest first.",
                mapOf("days" to ("integer" to "How many days ahead, default 7"))),
            Tool("projects", "Every project with its number of open tasks.", emptyMap()),
            Tool("project", "One project's tasks and notes.", mapOf("name" to ("string" to "Project name or id")),
                listOf("name")),
            Tool("inbox", "Captures and open tasks that are in no project.", emptyMap()),
            Tool("add_task", "Add a task, into a project or (without one) the inbox.",
                mapOf("text" to ("string" to "What to do, starting with a verb"),
                    "project" to ("string" to "Project name; leave out for the inbox"),
                    "due" to ("string" to "Due date, YYYY-MM-DD")), listOf("text")),
            Tool("capture", "Put a thought in the inbox, exactly as given.", mapOf("text" to ("string" to "The thought")),
                listOf("text")),
            Tool("mark_done", "Mark a task done.", mapOf("id" to ("string" to "The task's id, as the other tools show it")),
                listOf("id")),
        )

        private fun text(s: String, error: Boolean = false) = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s))).put("isError", error)

        private fun rpcError(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))

        private fun reply(ex: HttpExchange, code: Int, body: JSONObject?) {
            val b = body?.toString()?.toByteArray() ?: ByteArray(0)
            if (body != null) ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
            if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }
        }
    }
}
