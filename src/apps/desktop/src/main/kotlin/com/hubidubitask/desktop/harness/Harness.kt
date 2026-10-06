package com.hubidubitask.desktop.harness

import com.hubidubitask.core.agent.AgentError
import com.hubidubitask.core.agent.Health
import com.hubidubitask.core.agent.Message
import com.hubidubitask.core.agent.ModelClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The Harness agent: Claude Code on this computer, under the login of the
 * person using it, so it runs on their own Claude plan (or on a local model,
 * through a harness.env next to the app's data; see [env]).
 *
 * Every run is `claude -p` in --restricted mode: nothing that runs commands
 * or code, no user or project settings, only our MCP server, and anything
 * outside the allowed list refused (no one is there to approve it). With
 * tools it may read a Markdown copy of the user's items (Read, Glob, Grep),
 * fetch web pages, use subagents, and use the items' own tools over MCP;
 * without, every tool is off and only text comes back. Feeds never get tools:
 * their text comes from strangers' web pages.
 */
class Harness(
    private val items: Items,
    private val dataDir: File,
    /** Tests hand in a stand-in for `claude`; the app finds the real one. */
    private val claudePath: String? = null,
) {
    private val mcp by lazy { AppMcp(items).start() }
    private val slots = Semaphore(2)

    /** The `claude` command, looked for where installers put it; GUI apps get a thin PATH. */
    fun command(): String? {
        claudePath?.let { return it }
        val home = System.getProperty("user.home")
        val dirs = System.getenv("PATH").orEmpty().split(':') + listOf("$home/.local/bin", "$home/.claude/local",
            "$home/.npm-global/bin", "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        return dirs.filter { it.isNotBlank() }.map { File(it, "claude") }.firstOrNull { it.canExecute() }?.path
    }

    fun health(): Health {
        val cmd = command() ?: return Health(false, "Claude Code", emptyList(), "Claude Code is not installed here")
        return Health(true, "Claude Code ($cmd)", listOf(MODEL))
    }

    /** One question. Throws AgentError with a sentence a person can act on. */
    fun ask(messages: List<Message>, tools: Boolean): String {
        val claude = command() ?: throw AgentError("Claude Code is not installed on this computer.")
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val turns = messages.filter { it.role != "system" }
        val prompt = if (turns.size == 1) turns[0].content else turns.joinToString("\n\n") {
            (if (it.role == "assistant") "Assistant: " else "User: ") + it.content }

        val work = File(dataDir, "harness/" + if (tools) "view" else "empty")
        if (tools) View.write(items, work) else { work.deleteRecursively(); work.mkdirs() }
        val cmd = mutableListOf(claude, "-p", "--output-format", "json", "--no-session-persistence",
            "--restricted", "--strict-mcp-config", "--permission-prompts", "none")
        if (tools) {
            cmd += listOf("--tools", TOOLS.joinToString(","), "--mcp-config", mcp.config().toString(),
                "--allowedTools", (TOOLS + "mcp__${AppMcp.NAME}").joinToString(","))
        } else cmd += listOf("--tools", "")
        val sys = if (tools) "$TOOLS_NOTE\n\n$system".trim() else system
        if (sys.isNotBlank()) cmd += listOf("--system-prompt", sys)

        val limit = if (tools) 540L else 300L
        if (!slots.tryAcquire(limit, TimeUnit.SECONDS)) throw AgentError("Claude Code is busy; try again.")
        try {
            val pb = ProcessBuilder(cmd).directory(work)
            pb.environment().putAll(env())
            val p = pb.start()
            p.outputStream.use { it.write(prompt.toByteArray()) }
            val out = StringBuilder()
            val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            val err = StringBuilder()
            val errReader = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
            if (!p.waitFor(limit, TimeUnit.SECONDS)) {
                p.destroyForcibly(); throw AgentError("Claude Code took longer than $limit s.")
            }
            reader.join(); errReader.join()
            val json = runCatching { JSONObject(out.toString().trim()) }.getOrNull()
                ?: throw AgentError((err.toString().trim().ifBlank { out.toString().trim() }).takeLast(300)
                    .ifBlank { "Claude Code exited with ${p.exitValue()}" })
            if (json.optBoolean("is_error")) throw AgentError(json.optString("result").ifBlank { "Claude Code failed" })
            return json.optString("result")
        } finally { slots.release() }
    }

    /**
     * Extra environment for Claude Code, from `harness.env` in the app's data
     * folder: KEY=VALUE lines. This is how it is pointed at a local model,
     * e.g. ANTHROPIC_BASE_URL=http://127.0.0.1:8080 and ANTHROPIC_MODEL=...
     */
    fun env(): Map<String, String> = File(dataDir, "harness.env").takeIf { it.exists() }?.readLines()
        ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
        .orEmpty()

    fun stop() = runCatching { mcp.stop() }

    /** A ModelClient for the app's own agent code: chat, reviews, feeds. */
    fun client(tools: Boolean): ModelClient = object : ModelClient {
        override fun complete(messages: List<Message>) = ask(messages, tools)
        override fun health() = this@Harness.health()
    }

    companion object {
        const val MODEL = "claude-code"
        /** Task is Claude Code's subagent tool. */
        val TOOLS = listOf("Read", "Glob", "Grep", "WebFetch", "Task")
        private const val TOOLS_NOTE =
            "You run on the user's own computer. The working folder holds a read-only Markdown copy of " +
            "their HubidubiTask items (projects/, inbox.md, calendar.md, notes/), which you can Read, Glob " +
            "and Grep. The hubidubitask tools search, list and change their items; change them only when " +
            "the user asked. You can also fetch web pages and hand a sub-task to a subagent. Look things " +
            "up before answering."
    }
}
