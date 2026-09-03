"""Read queries over index.db. Entities come back as dicts (the stored JSON)."""

import json
import sqlite3


def _rows_to_entities(rows) -> list[dict]:
    return [json.loads(r["data"]) for r in rows]


def _fts_literal(query: str) -> str:
    """Quote each token so raw user input (hyphens, colons, parens) is matched
    literally instead of being parsed as FTS5 operator syntax."""
    tokens = query.split()
    return " ".join('"' + t.replace('"', '""') + '"' for t in tokens)


def search(conn: sqlite3.Connection, query: str, limit: int = 20) -> list[dict]:
    rows = conn.execute(
        """
        SELECT e.data, e.file, bm25(entities_fts) AS rank
        FROM entities_fts f JOIN entities e ON e.id = f.id
        WHERE entities_fts MATCH ?
        ORDER BY rank LIMIT ?
        """,
        (_fts_literal(query), limit),
    ).fetchall()
    out = []
    for r in rows:
        d = json.loads(r["data"])
        d["_file"] = r["file"]
        out.append(d)
    return out


def task_list(conn: sqlite3.Connection, project: str | None = None,
              context: str | None = None, include_done: bool = False,
              type_: str = "task") -> list[dict]:
    """Checkbox-shaped entities of a type — `task` (Phase 3) or `next_action`
    (GTD), which share the same on-disk form under tasks/."""
    rows = conn.execute("SELECT data FROM entities WHERE type = ?", (type_,)).fetchall()
    tasks = _rows_to_entities(rows)
    if not include_done:
        tasks = [t for t in tasks if not t["done"]]
    if project:
        tasks = [t for t in tasks if t.get("project") == project]
    if context:
        tasks = [t for t in tasks if t.get("context") == context]
    tasks.sort(key=lambda t: (t.get("due") or "9999", t.get("project") or "", t["text"]))
    return tasks


def find_task(conn: sqlite3.Connection, task_id: str) -> tuple[dict, str] | None:
    row = conn.execute(
        "SELECT data, file FROM entities WHERE id = ? AND type = 'task'",
        (task_id,),
    ).fetchone()
    return (json.loads(row["data"]), row["file"]) if row else None


def find_entity(conn: sqlite3.Connection, entity_id: str) -> tuple[dict, str] | None:
    """Locate any entity by id (ids are globally unique)."""
    row = conn.execute(
        "SELECT data, file FROM entities WHERE id = ?", (entity_id,)).fetchone()
    return (json.loads(row["data"]), row["file"]) if row else None


def agenda(conn: sqlite3.Connection, start: str, end: str) -> dict[str, list[dict]]:
    """Events with dtstart in [start, end] plus tasks due in [start, end].

    start/end are YYYY-MM-DD, inclusive. dtstart is either a date or an RFC
    3339 timestamp, so comparing its first 10 chars is a date comparison.
    """
    ev_rows = conn.execute(
        """
        SELECT data FROM entities WHERE type = 'event'
        AND substr(json_extract(data, '$.dtstart'), 1, 10) BETWEEN ? AND ?
        ORDER BY json_extract(data, '$.dtstart')
        """,
        (start, end),
    ).fetchall()
    task_rows = conn.execute(
        """
        SELECT data FROM entities WHERE type = 'task'
        AND json_extract(data, '$.done') = 0
        AND json_extract(data, '$.due') BETWEEN ? AND ?
        ORDER BY json_extract(data, '$.due')
        """,
        (start, end),
    ).fetchall()
    return {
        "events": _rows_to_entities(ev_rows),
        "tasks": _rows_to_entities(task_rows),
    }


def inbox_items(conn: sqlite3.Connection) -> list[dict]:
    rows = conn.execute(
        "SELECT data FROM entities WHERE type = 'inbox_item'").fetchall()
    items = _rows_to_entities(rows)
    items.sort(key=lambda i: (i["date"], i["time"]))
    return items


def counts(conn: sqlite3.Connection) -> dict[str, int]:
    return {
        r["type"]: r["n"]
        for r in conn.execute(
            "SELECT type, COUNT(*) AS n FROM entities GROUP BY type")
    }
