"""Incremental indexer: one file in, that file's entities replaced.

Also the staleness sweep used by read commands, and the full rebuild
(built into a temp db, atomically renamed over index.db).
"""

import hashlib
import json
import os
import sqlite3
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from ..vault import Vault
from . import schema


def _mtime_rfc3339(path: Path) -> str:
    ts = path.stat().st_mtime
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _fts_fields(entity) -> tuple[str, str]:
    t = entity.type
    if t == "task":
        return entity.text, " ".join(
            filter(None, [entity.project, entity.context or "", *entity.tags])
        )
    if t == "note":
        return entity.title, entity.body + (" " + " ".join(entity.tags) if entity.tags else "")
    if t == "event":
        return entity.summary, " ".join(
            filter(None, [entity.calendar, entity.location or "", entity.description or ""])
        )
    return entity.text, ""  # inbox_item


def _parse(vault: Vault, path: Path, domain: str) -> list[tuple[object, int | None]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    mtime = _mtime_rfc3339(path)
    if domain == "task":
        from ..formats import tasks_md

        return [(t, ln) for t, ln in tasks_md.parse_file(text, path, mtime)]
    if domain == "inbox_item":
        from ..formats import inbox_md

        return [(i, ln) for i, ln in inbox_md.parse_file(text, path, mtime)]
    if domain == "note":
        from ..formats import notes_md

        note = notes_md.parse_file(text, path, mtime)
        return [(note, None)] if note else []
    if domain == "event":
        from ..formats import ics

        return [(e, None) for e in ics.parse_file(text, path, mtime)]
    return []


def _relpath(vault: Vault, path: Path) -> str:
    return path.resolve().relative_to(vault.root.resolve()).as_posix()


def remove_file(conn: sqlite3.Connection, vault: Vault, path: Path) -> None:
    rel = _relpath(vault, path)
    with conn:
        ids = [r["id"] for r in conn.execute(
            "SELECT id FROM entities WHERE file = ?", (rel,))]
        if ids:
            qs = ",".join("?" * len(ids))
            conn.execute(f"DELETE FROM entities_fts WHERE id IN ({qs})", ids)
            conn.execute(f"DELETE FROM links WHERE src_id IN ({qs})", ids)
        conn.execute("DELETE FROM entities WHERE file = ?", (rel,))
        conn.execute("DELETE FROM files WHERE path = ?", (rel,))


def index_file(conn: sqlite3.Connection, vault: Vault, path: Path) -> int:
    """(Re)index one file. Returns the number of entities indexed."""
    domain = vault.classify(path)
    if not domain:
        return 0
    if not path.exists():
        remove_file(conn, vault, path)
        return 0

    rel = _relpath(vault, path)
    raw = path.read_bytes()
    content_hash = hashlib.sha256(raw).hexdigest()
    mtime_ns = path.stat().st_mtime_ns
    parsed = _parse(vault, path, domain)

    with conn:
        old_ids = [r["id"] for r in conn.execute(
            "SELECT id FROM entities WHERE file = ?", (rel,))]
        if old_ids:
            qs = ",".join("?" * len(old_ids))
            conn.execute(f"DELETE FROM entities_fts WHERE id IN ({qs})", old_ids)
            conn.execute(f"DELETE FROM links WHERE src_id IN ({qs})", old_ids)
        conn.execute("DELETE FROM entities WHERE file = ?", (rel,))

        for entity, line in parsed:
            data = json.dumps(asdict(entity), ensure_ascii=False, sort_keys=True)
            conn.execute(
                "INSERT OR REPLACE INTO entities (id, type, updated, file, line, data)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (entity.id, entity.type, entity.updated, rel, line, data),
            )
            title, body = _fts_fields(entity)
            conn.execute("DELETE FROM entities_fts WHERE id = ?", (entity.id,))
            conn.execute(
                "INSERT INTO entities_fts (id, title, body) VALUES (?, ?, ?)",
                (entity.id, title, body),
            )
            if entity.type == "note":
                from ..formats import notes_md

                for target in notes_md.wikilinks(entity.body):
                    conn.execute(
                        "INSERT INTO links (src_id, dst, kind) VALUES (?, ?, 'wikilink')",
                        (entity.id, target),
                    )
        conn.execute(
            "INSERT OR REPLACE INTO files (path, mtime_ns, content_hash) VALUES (?, ?, ?)",
            (rel, mtime_ns, content_hash),
        )
    return len(parsed)


def sweep(conn: sqlite3.Connection, vault: Vault) -> int:
    """Reindex files whose mtime changed and drop entries for deleted files.

    Cheap enough to run before every read command, so `prodos task list`
    reflects a vim edit with no watcher running.
    """
    known = {r["path"]: r["mtime_ns"] for r in conn.execute(
        "SELECT path, mtime_ns FROM files")}
    changed = 0
    seen = set()
    for path, _domain in vault.entity_files():
        rel = _relpath(vault, path)
        seen.add(rel)
        if known.get(rel) != path.stat().st_mtime_ns:
            index_file(conn, vault, path)
            changed += 1
    for rel in set(known) - seen:
        remove_file(conn, vault, vault.root / rel)
        changed += 1
    return changed


def rebuild(vault: Vault) -> int:
    """Reconstruct index.db from the vault into a temp db, then atomically
    swap it in. Returns the number of entities indexed."""
    vault.prodos_dir.mkdir(parents=True, exist_ok=True)
    tmp = vault.index_path.with_suffix(".db.rebuild")
    for leftover in (tmp, tmp.with_suffix(".rebuild-wal"), tmp.with_suffix(".rebuild-shm")):
        leftover.unlink(missing_ok=True)
    conn = schema.connect(tmp)
    total = 0
    try:
        for path, _domain in vault.entity_files():
            total += index_file(conn, vault, path)
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    finally:
        conn.close()
    for suffix in ("-wal", "-shm"):
        Path(str(vault.index_path) + suffix).unlink(missing_ok=True)
    os.replace(tmp, vault.index_path)
    return total


def open_index(vault: Vault, do_sweep: bool = True,
               check_same_thread: bool = True) -> sqlite3.Connection:
    """Open (creating if absent) the vault index, optionally sweeping stale files."""
    vault.prodos_dir.mkdir(parents=True, exist_ok=True)
    conn = schema.connect(vault.index_path, check_same_thread=check_same_thread)
    if do_sweep:
        sweep(conn, vault)
    return conn
