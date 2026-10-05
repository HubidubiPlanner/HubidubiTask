"""Incremental indexer: one file in, that file's entities replaced.

Also the staleness sweep used by read commands, and the full rebuild
(built into a temp db, atomically renamed over index.db).
"""

import hashlib
import json
import os
import sqlite3
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
from pathlib import Path

from .. import reviews
from ..sidecars import BODY
from ..vault import Vault
from . import schema


def _mtime_rfc3339(path: Path) -> str:
    ts = path.stat().st_mtime
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _as_dict(entity) -> dict:
    return asdict(entity) if is_dataclass(entity) else dict(entity)


def _fts_fields(d: dict) -> tuple[str, str]:
    """(title, body) for the FTS row, from a normalized entity dict. Handles
    the four core types plus generic checkbox/frontmatter plugin types."""
    t = d.get("type")
    tags = d.get("tags") or []
    if t == "note" or ("body" in d and "title" in d):  # frontmatter-shaped (note, project)
        return d.get("title", ""), " ".join(
            filter(None, [d.get("body", ""), *tags]))
    if t == "event":
        return d.get("summary", ""), " ".join(
            filter(None, [d.get("calendar", ""), d.get("location") or "",
                          d.get("description") or ""]))
    if "text" in d:  # checkbox- or inbox-shaped (task, next_action, waiting_for, inbox_item)
        return d.get("text", ""), " ".join(
            filter(None, [d.get("project") or "", d.get("context") or "", *tags]))
    return str(d.get("title") or d.get("id", "")), ""


def _parse(vault: Vault, path: Path, domain: str) -> list[tuple[dict, int | None]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    mtime = _mtime_rfc3339(path)

    # Core types keep their typed parsers; everything else is dispatched by the
    # format declared in the entity registry.
    if domain == "task":
        from ..formats import tasks_md
        return [(_as_dict(t), ln) for t, ln in tasks_md.parse_file(text, path, mtime)]
    if domain == "inbox_item":
        from ..formats import inbox_md
        return [(_as_dict(i), ln) for i, ln in inbox_md.parse_file(text, path, mtime)]
    if domain == "note":
        from ..formats import notes_md
        note = notes_md.parse_file(text, path, mtime)
        return [(_as_dict(note), None)] if note else []
    if domain == "event":
        from ..formats import ics
        return [(_as_dict(e), None) for e in ics.parse_file(text, path, mtime)]

    fmt = vault.entity_format(domain)
    from ..formats import generic
    if fmt == "checkbox":
        return generic.parse_checkbox(text, path, mtime, domain)
    if fmt == "frontmatter":
        return generic.parse_frontmatter(text, path, mtime, domain)
    if fmt == "inbox":
        from ..formats import inbox_md
        return [(_as_dict(i), ln) for i, ln in inbox_md.parse_file(text, path, mtime)]
    if fmt == "ics":
        from ..formats import ics
        return [(_as_dict(e), None) for e in ics.parse_file(text, path, mtime)]
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
    st = path.stat()
    mtime_ns = st.st_mtime_ns
    size = st.st_size
    parsed = _parse(vault, path, domain)

    with conn:
        old_ids = [r["id"] for r in conn.execute(
            "SELECT id FROM entities WHERE file = ?", (rel,))]
        if old_ids:
            qs = ",".join("?" * len(old_ids))
            conn.execute(f"DELETE FROM entities_fts WHERE id IN ({qs})", old_ids)
            conn.execute(f"DELETE FROM links WHERE src_id IN ({qs})", old_ids)
        conn.execute("DELETE FROM entities WHERE file = ?", (rel,))

        for d, line in parsed:
            reviews.attach(vault, d)
            BODY.attach(vault, d)
            data = json.dumps(d, ensure_ascii=False, sort_keys=True)
            conn.execute(
                "INSERT OR REPLACE INTO entities (id, type, updated, file, line, data)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (d["id"], d["type"], d["updated"], rel, line, data),
            )
            title, body = _fts_fields(d)
            conn.execute("DELETE FROM entities_fts WHERE id = ?", (d["id"],))
            conn.execute(
                "INSERT INTO entities_fts (id, title, body) VALUES (?, ?, ?)",
                (d["id"], title, body),
            )
            if d.get("body"):  # note bodies and any frontmatter type may link
                from ..formats import notes_md

                for target in notes_md.wikilinks(d["body"]):
                    conn.execute(
                        "INSERT INTO links (src_id, dst, kind) VALUES (?, ?, 'wikilink')",
                        (d["id"], target),
                    )
        conn.execute(
            "INSERT OR REPLACE INTO files (path, mtime_ns, size, content_hash)"
            " VALUES (?, ?, ?, ?)",
            (rel, mtime_ns, size, content_hash),
        )
    return len(parsed)


def sweep(conn: sqlite3.Connection, vault: Vault) -> int:
    """Reindex files whose mtime changed and drop entries for deleted files.

    Cheap enough to run before every read command, so `hubidubios task list`
    reflects a vim edit with no watcher running.

    The quick-check is (mtime_ns, size), not mtime alone: some filesystems
    (notably overlayfs in containers) collapse rapid successive writes to the
    same mtime_ns, so an edit that changes size — e.g. a task going `[ ]`→`[x]`
    with `done:` metadata — would otherwise be missed. This is the standard
    rsync/make quick-check and stays hash-free, so the sweep budget is intact.
    """
    known = {r["path"]: (r["mtime_ns"], r["size"]) for r in conn.execute(
        "SELECT path, mtime_ns, size FROM files")}
    changed = 0
    seen = set()
    for path, _domain in vault.entity_files():
        rel = _relpath(vault, path)
        seen.add(rel)
        st = path.stat()
        if known.get(rel) != (st.st_mtime_ns, st.st_size):
            index_file(conn, vault, path)
            changed += 1
    for rel in set(known) - seen:
        remove_file(conn, vault, vault.root / rel)
        changed += 1
    return changed


def rebuild(vault: Vault) -> int:
    """Reconstruct index.db from the vault into a temp db, then atomically
    swap it in. Returns the number of entities indexed."""
    vault.hubidubios_dir.mkdir(parents=True, exist_ok=True)
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
    vault.hubidubios_dir.mkdir(parents=True, exist_ok=True)
    conn = schema.connect(vault.index_path, check_same_thread=check_same_thread)
    if do_sweep:
        sweep(conn, vault)
    return conn
