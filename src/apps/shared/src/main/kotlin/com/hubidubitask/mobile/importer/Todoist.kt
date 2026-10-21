package com.hubidubitask.mobile.importer

import com.hubidubitask.core.net.SafeFetch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-time import of active Todoist projects and open tasks.
 *
 * Todoist is read once with the token the user types; nothing is written back
 * and nothing is kept — not the token, not a record of what came in. A second
 * run is still safe: [plan] recognises what is already in the app (a project
 * of the same name, a task with the same text in the same project) and leaves
 * it out.
 *
 * Sub-tasks become notes under their task (a sub-sub-task under the top task
 * it belongs to) and descriptions come along. What the app has
 * no place for is counted rather than silently dropped: labels, sections and
 * recurrence (the next date is kept).
 */
object Todoist {

    private const val API = "https://api.todoist.com/api/v1"

    data class Project(val id: String, val name: String, val inbox: Boolean)

    data class Task(
        val id: String,
        val content: String,
        val projectId: String?,
        val due: String?,
        val recurring: Boolean = false,
        val parentId: String? = null,
        val description: String = "",
        val hasLabels: Boolean = false,
        val inSection: Boolean = false,
    )

    class TokenRejected : Exception("Todoist rejected the token.")

    /**
     * Everything open in the account, through [web]: https only, never into
     * the home network. Network; call off the main thread.
     */
    fun fetch(token: String, web: SafeFetch): Pair<List<Project>, List<Task>> {
        val projects = all(web, token, "/projects").mapNotNull { o ->
            if (o.optBoolean("is_archived") || o.optBoolean("is_deleted")) return@mapNotNull null
            Project(o.getString("id"), o.optString("name").trim(),
                o.optBoolean("inbox_project") || o.optBoolean("is_inbox_project"))
        }
        val tasks = all(web, token, "/tasks").mapNotNull { o ->
            if (o.optBoolean("checked") || o.optBoolean("is_deleted")) return@mapNotNull null
            val due = o.optJSONObject("due")
            Task(
                id = o.getString("id"),
                content = o.optString("content"),
                projectId = o.optString("project_id").takeIf { it.isNotEmpty() && it != "null" },
                due = due?.optString("date")?.take(10)?.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) },
                recurring = due?.optBoolean("is_recurring") == true,
                parentId = o.optString("parent_id").takeIf { !o.isNull("parent_id") && it.isNotEmpty() },
                description = o.optString("description").trim(),
                hasLabels = (o.optJSONArray("labels")?.length() ?: 0) > 0,
                inSection = !o.isNull("section_id") && o.optString("section_id").isNotEmpty(),
            )
        }
        return projects to tasks
    }

    private fun all(web: SafeFetch, token: String, path: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var cursor: String? = null
        do {
            val url = "$API$path?limit=200" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val body = web.get(url, mapOf("Authorization" to "Bearer $token")) { r ->
                if (r.code == 401 || r.code == 403) throw TokenRejected()
                if (!r.isSuccessful) throw java.io.IOException("Todoist answered ${r.code}")
                r.body?.string().orEmpty()
            }
            if (body.trimStart().startsWith("[")) {
                val a = JSONArray(body); for (i in 0 until a.length()) out += a.getJSONObject(i)
                return out
            }
            val o = JSONObject(body)
            val a = o.optJSONArray("results") ?: JSONArray()
            for (i in 0 until a.length()) out += a.getJSONObject(i)
            cursor = o.optString("next_cursor").takeIf { !o.isNull("next_cursor") && it.isNotEmpty() }
        } while (cursor != null)
        return out
    }

    /**
     * One item to create: a task, or — when [parentKey] is set — a note under
     * that task. [key] and [parentKey] are Todoist ids, used only to link them
     * during the import. [existingParent] is set
     * when the parent is already in the app (skipped as a duplicate).
     */
    data class NewTask(
        val key: String,
        val text: String,
        val project: String?,
        val due: String?,
        val body: String? = null,
        val parentKey: String? = null,
        val existingParent: String? = null,
    )

    data class Plan(
        /** Projects to create, by name. */
        val newProjects: List<String>,
        /** Todoist projects whose name matches one already in the app; their tasks join it. */
        val joinedProjects: Int,
        /** Parents before their sub-tasks. */
        val tasks: List<NewTask>,
        /** Tasks already in the app (same text, same project). */
        val alreadyHere: Int,
        val dated: Int,
        val subtasks: Int,
        val withDescription: Int,
        val recurring: Int,
        val withLabels: Int,
        val inSections: Int,
    )

    private fun clean(s: String) =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "(untitled)" }

    /**
     * What an import would add, given what the app already holds.
     * [existingProjects] are project names; [existingItems] maps
     * (project name or null, item text) to the item's id. Compared case-insensitively.
     */
    fun plan(
        projects: List<Project>,
        tasks: List<Task>,
        existingProjects: Collection<String>,
        existingItems: Map<Pair<String?, String>, String>,
    ): Plan {
        fun key(s: String) = s.trim().lowercase()
        val known = existingProjects.map(::key).toHashSet()
        val have = existingItems.mapKeys { (k, _) -> k.first?.let(::key) to key(k.second) }

        val nameOf = HashMap<String, String?>()   // todoist project id -> our project name (null = no project)
        val create = ArrayList<String>()
        var joined = 0
        for (p in projects) {
            if (p.inbox) { nameOf[p.id] = null; continue }
            val name = p.name.ifBlank { "Untitled" }
            nameOf[p.id] = name
            if (key(name) in known) joined++ else { create += name; known += key(name) }
        }

        // Parents first, so a sub-task always finds the id its parent got.
        val byId = tasks.associateBy { it.id }
        fun depth(t: Task): Int {
            var d = 0; var cur = t.parentId
            while (cur != null && d < 10) { d++; cur = byId[cur]?.parentId }
            return d
        }
        val existingIdOf = HashMap<String, String>()   // todoist id -> id already in the app
        val out = ArrayList<NewTask>()
        var here = 0
        val imported = ArrayList<Task>()
        for (t in tasks.sortedBy(::depth)) {
            val text = clean(t.content)
            val project = t.projectId?.let { nameOf[it] }
            val existing = have[project?.let(::key) to key(text)]
            if (existing != null) { here++; existingIdOf[t.id] = existing; continue }
            // Notes hang off a task, not off each other: a sub-sub-task goes
            // under the top task. A parent that isn't open any more (completed,
            // deleted) leaves it a task of its own in the project.
            var parentKey = t.parentId?.takeIf { it in byId }
            var hops = 0
            while (parentKey != null && byId[parentKey]?.parentId in byId && hops++ < 10)
                parentKey = byId[parentKey]?.parentId
            out += NewTask(t.id, text, project, t.due, t.description.ifBlank { null },
                parentKey, parentKey?.let { existingIdOf[it] })
            imported += t
        }
        return Plan(
            newProjects = create, joinedProjects = joined, tasks = out, alreadyHere = here,
            dated = out.count { it.due != null },
            subtasks = out.count { it.parentKey != null },
            withDescription = out.count { it.body != null },
            recurring = imported.count { it.recurring },
            withLabels = imported.count { it.hasLabels },
            inSections = imported.count { it.inSection },
        )
    }
}
