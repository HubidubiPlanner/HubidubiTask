package com.hubidubios.mobile.tree

import com.hubidubios.core.EntityRecord

/**
 * The Projects view is a tree the user rearranges, so the shape and the rules
 * for moving live here as pure functions rather than inside a composable —
 * reparenting has real invariants (a node may not become its own ancestor) and
 * those deserve tests, not a UI you have to click through.
 *
 * Nesting comes from an explicit `parent` field. A legacy action carries only
 * the project *slug* it was filed under, so [build] falls back to matching that
 * against a project's slug — otherwise every pre-existing action would appear
 * at the top level the first time the tree is shown.
 */
object ProjectTree {

    data class Node(
        val record: EntityRecord,
        val depth: Int,
        val hasChildren: Boolean,
    ) {
        val id: String get() = record.id
        val isProject: Boolean get() = record.type == "project"
        val title: String get() =
            record.fields["title"] ?: record.fields["text"] ?: record.id
        val done: Boolean get() = record.fields["done"] == "true"
    }

    fun slugOf(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The id this record hangs from, or null when it belongs at the top. */
    fun parentOf(record: EntityRecord, projectIdBySlug: Map<String, String>): String? {
        record.fields["parent"]?.takeIf { it.isNotBlank() }?.let { return it }
        if (record.type != "project") {
            // Legacy: filed under a project slug before nesting existed.
            val slug = record.fields["project"]?.takeIf { it.isNotBlank() && it != "inbox" }
            return slug?.let { projectIdBySlug[it] }
        }
        return null
    }

    /**
     * Depth-first flattening, parents before children, each level ordered by
     * projects first then title. [collapsed] ids have their subtrees omitted.
     *
     * Records whose parent is missing (deleted project, partial sync) are shown
     * at the top level rather than silently dropped — a lost item the user
     * cannot see is worse than one in the wrong place.
     */
    fun build(
        records: List<EntityRecord>,
        collapsed: Set<String> = emptySet(),
    ): List<Node> {
        val byId = records.associateBy { it.id }
        val projectIdBySlug = records.filter { it.type == "project" }
            .associate { slugOf(it.fields["title"] ?: it.id) to it.id }

        val childrenOf = HashMap<String?, MutableList<EntityRecord>>()
        records.forEach { rec ->
            var parent = parentOf(rec, projectIdBySlug)
            if (parent != null && !byId.containsKey(parent)) parent = null
            if (parent == rec.id) parent = null          // self-parent: defensive
            childrenOf.getOrPut(parent) { mutableListOf() }.add(rec)
        }

        val order = compareByDescending<EntityRecord> { it.type == "project" }
            .thenBy { (it.fields["title"] ?: it.fields["text"] ?: it.id).lowercase() }

        // Reachability is computed independently of collapse: a collapsed
        // subtree is hidden on purpose, whereas one stranded by a parent cycle
        // is unreachable and must still be surfaced.
        val reachable = HashSet<String>()
        fun mark(parent: String?) {
            childrenOf[parent].orEmpty().forEach { if (reachable.add(it.id)) mark(it.id) }
        }
        mark(null)

        val out = mutableListOf<Node>()
        val seen = HashSet<String>()
        fun walk(parent: String?, depth: Int) {
            childrenOf[parent].orEmpty().sortedWith(order).forEach { rec ->
                if (!seen.add(rec.id)) return@forEach   // cycle guard
                val kids = childrenOf[rec.id].orEmpty()
                out += Node(rec, depth, kids.isNotEmpty())
                if (rec.id !in collapsed) walk(rec.id, depth + 1)
            }
        }
        walk(null, 0)

        // Stranded by a cycle: show at the top level rather than lose it.
        records.sortedWith(order).forEach { rec ->
            if (rec.id !in reachable && seen.add(rec.id)) {
                out += Node(rec, 0, childrenOf[rec.id].orEmpty().isNotEmpty())
                if (rec.id !in collapsed) walk(rec.id, 1)
            }
        }
        return out
    }

    /** Every id at or beneath [id] — the set a node may not be moved into. */
    fun descendantsOf(id: String, records: List<EntityRecord>): Set<String> {
        val projectIdBySlug = records.filter { it.type == "project" }
            .associate { slugOf(it.fields["title"] ?: it.id) to it.id }
        val childrenOf = records.groupBy { parentOf(it, projectIdBySlug) }
        val out = linkedSetOf(id)
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            childrenOf[queue.removeFirst()].orEmpty().forEach {
                if (out.add(it.id)) queue += it.id
            }
        }
        return out
    }

    /**
     * Whether [id] may be nested under [newParent] (null = move to top level).
     * Refuses a move into its own subtree, which would detach the branch from
     * the tree entirely and make it unreachable in the UI.
     */
    fun canMove(id: String, newParent: String?, records: List<EntityRecord>): Boolean {
        if (newParent == null) return true
        if (newParent == id) return false
        val target = records.firstOrNull { it.id == newParent } ?: return false
        // Only a project can contain things; an action nested under an action
        // would have no file of its own to live in.
        if (target.type != "project") return false
        return newParent !in descendantsOf(id, records)
    }

    /** Valid destinations for [id], as (id, label) with null meaning top level. */
    fun moveTargets(id: String, records: List<EntityRecord>): List<Pair<String?, String>> {
        val forbidden = descendantsOf(id, records)
        return listOf<Pair<String?, String>>(null to "Top level") +
            records.filter { it.type == "project" && it.id !in forbidden }
                .sortedBy { (it.fields["title"] ?: it.id).lowercase() }
                .map { it.id to (it.fields["title"] ?: it.id) }
    }
}
