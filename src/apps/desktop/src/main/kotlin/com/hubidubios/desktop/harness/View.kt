package com.hubidubios.desktop.harness

import com.hubidubios.mobile.tree.ProjectTree
import java.io.File
import java.time.LocalDate

/**
 * The user's items written out as plain Markdown for one harness request, so
 * Claude Code's Read, Glob and Grep have files to work on. A fresh copy each
 * time; nothing written here is read back. Changes go through the MCP tools.
 */
object View {
    fun write(items: Items, dir: File, today: LocalDate = LocalDate.now()) {
        dir.deleteRecursively(); dir.mkdirs()
        val all = items.all()
        val byId = all.associateBy { it.id }
        fun md(r: com.hubidubios.core.EntityRecord): String {
            val box = if (items.isTask(r)) (if (items.open(r)) "- [ ] " else "- [x] ") else "- "
            val due = r.fields["due"]?.takeIf { it.isNotBlank() }?.let { " (due $it)" }.orEmpty()
            val body = r.fields["body"]?.takeIf { it.isNotBlank() }?.let { "\n  " + it.replace("\n", "\n  ") }.orEmpty()
            return "$box${items.title(r)}$due  <!-- id:${r.id} -->$body"
        }

        File(dir, "README.md").writeText("""
            # The user's HubidubiOS items (read-only copy, $today)

            - `projects/<name>.md`: each project's tasks and notes
            - `inbox.md`: captures and tasks in no project
            - `calendar.md`: events from today on
            - `notes/`: notes in no project

            This copy is rewritten for every question. To change anything, use
            the hubidubios tools (add_task, capture, mark_done).
        """.trimIndent() + "\n")

        val pdir = File(dir, "projects").apply { mkdirs() }
        for (p in items.projects()) {
            val inside = items.inProject(p)
            val text = buildString {
                append("# ${items.title(p)}\n\n")
                p.fields["body"]?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                inside.filter { it.type != "note" }.forEach { append(md(it)).append('\n') }
                val notes = inside.filter { it.type == "note" }
                if (notes.isNotEmpty()) { append("\n## Notes\n\n"); notes.forEach { append(md(it)).append('\n') } }
            }
            File(pdir, ProjectTree.slugOf(items.title(p)).ifBlank { p.id } + ".md").writeText(text)
        }

        File(dir, "inbox.md").writeText("# Inbox and loose tasks\n\n" +
            items.loose().joinToString("\n") { md(it) } + "\n")

        File(dir, "calendar.md").writeText("# Calendar from $today\n\n" + all
            .filter { it.type == "event" && (it.fields["dtstart"] ?: "") >= today.toString() }
            .sortedBy { it.fields["dtstart"] }
            .joinToString("\n") { "- ${it.fields["dtstart"]} ${it.fields["summary"].orEmpty()}" +
                (it.fields["location"]?.let { l -> " @ $l" } ?: "") } + "\n")

        val ndir = File(dir, "notes").apply { mkdirs() }
        all.filter { it.type == "note" && items.projectOf(it, byId) == null && it.fields["parent"] == null }
            .forEach { n ->
                File(ndir, (ProjectTree.slugOf(items.title(n)).ifBlank { n.id }) + ".md")
                    .writeText("# ${items.title(n)}\n\n${n.fields["body"].orEmpty()}\n")
            }
    }
}
