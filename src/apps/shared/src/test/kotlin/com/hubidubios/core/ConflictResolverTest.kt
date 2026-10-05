package com.hubidubios.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConflictResolverTest {

    private fun task(id: String, updated: String, vararg fields: Pair<String, String?>) =
        EntityRecord(id, "task", updated, fields.toMap())

    private fun note(id: String, updated: String, title: String, body: String) =
        EntityRecord(id, "note", updated, mapOf("title" to title, "body" to body))

    @Test
    fun `different fields both win`() {
        val base = task("t_1", "2026-08-31T10:00:00Z",
            "text" to "call bob", "due" to "2026-09-01", "context" to "@calls")
        val local = base.copy(updated = "2026-08-31T11:00:00Z",
            fields = base.fields + ("due" to "2026-09-05"))       // local changed due
        val remote = base.copy(updated = "2026-08-31T10:30:00Z",
            fields = base.fields + ("context" to "@errands"))     // remote changed context

        val merged = ConflictResolver.mergeStructured(base, local, remote)
        assertEquals("2026-09-05", merged.fields["due"])       // local's field
        assertEquals("@errands", merged.fields["context"])     // remote's field
        assertEquals("call bob", merged.fields["text"])        // untouched
    }

    @Test
    fun `same field conflict is last-write-wins by updated`() {
        val base = task("t_1", "2026-08-31T10:00:00Z", "text" to "draft")
        val local = base.copy(updated = "2026-08-31T12:00:00Z", fields = mapOf("text" to "local edit"))
        val remote = base.copy(updated = "2026-08-31T11:00:00Z", fields = mapOf("text" to "remote edit"))

        assertEquals("local edit",
            ConflictResolver.mergeStructured(base, local, remote).fields["text"])
        // symmetric: newer remote wins
        val remoteNewer = remote.copy(updated = "2026-08-31T13:00:00Z")
        assertEquals("remote edit",
            ConflictResolver.mergeStructured(base, local, remoteNewer).fields["text"])
    }

    @Test
    fun `deletion beats concurrent edit on either side`() {
        val base = task("t_1", "2026-08-31T10:00:00Z", "text" to "x")
        val edit = base.copy(updated = "2027-01-01T00:00:00Z", fields = mapOf("text" to "still here"))
        val del = base.tombstone("2026-08-31T10:30:00Z")

        assertTrue(ConflictResolver.mergeStructured(base, edit, del).deleted)   // remote delete
        assertTrue(ConflictResolver.mergeStructured(base, del, edit).deleted)   // local delete
    }

    @Test
    fun `independent creation with no base merges by field presence`() {
        val local = task("t_1", "2026-08-31T11:00:00Z", "text" to "a", "due" to "2026-09-09")
        val remote = task("t_1", "2026-08-31T10:00:00Z", "text" to "a", "context" to "@home")
        val merged = ConflictResolver.mergeStructured(null, local, remote)
        assertEquals("2026-09-09", merged.fields["due"])
        assertEquals("@home", merged.fields["context"])
    }

    @Test
    fun `note bodies never auto-merge - server canonical, local preserved`() {
        val base = note("n_1", "2026-08-31T10:00:00Z", "Design", "shared start")
        val local = base.copy(updated = "2026-08-31T12:00:00Z",
            fields = base.fields + ("body" to "shared start + my offline paragraph"))
        val remote = base.copy(updated = "2026-08-31T11:00:00Z",
            fields = base.fields + ("body" to "shared start + their online paragraph"))

        val res = ConflictResolver.resolveNote(base, local, remote, "phone", "2026-08-31")
        assertEquals("shared start + their online paragraph", res.canonical.fields["body"])
        val copy = assertNotNull(res.conflictCopy)
        assertEquals("shared start + my offline paragraph", copy.fields["body"])
        assertTrue(copy.id.contains("conflict-phone-2026-08-31"))
        assertEquals("n_1", copy.fields["conflict_of"])
    }

    @Test
    fun `note with only local body change keeps local, no conflict`() {
        val base = note("n_1", "2026-08-31T10:00:00Z", "Design", "start")
        val local = base.copy(updated = "2026-08-31T12:00:00Z",
            fields = base.fields + ("body" to "start + local"))
        val remote = base // server unchanged
        val res = ConflictResolver.resolveNote(base, local, remote, "phone", "2026-08-31")
        assertEquals("start + local", res.canonical.fields["body"])
        assertNull(res.conflictCopy)
    }

    @Test
    fun `identical note bodies produce no conflict`() {
        val base = note("n_1", "2026-08-31T10:00:00Z", "D", "same")
        val local = base.copy(updated = "2026-08-31T12:00:00Z", fields = base.fields + ("title" to "D2"))
        val remote = base.copy(updated = "2026-08-31T11:00:00Z")
        val res = ConflictResolver.resolveNote(base, local, remote, "phone", "2026-08-31")
        assertNull(res.conflictCopy)
        assertEquals("D2", res.canonical.fields["title"]) // newer metadata wins
    }
}
