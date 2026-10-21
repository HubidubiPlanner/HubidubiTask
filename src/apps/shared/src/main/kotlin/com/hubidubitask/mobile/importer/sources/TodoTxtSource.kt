package com.hubidubitask.mobile.importer.sources

import com.hubidubitask.core.i18n.t
import com.hubidubitask.mobile.importer.ImportContext
import com.hubidubitask.mobile.importer.ImportIcon
import com.hubidubitask.mobile.importer.ImportInput
import com.hubidubitask.mobile.importer.ImportItem
import com.hubidubitask.mobile.importer.ImportLine
import com.hubidubitask.mobile.importer.ImportPlan
import com.hubidubitask.mobile.importer.ImportSource
import com.hubidubitask.mobile.importer.ImportValue
import java.io.IOException
import java.time.LocalDate

/**
 * A todo.txt file, the plain text Simpletask, Markor and the todo.txt tools
 * keep (https://github.com/todotxt/todo.txt): one task a line. Read from the
 * file the user picks; nothing leaves the device.
 *
 * A line's first `+project` is its project and `due:YYYY-MM-DD` its date.
 * Done lines (`x ` first) are skipped. What the app has no place for is
 * counted, not dropped silently: the priority `(A)`, `@contexts`, `rec:`
 * repeats, a second project and the creation date. Other `key:value` words
 * stay in the text.
 */
object TodoTxtSource : ImportSource {
    override val id = "todotxt"
    override val title = "todo.txt"
    override val subtitle get() = t("Projects and open tasks")
    override val icon = ImportIcon.FILE
    override val input = ImportInput.File(listOf("text/plain"), listOf("txt"))

    /** More lines than a to-do list has; a file past it is refused. */
    const val MAX_LINES = 20_000

    override fun read(value: ImportValue, here: ImportContext): ImportPlan {
        val file = value as ImportValue.File
        if (file.bytes.size > ImportValue.FILE_LIMIT) throw IOException(t("That file is too large."))
        return plan(file.text, here.projectNames, here.existingItems)
    }

    private val SPACE = Regex("\\s+")
    private val PRIORITY = Regex("\\([A-Z]\\)")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val REPEAT = Regex("rec:[^\\s:]+")

    private fun date(s: String): String? =
        s.takeIf { DATE.matches(it) && runCatching { LocalDate.parse(it) }.isSuccess }

    /** One open line, taken apart. */
    private class Task(
        val text: String,
        val projects: List<String>,
        val due: String?,
        val priority: Boolean,
        val created: Boolean,
        val contexts: Boolean,
        val repeats: Boolean,
    )

    private fun task(line: String): Task {
        val words = line.split(SPACE).filter { it.isNotEmpty() }
        var i = 0
        val priority = words.size > 1 && PRIORITY.matches(words[0])
        if (priority) i++
        val created = words.size > i + 1 && date(words[i]) != null
        if (created) i++
        val kept = ArrayList<String>()
        val projects = ArrayList<String>()
        var due: String? = null
        var contexts = false
        var repeats = false
        for (w in words.drop(i)) when {
            w.length > 1 && w[0] == '+' -> projects += w.substring(1)
            w.length > 1 && w[0] == '@' -> contexts = true
            due == null && w.startsWith("due:") && date(w.substring(4)) != null -> due = w.substring(4)
            REPEAT.matches(w) -> repeats = true
            else -> kept += w
        }
        // A line of tags alone keeps them as its words rather than coming in blank.
        val text = kept.ifEmpty { words.drop(i) }.ifEmpty { words }.joinToString(" ")
        return Task(text, projects, due, priority, created, contexts, repeats)
    }

    /**
     * What an import of [text] would add, given what the app already holds:
     * [existingProjects] by name, [existingItems] keyed by (project name or
     * null, text). Compared trimmed and case-insensitively, so a second import
     * of the same file adds nothing.
     */
    fun plan(
        text: String,
        existingProjects: Collection<String>,
        existingItems: Map<Pair<String?, String>, String>,
    ): ImportPlan {
        if ('\u0000' in text) throw IOException(t("That isn't a text file."))
        val lines = text.lineSequence().withIndex().filter { it.value.isNotBlank() }.take(MAX_LINES + 1).toList()
        if (lines.size > MAX_LINES) throw IOException(t("That file is too large."))

        fun key(s: String) = s.trim().lowercase()
        val known = HashMap<String, String>()   // key -> the name as the app has it
        existingProjects.forEach { known.putIfAbsent(key(it), it.trim()) }
        val spelled = HashMap<String, String>()   // key -> the first spelling in the file
        val have = existingItems.keys.mapTo(HashSet()) { (p, s) -> p?.let(::key) to key(s) }

        val items = ArrayList<ImportItem>()
        val taken = ArrayList<Task>()
        var done = 0
        var here = 0
        for ((n, raw) in lines) {
            val line = raw.trim()
            if (line.startsWith("x ")) { done++; continue }
            val task = task(line)
            val project = task.projects.firstOrNull()?.let { tag -> known[key(tag)] ?: spelled.getOrPut(key(tag)) { tag } }
            if ((project?.let(::key) to key(task.text)) in have) { here++; continue }
            items += ImportItem("line ${n + 1}", task.text, project, task.due)
            taken += task
        }

        val used = items.mapNotNull { it.project }.distinctBy(::key)
        val (joined, fresh) = used.partition { key(it) in known }
        val dated = items.count { it.due != null }
        return ImportPlan(
            newProjects = fresh,
            items = items,
            lines = buildList {
                add(ImportLine(t("%d new projects", fresh.size) +
                    if (joined.isNotEmpty()) t(", %d existing ones get new tasks", joined.size) else ""))
                add(ImportLine(t("%d tasks", items.size) + if (dated > 0) t(", %d with a date", dated) else ""))
                if (done > 0) add(ImportLine(t("%d done — skipped", done), muted = true))
                if (here > 0) add(ImportLine(t("%d already in the app — skipped", here), muted = true))
            },
            lost = buildList {
                val priorities = taken.count { it.priority }
                val contexts = taken.count { it.contexts }
                val repeats = taken.count { it.repeats }
                val moreProjects = taken.count { it.projects.distinctBy(::key).size > 1 }
                val created = taken.count { it.created }
                if (priorities > 0) add(t("%d tasks' priorities", priorities))
                if (contexts > 0) add(t("%d tasks' contexts", contexts))
                if (repeats > 0) add(t("%d repeats (the next date is kept)", repeats))
                if (moreProjects > 0) add(t("%d tasks in more than one project — each comes in under its first", moreProjects))
                if (created > 0) add(t("%d tasks' creation dates", created))
            },
        )
    }
}
