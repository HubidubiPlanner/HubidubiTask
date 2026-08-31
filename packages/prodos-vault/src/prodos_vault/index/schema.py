"""index.db schema. The index is disposable — rebuildable from the vault at
any time. If the index is lost, nothing is lost."""

import sqlite3

DDL = """
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,          -- vault-relative, posix separators
    mtime_ns INTEGER NOT NULL,
    content_hash TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS entities (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,             -- task | event | note | inbox_item
    updated TEXT NOT NULL,          -- RFC 3339
    file TEXT NOT NULL,
    line INTEGER,
    data TEXT NOT NULL              -- full entity as JSON
);
CREATE INDEX IF NOT EXISTS idx_entities_type ON entities(type);
CREATE INDEX IF NOT EXISTS idx_entities_file ON entities(file);

CREATE TABLE IF NOT EXISTS links (
    src_id TEXT NOT NULL,
    dst TEXT NOT NULL,              -- target note title/slug; may be unresolved
    kind TEXT NOT NULL              -- wikilink
);
CREATE INDEX IF NOT EXISTS idx_links_src ON links(src_id);

CREATE VIRTUAL TABLE IF NOT EXISTS entities_fts USING fts5(
    id UNINDEXED, title, body
);
"""


def connect(db_path, check_same_thread: bool = True) -> sqlite3.Connection:
    """check_same_thread=False is safe only when the caller serializes access
    (the sync server does, behind one lock)."""
    conn = sqlite3.connect(db_path, check_same_thread=check_same_thread)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.row_factory = sqlite3.Row
    conn.executescript(DDL)
    return conn
