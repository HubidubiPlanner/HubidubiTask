package com.hubidubios.mobile.tree

import com.hubidubios.core.EntityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTreeTest {

    private fun project(id: String, title: String, parent: String? = null) =
        EntityRecord(id, "project", "2026-09-20T10:00:00Z",
            buildMap {
                put("title", title)
                if (parent != null) put("parent", parent)
            })

    private fun action(id: String, text: String, parent: String? = null,
                       project: String? = null, done: Boolean = false) =
        EntityRecord(id, "next_action", "2026-09-20T10:00:00Z",
            buildMap {
                put("text", text); put("done", done.toString())
                if (parent != null) put("parent", parent)
                if (project != null) put("project", project)
            })

    @Test
    fun `nests children under their parent`() {
        val records = listOf(
            project("p_site", "Website"),
            action("t_a", "draft copy", parent = "p_site"),
        )

        val tree = ProjectTree.build(records)

        assertEquals(listOf("p_site" to 0, "t_a" to 1), tree.map { it.id to it.depth })
        assertTrue("parent should report having children", tree[0].hasChildren)
    }

    @Test
    fun `nests sub-projects to arbitrary depth`() {
        val records = listOf(
            project("p_root", "Root"),
            project("p_mid", "Mid", parent = "p_root"),
            action("t_leaf", "leaf task", parent = "p_mid"),
        )

        assertEquals(listOf(0, 1, 2), ProjectTree.build(records).map { it.depth })
    }

    @Test
    fun `legacy actions filed by project slug still appear under the project`() {
        // Pre-existing items have no parent field, only the slug of the file
        // they were filed in; without the fallback they would all jump to top level.
        val records = listOf(
            project("p_site", "My Website"),
            action("t_a", "old task", project = "my-website"),
        )

        val tree = ProjectTree.build(records)

        assertEquals(listOf("p_site" to 0, "t_a" to 1), tree.map { it.id to it.depth })
    }

    @Test
    fun `an item whose parent no longer exists is shown, not dropped`() {
        val records = listOf(action("t_orphan", "orphan", parent = "p_deleted"))

        val tree = ProjectTree.build(records)

        assertEquals(listOf("t_orphan"), tree.map { it.id })
        assertEquals(0, tree[0].depth)
    }

    @Test
    fun `a parent cycle cannot hang the tree`() {
        val records = listOf(
            project("p_a", "A", parent = "p_b"),
            project("p_b", "B", parent = "p_a"),
        )

        val tree = ProjectTree.build(records)

        assertEquals("every record should appear exactly once", 2, tree.size)
    }

    @Test
    fun `collapsing a node hides its whole subtree`() {
        val records = listOf(
            project("p_root", "Root"),
            project("p_mid", "Mid", parent = "p_root"),
            action("t_leaf", "leaf", parent = "p_mid"),
        )

        val tree = ProjectTree.build(records, collapsed = setOf("p_root"))

        assertEquals(listOf("p_root"), tree.map { it.id })
    }

    @Test
    fun `projects sort before actions at the same level`() {
        val records = listOf(
            project("p_root", "Root"),
            action("t_a", "aaa first alphabetically", parent = "p_root"),
            project("p_sub", "zzz last alphabetically", parent = "p_root"),
        )

        val ids = ProjectTree.build(records).map { it.id }

        assertEquals(listOf("p_root", "p_sub", "t_a"), ids)
    }

    @Test
    fun `a node cannot be moved into its own subtree`() {
        val records = listOf(
            project("p_root", "Root"),
            project("p_child", "Child", parent = "p_root"),
        )

        assertFalse("moving a parent under its child would detach the branch",
            ProjectTree.canMove("p_root", "p_child", records))
        assertFalse(ProjectTree.canMove("p_root", "p_root", records))
    }

    @Test
    fun `a node can be moved to top level or another project`() {
        val records = listOf(
            project("p_a", "A"),
            project("p_b", "B"),
            action("t_x", "task", parent = "p_a"),
        )

        assertTrue(ProjectTree.canMove("t_x", null, records))
        assertTrue(ProjectTree.canMove("t_x", "p_b", records))
    }

    @Test
    fun `an action cannot become a container`() {
        // Actions live as lines inside a project's file; one nested under
        // another would have nowhere of its own to live.
        val records = listOf(
            project("p_a", "A"),
            action("t_x", "x", parent = "p_a"),
            action("t_y", "y", parent = "p_a"),
        )

        assertFalse(ProjectTree.canMove("t_x", "t_y", records))
    }

    @Test
    fun `move targets exclude the node and its descendants`() {
        val records = listOf(
            project("p_root", "Root"),
            project("p_child", "Child", parent = "p_root"),
            project("p_other", "Other"),
        )

        val ids = ProjectTree.moveTargets("p_root", records).map { it.first }

        assertTrue("top level must always be offered", ids.contains(null))
        assertTrue(ids.contains("p_other"))
        assertFalse("its own child is not a legal destination", ids.contains("p_child"))
        assertFalse(ids.contains("p_root"))
    }
}
