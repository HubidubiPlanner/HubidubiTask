package com.hubidubios.mobile.tree

import com.hubidubios.core.EntityRecord

/**
 * The Projects view: a flat list of projects, each holding its items — notes
 * and tasks alike. Projects do not nest inside each other; they are the
 * containers, and the items are what moves between them.
 *
 * The shape and the move rules are pure functions rather than logic inside a
 * composable, because reparenting has real invariants worth testing.
 *
 * Nesting comes from an explicit `parent` field. A legacy task carries only the
 * project *slug* it was filed under, so [build] falls back to matching that
 * against a project's slug — otherwise every pre-existing task would appear at
 * the top level the first time the tree is shown.
 */
object ProjectTree {

    data class Node(
        val record: EntityRecord,
        val depth: Int,
        val hasChildren: Boolean,
    ) {
        val id: String get() = record.id
        val isProject: Boolean get() = record.type == "project"
        val isNote: Boolean get() = record.type == "note"
        val title: String get() =
            record.fields["title"] ?: record.fields["text"] ?: record.id
        val done: Boolean get() = record.fields["done"] == "true"
    }

    /**
     * Everything the Projects view holds: the containers and every kind of
     * item that can go in one.
     *
     * Captures land as inbox items until they are clarified, so leaving those
     * out means a note you just wrote is simply not in the view you filed it
     * into — it looks lost, and there is nowhere in the app to find it.
     */
    fun records(
        projects: List<EntityRecord>,
        nextActions: List<EntityRecord>,
        notes: List<EntityRecord>,
        inbox: List<EntityRecord>,
    ): List<EntityRecord> = projects + nextActions + notes + inbox

    fun slugOf(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The project this record belongs to, or null when it sits at the top. */
    fun parentOf(record: EntityRecord, projectIdBySlug: Map<String, String>): String? {
        // A project is always top level: it is a container, never contained.
        if (record.type == "project") return null
        record.fields["parent"]?.takeIf { it.isNotBlank() }?.let { return it }
        // Legacy: filed under a project slug before nesting existed.
        val slug = record.fields["project"]?.takeIf { it.isNotBlank() && it != "inbox" }
        return slug?.let { projectIdBySlug[it] }
    }

    /**
     * Projects first (alphabetically), each followed by its items; then any
     * items that belong to no project.
     *
     * An item whose project was deleted is shown at the top level rather than
     * silently dropped — one the user cannot see is worse than one out of place.
     */
    fun build(
        records: List<EntityRecord>,
        collapsed: Set<String> = emptySet(),
    ): List<Node> {
        val projects = records.filter { it.type == "project" }
        val projectIds = projects.map { it.id }.toSet()
        val projectIdBySlug = projects.associate { slugOf(it.fields["title"] ?: it.id) to it.id }

        val itemsOf = HashMap<String?, MutableList<EntityRecord>>()
        records.filter { it.type != "project" }.forEach { rec ->
            val parent = parentOf(rec, projectIdBySlug)?.takeIf { it in projectIds }
            itemsOf.getOrPut(parent) { mutableListOf() }.add(rec)
        }

        // Notes before tasks, then alphabetical: reference material reads as
        // context for the actions underneath it.
        val itemOrder = compareByDescending<EntityRecord> { it.type == "note" }
            .thenBy { (it.fields["title"] ?: it.fields["text"] ?: it.id).lowercase() }

        val out = mutableListOf<Node>()
        projects.sortedBy { (it.fields["title"] ?: it.id).lowercase() }.forEach { proj ->
            val items = itemsOf[proj.id].orEmpty()
            out += Node(proj, 0, items.isNotEmpty())
            if (proj.id !in collapsed) {
                items.sortedWith(itemOrder).forEach { out += Node(it, 1, false) }
            }
        }
        itemsOf[null].orEmpty().sortedWith(itemOrder).forEach { out += Node(it, 0, false) }
        return out
    }

    /**
     * Whether [id] may be filed under [newParent] (null = no project).
     * Projects are containers and never move; only items do.
     */
    fun canMove(id: String, newParent: String?, records: List<EntityRecord>): Boolean {
        val self = records.firstOrNull { it.id == id } ?: return false
        if (self.type == "project") return false
        if (newParent == null) return true
        val target = records.firstOrNull { it.id == newParent } ?: return false
        return target.type == "project"
    }

    /** Valid destinations for [id], as (id, label); null means no project. */
    fun moveTargets(id: String, records: List<EntityRecord>): List<Pair<String?, String>> {
        val self = records.firstOrNull { it.id == id }
        if (self == null || self.type == "project") return emptyList()
        return listOf<Pair<String?, String>>(null to "No project") +
            records.filter { it.type == "project" }
                .sortedBy { (it.fields["title"] ?: it.id).lowercase() }
                .map { it.id to (it.fields["title"] ?: it.id) }
    }
}
