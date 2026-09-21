package com.hubidubios.mobile.tree

import com.hubidubios.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {

    private fun project(id: String, title: String) =
        EntityRecord(id, "project", "2026-09-20T10:00:00Z", mapOf("title" to title))

    private fun task(id: String, text: String, parent: String? = null,
                     project: String? = null) =
        EntityRecord(id, "next_action", "2026-09-20T10:00:00Z",
            buildMap {
                put("text", text); put("done", "false")
                if (parent != null) put("parent", parent)
                if (project != null) put("project", project)
            })

    private fun note(id: String, title: String, parent: String? = null) =
        EntityRecord(id, "note", "2026-09-20T10:00:00Z",
            buildMap {
                put("title", title)
                if (parent != null) put("parent", parent)
            })

    @Test
    fun `a project holds both notes and tasks`() {
        val records = listOf(
            project("p_house", "House"),
            task("t_a", "call roofer", parent = "p_house"),
            note("n_a", "Roof quote", parent = "p_house"),
        )

        val tree = ProjectTree.build(records)

        assertEquals(listOf("p_house" to 0, "n_a" to 1, "t_a" to 1),
            tree.map { it.id to it.depth })
    }

    @Test
    fun `projects never nest inside one another`() {
        // Even if something sets a parent on a project, it stays top level:
        // projects are containers, not contents.
        val records = listOf(
            project("p_a", "A"),
            EntityRecord("p_b", "project", "2026-09-20T10:00:00Z",
                mapOf("title" to "B", "parent" to "p_a")),
        )

        val tree = ProjectTree.build(records)

        assertEquals(listOf(0, 0), tree.map { it.depth })
    }

    @Test
    fun `items with no project sit at the top level, after the projects`() {
        val records = listOf(
            project("p_house", "House"),
            task("t_loose", "buy milk"),
        )

        val tree = ProjectTree.build(records)

        assertEquals(listOf("p_house" to 0, "t_loose" to 0), tree.map { it.id to it.depth })
    }

    @Test
    fun `legacy tasks filed by project slug still appear under the project`() {
        val records = listOf(
            project("p_site", "My Website"),
            task("t_a", "old task", project = "my-website"),
        )

        assertEquals(listOf("p_site" to 0, "t_a" to 1),
            ProjectTree.build(records).map { it.id to it.depth })
    }

    @Test
    fun `an item whose project was deleted is shown, not dropped`() {
        val records = listOf(task("t_orphan", "orphan", parent = "p_gone"))

        val tree = ProjectTree.build(records)

        assertEquals(listOf("t_orphan"), tree.map { it.id })
        assertEquals(0, tree[0].depth)
    }

    @Test
    fun `collapsing a project hides its items`() {
        val records = listOf(
            project("p_house", "House"),
            task("t_a", "call roofer", parent = "p_house"),
        )

        val tree = ProjectTree.build(records, collapsed = setOf("p_house"))

        assertEquals(listOf("p_house"), tree.map { it.id })
        assertTrue("it still shows as having children", tree[0].hasChildren)
    }

    @Test
    fun `notes sort before tasks within a project`() {
        val records = listOf(
            project("p_house", "House"),
            task("t_a", "aaa task", parent = "p_house"),
            note("n_z", "zzz note", parent = "p_house"),
        )

        assertEquals(listOf("p_house", "n_z", "t_a"), ProjectTree.build(records).map { it.id })
    }

    @Test
    fun `projects are listed alphabetically`() {
        val records = listOf(project("p_z", "Zebra"), project("p_a", "Apple"))

        assertEquals(listOf("p_a", "p_z"), ProjectTree.build(records).map { it.id })
    }

    @Test
    fun `an item can move to another project or out of one`() {
        val records = listOf(
            project("p_a", "A"), project("p_b", "B"),
            task("t_x", "task", parent = "p_a"),
        )

        assertTrue(ProjectTree.canMove("t_x", "p_b", records))
        assertTrue(ProjectTree.canMove("t_x", null, records))
    }

    @Test
    fun `a project cannot be moved at all`() {
        val records = listOf(project("p_a", "A"), project("p_b", "B"))

        assertFalse(ProjectTree.canMove("p_a", "p_b", records))
        assertTrue("and it is offered no destinations",
            ProjectTree.moveTargets("p_a", records).isEmpty())
    }

    @Test
    fun `an item cannot be filed under another item`() {
        val records = listOf(
            project("p_a", "A"),
            task("t_x", "x", parent = "p_a"),
            note("n_y", "y", parent = "p_a"),
        )

        assertFalse(ProjectTree.canMove("t_x", "n_y", records))
    }

    @Test
    fun `move targets are every project plus no project`() {
        val records = listOf(
            project("p_a", "A"), project("p_b", "B"),
            note("n_x", "note", parent = "p_a"),
        )

        val ids = ProjectTree.moveTargets("n_x", records).map { it.first }

        assertEquals(listOf(null, "p_a", "p_b"), ids)
    }
}

class ProjectTreeRecordsTest {

    private fun rec(id: String, type: String, fields: Map<String, String?> = emptyMap()) =
        EntityRecord(id = id, type = type, updated = "2026-09-21T09:00:00Z", fields = fields)

    @Test
    fun `a captured item is in the view it was filed into`() {
        // A capture stays an inbox item until it is clarified. Leaving that type
        // out of the view was the whole bug: the item was filed, synced, and
        // invisible.
        val project = rec("p_1", "project", mapOf("title" to "Roof"))
        val captured = rec("i_1", "inbox_item", mapOf("text" to "call the roofer",
            "parent" to "p_1"))

        val nodes = ProjectTree.build(
            ProjectTree.records(listOf(project), emptyList(), emptyList(), listOf(captured)))

        assertEquals(listOf("p_1", "i_1"), nodes.map { it.id })
    }

    @Test
    fun `every kind of item reaches the view`() {
        val records = ProjectTree.records(
            listOf(rec("p_1", "project", mapOf("title" to "Roof"))),
            listOf(rec("t_1", "next_action", mapOf("text" to "a"))),
            listOf(rec("n_1", "note", mapOf("title" to "b"))),
            listOf(rec("i_1", "inbox_item", mapOf("text" to "c"))),
        )

        assertEquals(setOf("p_1", "t_1", "n_1", "i_1"), records.map { it.id }.toSet())
    }
}

class ProjectTreeFreeItemsTest {

    private fun rec(id: String, type: String, fields: Map<String, String?> = emptyMap()) =
        EntityRecord(id = id, type = type, updated = "2026-09-21T09:00:00Z", fields = fields)

    @Test
    fun `the loose items are the ones outside every project`() {
        val nodes = ProjectTree.build(listOf(
            rec("p_1", "project", mapOf("title" to "Roof")),
            rec("n_1", "note", mapOf("title" to "quote", "parent" to "p_1")),
            rec("n_2", "note", mapOf("title" to "loose")),
        ))

        assertEquals(listOf("n_2"), nodes.filter { it.isFree }.map { it.id })
        // A project is a container, never a loose item, however it is listed.
        assertTrue(nodes.none { it.isProject && it.isFree })
    }

    @Test
    fun `with no projects at all every item is loose`() {
        val nodes = ProjectTree.build(listOf(
            rec("n_1", "note", mapOf("title" to "a")),
            rec("i_1", "inbox_item", mapOf("text" to "b")),
        ))

        assertEquals(2, nodes.count { it.isFree })
    }
}
