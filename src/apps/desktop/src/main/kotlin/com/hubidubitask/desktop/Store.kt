package com.hubidubitask.desktop

import com.hubidubitask.core.EntityRecord
import com.hubidubitask.core.peer.Cursor
import com.hubidubitask.core.peer.Logged
import com.hubidubitask.core.peer.PeerJson
import com.hubidubitask.core.peer.PeerStore
import org.json.JSONObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * This computer's copy of everything, in SQLite in the app's data folder.
 *
 * `records` holds each item as it now stands; `log` gets a row for every change
 * (made here or taken in from the phone) with the next sequence number and its
 * origin, which is what peer sync reads to find the delta. Media files live
 * beside it, named by their SHA-256, as on the phone.
 */
class Store(val dir: File = defaultDir()) : PeerStore, com.hubidubitask.core.ItemSink {

    val blobs = File(dir, "blobs").apply { mkdirs() }
    private val db: Connection

    init {
        dir.mkdirs()
        // Only this user may read the folder: the items, media and the TLS identity live here.
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(dir.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        // The file keeps its HubidubiOS-era name, so the data folder moved over from
        // that version still opens with everything in it.
        db = DriverManager.getConnection("jdbc:sqlite:" + File(dir, "hubidubios.db").path)
        db.createStatement().use { s ->
            s.executeUpdate("PRAGMA journal_mode=WAL")
            s.executeUpdate("""CREATE TABLE IF NOT EXISTS records (
                id TEXT PRIMARY KEY, type TEXT NOT NULL, updated TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, json TEXT NOT NULL)""")
            s.executeUpdate("""CREATE TABLE IF NOT EXISTS log (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, origin TEXT)""")
            s.executeUpdate("CREATE INDEX IF NOT EXISTS log_id ON log(id)")
            s.executeUpdate("""CREATE TABLE IF NOT EXISTS cursors (
                peer TEXT PRIMARY KEY, theirs INTEGER NOT NULL, mine INTEGER NOT NULL)""")
            s.executeUpdate("""CREATE TABLE IF NOT EXISTS peers (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, token TEXT NOT NULL,
                paired_at TEXT NOT NULL, last_sync TEXT)""")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            // Phones paired before pairing v2 held a bearer token here; their pairing is
            // void and they pair again. Their cursors stay, so the next pairing resumes.
            // (The column stays, empty, for the schema's NOT NULL.)
            s.executeUpdate("DELETE FROM peers WHERE token <> ''")
        }
    }

    // ---- items

    @Synchronized
    override fun get(id: String): EntityRecord? =
        db.prepareStatement("SELECT json FROM records WHERE id = ?").use { p ->
            p.setString(1, id)
            p.executeQuery().use { r -> if (r.next()) PeerJson.record(JSONObject(r.getString(1))) else null }
        }

    @Synchronized
    fun all(): List<EntityRecord> = db.createStatement().use { s ->
        s.executeQuery("SELECT json FROM records WHERE deleted = 0").use { r ->
            buildList { while (r.next()) add(PeerJson.record(JSONObject(r.getString(1)))) }
        }
    }

    override fun byType(type: String) = all().filter { it.type == type }

    /** A change made here, on the Mac. */
    @Synchronized
    override fun put(record: EntityRecord) = write(record, origin = null)

    @Synchronized
    override fun applyFromPeer(record: EntityRecord, peer: String) = write(record, origin = peer)

    private fun write(r: EntityRecord, origin: String?) {
        db.prepareStatement("INSERT OR REPLACE INTO records (id, type, updated, deleted, json) VALUES (?,?,?,?,?)").use { p ->
            p.setString(1, r.id); p.setString(2, r.type); p.setString(3, r.updated)
            p.setInt(4, if (r.deleted) 1 else 0); p.setString(5, PeerJson.record(r).toString())
            p.executeUpdate()
        }
        db.prepareStatement("INSERT INTO log (id, origin) VALUES (?, ?)").use { p ->
            p.setString(1, r.id); p.setString(2, origin); p.executeUpdate()
        }
    }

    // ---- peer sync

    @Synchronized
    override fun changesSince(seq: Long, exclude: String?, limit: Int): List<Logged> {
        // Only each record's latest log entry: it carries the current state, and
        // if that entry came from the peer asking, the peer already has it.
        val sql = """SELECT l.seq, l.origin, r.json FROM log l JOIN records r ON r.id = l.id
            WHERE l.seq = (SELECT MAX(seq) FROM log WHERE id = l.id) AND l.seq > ?
            ORDER BY l.seq"""
        return db.prepareStatement(sql).use { p ->
            p.setLong(1, seq)
            p.executeQuery().use { r ->
                buildList {
                    while (r.next() && size < limit) {
                        if (exclude != null && r.getString(2) == exclude) continue
                        add(Logged(r.getLong(1), PeerJson.record(JSONObject(r.getString(3)))))
                    }
                }
            }
        }
    }

    @Synchronized
    override fun lastSeq(): Long = db.createStatement().use { s ->
        s.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM log").use { it.next(); it.getLong(1) }
    }

    @Synchronized
    override fun cursor(peer: String): Cursor =
        db.prepareStatement("SELECT theirs, mine FROM cursors WHERE peer = ?").use { p ->
            p.setString(1, peer)
            p.executeQuery().use { r -> if (r.next()) Cursor(r.getLong(1), r.getLong(2)) else Cursor() }
        }

    @Synchronized
    override fun setCursor(peer: String, cursor: Cursor) {
        db.prepareStatement("INSERT OR REPLACE INTO cursors (peer, theirs, mine) VALUES (?,?,?)").use { p ->
            p.setString(1, peer); p.setLong(2, cursor.theirs); p.setLong(3, cursor.mine); p.executeUpdate()
        }
    }

    // ---- paired phones

    /** A paired phone. Its sync key is not here but in the system's secret store ([PeerKeys]). */
    data class Peer(val id: String, val name: String, val pairedAt: String, val lastSync: String?)

    @Synchronized
    fun peers(): List<Peer> = db.createStatement().use { s ->
        s.executeQuery("SELECT id, name, paired_at, last_sync FROM peers ORDER BY paired_at").use { r ->
            buildList { while (r.next()) add(Peer(r.getString(1), r.getString(2), r.getString(3), r.getString(4))) }
        }
    }

    fun peer(id: String): Peer? = peers().firstOrNull { it.id == id }

    @Synchronized
    fun addPeer(p: Peer) {
        db.prepareStatement("INSERT OR REPLACE INTO peers (id, name, token, paired_at, last_sync) VALUES (?,?,'',?,?)").use { s ->
            s.setString(1, p.id); s.setString(2, p.name)
            s.setString(3, p.pairedAt); s.setString(4, p.lastSync); s.executeUpdate()
        }
    }

    @Synchronized
    fun removePeer(id: String) {
        db.prepareStatement("DELETE FROM peers WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
        db.prepareStatement("DELETE FROM cursors WHERE peer = ?").use { it.setString(1, id); it.executeUpdate() }
    }

    @Synchronized
    fun touchPeer(id: String, at: String) {
        db.prepareStatement("UPDATE peers SET last_sync = ? WHERE id = ?").use {
            it.setString(1, at); it.setString(2, id); it.executeUpdate()
        }
    }

    // ---- small settings

    @Synchronized
    fun kv(k: String): String? = db.prepareStatement("SELECT v FROM kv WHERE k = ?").use { p ->
        p.setString(1, k); p.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    @Synchronized
    fun setKv(k: String, v: String?) {
        if (v == null) db.prepareStatement("DELETE FROM kv WHERE k = ?").use { it.setString(1, k); it.executeUpdate() }
        else db.prepareStatement("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)").use {
            it.setString(1, k); it.setString(2, v); it.executeUpdate()
        }
    }

    fun blob(sha: String): File? = File(blobs, sha).takeIf { sha.matches(Regex("[0-9a-f]{64}")) }

    /** Keep [bytes] under their hash, as the phone does with what it captures;
     *  the phone fetches them by that hash on its next round. */
    fun putBlob(bytes: ByteArray): String {
        val sha = com.hubidubitask.core.peer.PeerPairing.sha256Hex(bytes)
        val f = File(blobs, sha)
        if (!f.exists()) f.writeBytes(bytes)
        return sha
    }

    companion object {
        fun defaultDir() = Platform.dataDir()
    }
}
