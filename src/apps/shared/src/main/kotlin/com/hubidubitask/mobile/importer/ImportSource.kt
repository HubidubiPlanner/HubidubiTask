package com.hubidubitask.mobile.importer

import com.hubidubitask.core.ItemSink
import com.hubidubitask.core.ItemWriter
import com.hubidubitask.core.i18n.t
import com.hubidubitask.core.net.SafeFetch
import com.hubidubitask.mobile.importer.sources.GoogleCalendarSource
import com.hubidubitask.mobile.importer.sources.TodoistSource
import com.hubidubitask.mobile.tree.ProjectTree
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Another app the user can bring their items in from, once. Listed in
 * Import on the phone and the computer, both drawn from [ImportSources.all].
 *
 * A source only reads: [read] gets what the user typed or picked and returns
 * a [ImportPlan] of what would come in. [ImportWriter] writes it, after the
 * user confirmed, and marks every item as imported. Nothing is kept: not the
 * token, not the address, not the file.
 *
 * Texts shown to the user are getters that translate (t), so they follow the
 * app's language and the translation check finds them. A brand name stays as
 * it is.
 */
interface ImportSource {
    /** Never shown; names the source in code and tests. */
    val id: String
    /** Its name in the list and as the screen's title. */
    val title: String
    /** What it brings, under its name in the list. */
    val subtitle: String
    val icon: ImportIcon
    /** What the user gives it. */
    val input: ImportInput

    /**
     * What an import would add, given what the app already holds ([here]).
     * Network only through [ImportContext.web]; never writes. Call it off the
     * main thread.
     */
    fun read(value: ImportValue, here: ImportContext): ImportPlan

    /** What to tell the user when [read] failed with [e]; null for the usual words. */
    fun error(e: Throwable): String? = null
}

/** The icon next to a source in the list; each app draws its own. */
enum class ImportIcon { TASKS, CALENDAR, FILE }

/** What a source asks for. [label] sits over the field, [howTo] under it. */
sealed interface ImportInput {
    /** A token or password, typed hidden. */
    data class Secret(val label: String, val howTo: String? = null) : ImportInput
    /** A web address. */
    data class Address(val label: String, val howTo: String? = null) : ImportInput
    /** A file the user picks: [mimeTypes] for the phone's picker, [extensions] for the computer's. */
    data class File(val mimeTypes: List<String>, val extensions: List<String> = emptyList()) : ImportInput
}

/** What the user gave a source. */
sealed interface ImportValue {
    data class Text(val text: String) : ImportValue
    class File(val name: String, val bytes: ByteArray) : ImportValue {
        val text: String get() = String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    companion object {
        /** The largest file a source is given. */
        const val FILE_LIMIT = 5 * 1024 * 1024
    }
}

/**
 * Where the app stands before an import: the projects and items it holds, and
 * the one way out to the web ([web]: https only, never into the home network).
 */
class ImportContext(
    private val sink: ItemSink,
    val web: SafeFetch = WEB,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.now(zone),
) {
    private val projects = sink.byType("project").filter { !it.deleted }
    private val titleOf = projects.associate { it.id to (it.fields["title"] ?: it.id) }

    /** The names of the projects already here. */
    val projectNames: Collection<String> get() = titleOf.values

    /**
     * Every task, inbox item and note already here, keyed by (the name of the
     * project it sits in, or null; its text), with its id.
     */
    val existingItems: Map<Pair<String?, String>, String> by lazy {
        val idBySlug = projects.associate { ProjectTree.slugOf(it.fields["title"] ?: it.id) to it.id }
        val items = listOf("next_action", "inbox_item", "note").flatMap(sink::byType).filter { !it.deleted }
        val byId = items.associateBy { it.id }
        items.associate { rec ->
            // A note under a task sits in that task's project.
            val project = ProjectTree.parentOf(rec, idBySlug)
                ?.let { titleOf[it] ?: byId[it]?.fields?.get("parent")?.let(titleOf::get) }
            (project to (rec.fields["text"] ?: rec.fields["title"] ?: "")) to rec.id
        }
    }

    /** Whether a record with [id] is here (deleted ones too). */
    fun has(id: String): Boolean = sink.get(id) != null

    companion object {
        val WEB = SafeFetch { callTimeout(60, TimeUnit.SECONDS) }
    }
}

/**
 * One item to create: a task, or, when [parentKey] is set, a note under that
 * task. [key] and [parentKey] link them during the import only (the other
 * app's ids). [existingParent] is set when the parent is already in the app.
 * [project] is a project's name; null means no project.
 */
data class ImportItem(
    val key: String,
    val text: String,
    val project: String?,
    val due: String?,
    val body: String? = null,
    val parentKey: String? = null,
    val existingParent: String? = null,
)

/** A line of a plan's summary; [muted] for what is left out. */
data class ImportLine(val text: String, val muted: Boolean = false)

/**
 * What an import would add. [lines] say it, [lost] what the app has no place
 * for (shown as "not carried over"). Parents come before their notes.
 */
data class ImportPlan(
    val newProjects: List<String> = emptyList(),
    val items: List<ImportItem> = emptyList(),
    val events: List<Ics.Occurrence> = emptyList(),
    /** The calendar events are filed under. */
    val calendar: String = "import",
    val lines: List<ImportLine> = emptyList(),
    val lost: List<String> = emptyList(),
) {
    val empty: Boolean get() = newProjects.isEmpty() && items.isEmpty() && events.isEmpty()
}

/** The one place an import writes. Every project, task and note it makes is marked imported. */
object ImportWriter {
    const val ORIGIN = "import"

    data class Result(val tasks: Int, val notes: Int, val projects: Int, val events: Int)

    fun apply(plan: ImportPlan, sink: ItemSink): Result {
        val w = ItemWriter(sink)
        val idByName = HashMap<String, String>()
        sink.byType("project").filter { !it.deleted }
            .forEach { idByName[(it.fields["title"] ?: it.id).trim().lowercase()] = it.id }
        var projects = 0
        for (name in plan.newProjects) {
            val k = name.trim().lowercase()
            if (k in idByName) continue
            idByName[k] = w.project(name, origin = ORIGIN); projects++
        }
        val idByKey = HashMap<String, String>()
        var tasks = 0
        var notes = 0
        for (i in plan.items) {
            val task = i.parentKey?.let { idByKey[it] ?: i.existingParent }
            idByKey[i.key] = if (task != null) {
                notes++
                w.note(i.text, i.body.orEmpty(), i.due, parent = task, origin = ORIGIN)
            } else {
                tasks++
                w.nextAction(i.text, i.project?.let { idByName[it.trim().lowercase()] },
                    i.project?.let(ProjectTree::slugOf), i.due, i.body, origin = ORIGIN)
            }
        }
        val events = plan.events.count {
            w.event(it.uid, it.summary, it.dtstart, it.dtend, it.allDay, it.location, plan.calendar)
        }
        return Result(tasks, notes, projects, events)
    }
}

/** What both apps say about an import, in the app's language. */
object ImportText {
    /** After writing [r]; [onComputer] for the computer app, whose items go to the phone. */
    fun done(r: ImportWriter.Result, onComputer: Boolean): String =
        if (r.events > 0 && r.tasks + r.notes + r.projects == 0) {
            if (onComputer) t("Imported %d events. They show in Incoming, and reach your phone on the next sync.", r.events)
            else t("Imported %d events. They show in Incoming, and reach your computer on the next sync.", r.events)
        } else {
            if (onComputer) t("Imported %d tasks, %d notes and %d projects. They reach your phone on the next sync.",
                r.tasks, r.notes, r.projects)
            else t("Imported %d tasks, %d notes and %d projects. They reach your computer on the next sync.",
                r.tasks, r.notes, r.projects)
        }

    /** Why [source] could not be read. */
    fun error(source: ImportSource?, e: Throwable): String = source?.error(e) ?: when (e) {
        is java.net.UnknownHostException -> t("No internet connection.")
        else -> e.message ?: t("Couldn't read it.")
    }
}

/** Every source Import offers, in its order. The HubidubiTask backup follows them in each app. */
object ImportSources {
    val all: List<ImportSource> = listOf(TodoistSource, GoogleCalendarSource)

    fun of(id: String): ImportSource? = all.firstOrNull { it.id == id }
}

