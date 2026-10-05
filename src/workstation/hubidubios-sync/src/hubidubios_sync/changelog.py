"""Turn vault state into a cursor-based change feed.

`capture()` diffs the (disposable) entity index against sync.db's persistent
`entity_state` and appends entity-level changes. Content-hash diffing means a
full `hubidubios index rebuild` emits nothing unless files actually changed.

Deletions are tombstones with 90-day retention, never hard deletes.
"""

import hashlib
import json
import time

TOMBSTONE_RETENTION_S = 90 * 24 * 3600
FEED_BATCH_LIMIT = 500


def _hash(data_json: str) -> str:
    return hashlib.sha256(data_json.encode("utf-8")).hexdigest()


def capture(index_conn, sync_conn, device_id: str = "_local") -> int:
    """Diff the index against entity_state; append changes. Returns count."""
    current: dict[str, tuple[str, str, str, str]] = {}
    for r in index_conn.execute("SELECT id, type, updated, file, data FROM entities"):
        current[r["id"]] = (r["type"], r["updated"], r["file"], r["data"])

    known = {
        r["entity_id"]: (r["content_hash"], r["updated"])
        for r in sync_conn.execute(
            "SELECT entity_id, content_hash, updated FROM entity_state")
    }

    now = time.time()
    n = 0
    with sync_conn:
        for eid, (etype, updated, file, data) in current.items():
            h = _hash(data)
            if eid in known and known[eid][0] == h:
                continue
            sync_conn.execute(
                "INSERT INTO changes (entity_id, entity_type, op, updated,"
                " device_id, content_hash, data, ts)"
                " VALUES (?, ?, 'upsert', ?, ?, ?, ?, ?)",
                (eid, etype, updated, device_id, h, data, now),
            )
            sync_conn.execute(
                "INSERT OR REPLACE INTO entity_state"
                " (entity_id, entity_type, updated, content_hash, file)"
                " VALUES (?, ?, ?, ?, ?)",
                (eid, etype, updated, h, file),
            )
            n += 1
        for eid in set(known) - set(current):
            row = sync_conn.execute(
                "SELECT entity_type, updated FROM entity_state WHERE entity_id = ?",
                (eid,)).fetchone()
            sync_conn.execute(
                "INSERT INTO changes (entity_id, entity_type, op, updated,"
                " device_id, content_hash, data, ts)"
                " VALUES (?, ?, 'delete', ?, ?, '', NULL, ?)",
                (eid, row["entity_type"], row["updated"], device_id, now),
            )
            sync_conn.execute(
                "DELETE FROM entity_state WHERE entity_id = ?", (eid,))
            n += 1
    return n


def feed(sync_conn, since: int, limit: int = FEED_BATCH_LIMIT) -> dict:
    """Changes after cursor `since`, collapsed to the latest change per entity
    within the batch window."""
    rows = sync_conn.execute(
        "SELECT * FROM changes WHERE seq > ? ORDER BY seq LIMIT ?",
        (since, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]
    cursor = rows[-1]["seq"] if rows else since

    latest: dict[str, dict] = {}
    for r in rows:  # seq order: later rows overwrite earlier ones
        latest[r["entity_id"]] = {
            "seq": r["seq"],
            "id": r["entity_id"],
            "type": r["entity_type"],
            "op": r["op"],
            "updated": r["updated"],
            "device_id": r["device_id"],
            "content_hash": r["content_hash"],
            "data": json.loads(r["data"]) if r["data"] else None,
        }
    changes = sorted(latest.values(), key=lambda c: c["seq"])
    return {"changes": changes, "cursor": cursor, "has_more": has_more}


def latest_updated(sync_conn, entity_id: str) -> str | None:
    """The entity's current `updated`, from live state or its tombstone."""
    row = sync_conn.execute(
        "SELECT updated FROM entity_state WHERE entity_id = ?",
        (entity_id,)).fetchone()
    if row:
        return row["updated"]
    row = sync_conn.execute(
        "SELECT updated FROM changes WHERE entity_id = ? ORDER BY seq DESC LIMIT 1",
        (entity_id,)).fetchone()
    return row["updated"] if row else None


def is_tombstoned(sync_conn, entity_id: str) -> bool:
    row = sync_conn.execute(
        "SELECT op FROM changes WHERE entity_id = ? ORDER BY seq DESC LIMIT 1",
        (entity_id,)).fetchone()
    return bool(row and row["op"] == "delete")


def purge_expired_tombstones(sync_conn) -> int:
    """Drop delete-rows past retention. Superseded upsert rows for still-live
    entities are also compacted (only the latest per entity matters)."""
    cutoff = time.time() - TOMBSTONE_RETENTION_S
    with sync_conn:
        cur = sync_conn.execute(
            """
            DELETE FROM changes WHERE op = 'delete' AND ts < ?
            AND seq = (SELECT MAX(seq) FROM changes c2
                       WHERE c2.entity_id = changes.entity_id)
            """,
            (cutoff,),
        )
        n = cur.rowcount
        sync_conn.execute(
            """
            DELETE FROM changes
            WHERE seq NOT IN (SELECT MAX(seq) FROM changes GROUP BY entity_id)
            AND ts < ?
            """,
            (cutoff,),
        )
    return n
