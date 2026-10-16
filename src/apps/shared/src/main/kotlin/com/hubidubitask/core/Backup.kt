package com.hubidubitask.core

import com.hubidubitask.core.peer.Lww
import com.hubidubitask.core.peer.PeerJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything in the app as one file, made by Settings › Export and read back
 * by Import › HubidubiTask backup, on the phone or the computer.
 *
 * Sealed with a password the user chooses ([Sealed]); inside, a ZIP:
 * `backup.json` first, every item as sync carries it (projects, tasks
 * done or not, notes, events, feeds, their reviews), then `blobs/<sha256>` for
 * each photo, voice memo or file an item names and this device holds. Nothing
 * of the device itself goes in: no keys, tokens, pairing or settings.
 *
 * A restore changes an item only where the backup's copy is newer than the
 * one here, by the same rule sync uses, so restoring onto a device that has
 * moved on loses nothing; deleted items stay deleted.
 */
object Backup {
    const val FORMAT = "hubidubitask-backup"
    const val VERSION = 1
    private const val INDEX = "backup.json"
    private const val BLOBS = "blobs/"

    class NotABackup : Exception("not a HubidubiTask backup")
    /** The file is sealed and no password was given. */
    class PasswordNeeded : Exception("password needed")

    /** What a backup holds, without its photos and memos. */
    data class Contents(val records: List<EntityRecord>, val created: String?, val appVersion: String?)

    /** What restoring would do on this device. */
    data class Plan(val incoming: List<EntityRecord>, val added: Int, val replaced: Int, val alreadyHere: Int,
                    val media: Int)

    /**
     * The live items of [records] and the media they name, to [out], sealed
     * with [password] (null only in tests: a bare ZIP). Returns how many media
     * went in.
     */
    fun write(out: OutputStream, records: List<EntityRecord>, appVersion: String, password: CharArray?,
              rounds: Int = Sealed.ROUNDS, blob: (sha: String) -> ByteArray?): Int {
        val live = records.filter { !it.deleted }.sortedBy { it.id }
        var media = 0
        ZipOutputStream(if (password == null) out else Sealed.seal(out, password, rounds)).use { zip ->
            val index = JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("app_version", appVersion).put("created", nowUtc())
                .put("records", JSONArray().apply { live.forEach { put(PeerJson.record(it)) } })
            zip.putNextEntry(ZipEntry(INDEX))
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            for (sha in shas(live)) {
                val bytes = blob(sha) ?: continue
                zip.putNextEntry(ZipEntry(BLOBS + sha))
                zip.write(bytes)
                zip.closeEntry()
                media++
            }
        }
        return media
    }

    /** Whether the file starting in [input] is sealed, so a password must be asked for. */
    fun isSealed(input: InputStream): Boolean {
        val head = ByteArray(8)
        var got = 0
        while (got < head.size) { val k = input.read(head, got, head.size - got); if (k < 0) break; got += k }
        return Sealed.isSealed(head.copyOf(got))
    }

    /** What is inside: opened with [password] when sealed; a bare ZIP from before sealing reads as it is. */
    private fun inside(input: InputStream, password: CharArray?): InputStream {
        val b = java.io.BufferedInputStream(input, 64)
        b.mark(16)
        val sealed = isSealed(b)
        b.reset()
        if (!sealed) return b
        return Sealed.open(b, password ?: throw PasswordNeeded())
    }

    /** The items in a backup, read from its index alone; the media are not read. */
    fun read(input: InputStream, password: CharArray?): Contents {
        ZipInputStream(inside(input, password)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: throw NotABackup()
                if (entry.name != INDEX) continue
                val o = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                if (o == null || o.optString("format") != FORMAT || o.optInt("version") > VERSION) throw NotABackup()
                val a = o.optJSONArray("records") ?: JSONArray()
                return Contents((0 until a.length()).map { PeerJson.record(a.getJSONObject(it)) },
                    o.optString("created").ifEmpty { null }, o.optString("app_version").ifEmpty { null })
            }
        }
    }

    /** Each photo, memo or file in a backup whose bytes match its name, one at a time. */
    fun media(input: InputStream, password: CharArray?, each: (sha: String, bytes: ByteArray) -> Unit) {
        ZipInputStream(inside(input, password)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return
                if (!entry.name.startsWith(BLOBS)) continue
                val sha = entry.name.removePrefix(BLOBS)
                if (!isSha(sha)) continue
                val bytes = zip.readBytes()
                if (sha256(bytes) == sha) each(sha, bytes)
            }
        }
    }

    /** What restoring [contents] would change, given what is [here]. */
    fun plan(contents: Contents, here: (id: String) -> EntityRecord?): Plan {
        var added = 0; var replaced = 0; var same = 0
        val incoming = contents.records.filter { r ->
            val local = here(r.id)
            when {
                local == null -> { added++; true }
                Lww.incomingWins(r, local) -> { replaced++; true }
                else -> { same++; false }
            }
        }
        return Plan(incoming, added, replaced, same, shas(contents.records).size)
    }

    /** The media the items name, each once. */
    fun shas(records: List<EntityRecord>): List<String> =
        records.mapNotNull { it.fields["media_sha256"]?.takeIf(::isSha) }.distinct().sorted()

    private fun isSha(s: String) = s.matches(Regex("[0-9a-f]{64}"))

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The file name offered when saving, dated like the app's own dates. */
    fun fileName(today: java.time.LocalDate = java.time.LocalDate.now()) = "HubidubiTask backup $today.hubidubitask"
}
