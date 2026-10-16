package com.hubidubitask.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupTest {
    private val photo = "a photo".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }

    private fun rec(id: String, type: String, updated: String, vararg f: Pair<String, String?>, deleted: Boolean = false) =
        EntityRecord(id, type, updated, f.toMap(), deleted)

    private val records = listOf(
        rec("p_1", "project", "2026-10-01T10:00:00Z", "title" to "Kitchen", "review" to "Going well."),
        rec("t_1", "next_action", "2026-10-02T10:00:00Z", "text" to "Order the worktop", "parent" to "p_1", "done" to "true"),
        rec("n_1", "note", "2026-10-03T10:00:00Z", "title" to "Tiles", "kind" to "image", "media_sha256" to sha),
        rec("n_2", "note", "2026-10-04T10:00:00Z", deleted = true),
    )

    private fun backup(): ByteArray = ByteArrayOutputStream().also { out ->
        assertEquals(1, Backup.write(out, records, "1.3.0") { if (it == sha) photo else null })
    }.toByteArray()

    @Test
    fun `everything live comes back, done tasks and reviews included, deleted ones not`() {
        val c = Backup.read(ByteArrayInputStream(backup()))
        assertEquals(records.filter { !it.deleted }.sortedBy { it.id }, c.records)
        assertEquals("1.3.0", c.appVersion)
        val media = mutableMapOf<String, String>()
        Backup.media(ByteArrayInputStream(backup())) { s, b -> media[s] = b.decodeToString() }
        assertEquals(mapOf(sha to "a photo"), media)
    }

    @Test
    fun `a restore takes only what is newer than here`() {
        val c = Backup.read(ByteArrayInputStream(backup()))
        val here = mapOf(
            // Edited here after the backup: kept.
            "p_1" to rec("p_1", "project", "2026-10-09T10:00:00Z", "title" to "Kitchen and hall"),
            // Older here than in the backup: replaced.
            "t_1" to rec("t_1", "next_action", "2026-09-01T10:00:00Z", "text" to "Order the worktop"),
            // n_1 is not here at all: added.
        )
        val plan = Backup.plan(c) { here[it] }
        assertEquals(listOf("n_1", "t_1"), plan.incoming.map { it.id })
        assertEquals(Triple(1, 1, 1), Triple(plan.added, plan.replaced, plan.alreadyHere))
        assertEquals(1, plan.media)
        // Deleted here after the backup: stays deleted.
        val gone = Backup.plan(c) { if (it == "n_1") rec("n_1", "note", "2026-10-10T10:00:00Z", deleted = true) else null }
        assertEquals(listOf("p_1", "t_1"), gone.incoming.map { it.id })
    }

    @Test
    fun `a file that is not a backup is said so, and a damaged photo is left out`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("something.txt")); it.write("hi".toByteArray()) }
        }.toByteArray()
        assertFailsWith<Backup.NotABackup> { Backup.read(ByteArrayInputStream(zip)) }
        assertFailsWith<Backup.NotABackup> { Backup.read(ByteArrayInputStream("not a zip".toByteArray())) }
        val tampered = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("blobs/$sha")); it.write("another photo".toByteArray()) }
        }.toByteArray()
        var seen = 0
        Backup.media(ByteArrayInputStream(tampered)) { _, _ -> seen++ }
        assertEquals(0, seen)
    }
}
