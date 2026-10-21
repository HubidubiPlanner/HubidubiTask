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
import com.hubidubitask.mobile.importer.Todoist

/** Todoist's active projects and open tasks, read once with the user's API token ([Todoist]). */
object TodoistSource : ImportSource {
    override val id = "todoist"
    override val title = "Todoist"
    override val subtitle get() = t("Projects and open tasks")
    override val icon = ImportIcon.TASKS
    override val input get() = ImportInput.Secret(t("API token"),
        t("In Todoist: Settings → Integrations → Developer → API token."))

    override fun read(value: ImportValue, here: ImportContext): ImportPlan {
        val token = (value as ImportValue.Text).text.trim()
        val (projects, tasks) = Todoist.fetch(token, here.web)
        val p = Todoist.plan(projects, tasks, here.projectNames, here.existingItems)
        return ImportPlan(
            newProjects = p.newProjects,
            items = p.tasks.map { ImportItem(it.key, it.text, it.project, it.due, it.body, it.parentKey, it.existingParent) },
            lines = buildList {
                add(ImportLine(t("%d new projects", p.newProjects.size) +
                    if (p.joinedProjects > 0) t(", %d existing ones get new tasks", p.joinedProjects) else ""))
                add(ImportLine(t("%d tasks", p.tasks.size - p.subtasks) +
                    if (p.dated > 0) t(", %d with a date", p.dated) else ""))
                if (p.subtasks > 0) add(ImportLine(t("%d of them are sub-tasks — they come in as notes under their task", p.subtasks)))
                if (p.withDescription > 0) add(ImportLine(t("%d with a description", p.withDescription)))
                if (p.alreadyHere > 0) add(ImportLine(t("%d already in the app — skipped", p.alreadyHere), muted = true))
            },
            lost = buildList {
                if (p.recurring > 0) add(t("%d repeats (the next date is kept)", p.recurring))
                if (p.withLabels > 0) add(t("%d tasks' labels", p.withLabels))
                if (p.inSections > 0) add(t("%d tasks' sections", p.inSections))
            },
        )
    }

    override fun error(e: Throwable): String? =
        if (e is Todoist.TokenRejected) t("Todoist didn't accept that token.") else null
}
