"""sync.db — the sync server's persistent state.

Unlike index.db this is NOT disposable: it holds device registrations, the
monotonic change sequence, and tombstones. It lives in .prodos/ (per-device,
never synced) and survives `prodos index rebuild` untouched.
"""

import sqlite3
from pathlib import Path

DDL = """
CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,            -- d_<random>
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL,       -- sha256 of the bearer token
    created TEXT NOT NULL,
    last_seen TEXT
);

CREATE TABLE IF NOT EXISTS pair_tokens (
    token_hash TEXT PRIMARY KEY,
    expires REAL NOT NULL           -- unix time; 5-minute window
);

-- The change feed. seq is the cursor: a monotonic server-side sequence,
-- never a wall-clock timestamp (clock skew silently loses changes).
CREATE TABLE IF NOT EXISTS changes (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    op TEXT NOT NULL,               -- upsert | delete
    updated TEXT NOT NULL,          -- entity's RFC 3339 updated
    device_id TEXT NOT NULL,        -- originating device ('_local' = workstation)
    content_hash TEXT NOT NULL,     -- sha256 of data ('' for deletes)
    data TEXT,                      -- entity JSON for upserts, NULL for deletes
    ts REAL NOT NULL                -- server receive time, for tombstone retention
);
CREATE INDEX IF NOT EXISTS idx_changes_entity ON changes(entity_id);

-- Last state the changelog has seen per entity; diffing the index against
-- this is what turns file edits into feed entries.
CREATE TABLE IF NOT EXISTS entity_state (
    entity_id TEXT PRIMARY KEY,
    entity_type TEXT NOT NULL,
    updated TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    file TEXT NOT NULL
);
"""


def sync_db_path(vault) -> Path:
    return vault.prodos_dir / "sync.db"


def connect(path) -> sqlite3.Connection:
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.row_factory = sqlite3.Row
    conn.executescript(DDL)
    return conn
