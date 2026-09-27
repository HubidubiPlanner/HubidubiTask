package com.hubidubios.mobile.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoistPlanTest {

    private val projects = listOf(
        Todoist.Project("1", "Inbox", inbox = true),
        Todoist.Project("2", "Kitchen", inbox = false),
        Todoist.Project("3", "Books", inbox = false),
    )
    // The sub-task is listed before its parent, as the API may return them.
    private val tasks = listOf(
        Todoist.Task("c", "Call electrician", "2", null, parentId = "b"),
        Todoist.Task("a", "Buy toner", "1", null),
        Todoist.Task("b", "Order\n worktop", "2", "2026-09-30", description = "Oak, 3 m"),
        Todoist.Task("d", "Read Dune", "3", null, recurring = true),
        Todoist.Task("e", "Orphan", "3", null, parentId = "gone"),
    )

    @Test
    fun `fresh import creates projects and files tasks, parents first`() {
        val p = Todoist.plan(projects, tasks, emptyList(), emptyMap())
        assertEquals(listOf("Kitchen", "Books"), p.newProjects)
        val keys = p.tasks.map { it.key }
        assert(keys.indexOf("b") < keys.indexOf("c")) { "sub-task planned before its parent" }
        val order = p.tasks.first { it.key == "b" }
        assertEquals("Order worktop", order.text)
        assertEquals("Oak, 3 m", order.body)
        assertEquals("b", p.tasks.first { it.key == "c" }.parentKey)
        assertEquals(1, p.subtasks); assertEquals(1, p.withDescription); assertEquals(1, p.recurring)
    }

    @Test
    fun `a sub-task whose parent isn't open goes straight into the project`() {
        val orphan = Todoist.plan(projects, tasks, emptyList(), emptyMap()).tasks.first { it.key == "e" }
        assertNull(orphan.parentKey)
        assertEquals("Books", orphan.project)
    }

    @Test
    fun `a project of the same name is joined, not duplicated`() {
        val p = Todoist.plan(projects, tasks, listOf("kitchen"), emptyMap())
        assertEquals(listOf("Books"), p.newProjects)
        assertEquals(1, p.joinedProjects)
    }

    @Test
    fun `a new sub-task of a parent already in the app points at it`() {
        val p = Todoist.plan(projects, tasks, listOf("Kitchen"),
            mapOf(("Kitchen" to "Order worktop") to "t_existing"))
        assertEquals(1, p.alreadyHere)
        assertEquals("t_existing", p.tasks.first { it.key == "c" }.existingParent)
    }

    @Test
    fun `running twice adds nothing the second time`() {
        val first = Todoist.plan(projects, tasks, emptyList(), emptyMap())
        val nowInApp = first.tasks.associate { (it.project to it.text) to "t_" + it.key }
        val second = Todoist.plan(projects, tasks, first.newProjects, nowInApp)
        assertEquals(emptyList<String>(), second.newProjects)
        assertEquals(0, second.tasks.size)
        assertEquals(5, second.alreadyHere)
    }

    @Test
    fun `a sub-sub-task becomes a note under the top task`() {
        val deep = tasks + Todoist.Task("f", "Pick socket colour", "2", null, parentId = "c")
        val p = Todoist.plan(projects, deep, emptyList(), emptyMap())
        assertEquals("b", p.tasks.first { it.key == "f" }.parentKey)
    }
}
