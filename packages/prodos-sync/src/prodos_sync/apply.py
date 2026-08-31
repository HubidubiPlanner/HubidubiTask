"""Apply pushed client changes to vault files.

Server-side rules (field-level merge is the client's job, Phase 5):
- Last-write-wins per entity by `updated` (RFC 3339 compares lexicographically).
- Deletion beats edit: ids are never reused, so an upsert against a
  tombstoned id is by definition a concurrent modification and is rejected.

Every accepted change is written to the files first — files stay the source
of truth — then the touched files are reindexed and the changelog captures
the result attributed to the pushing device.
"""

import re
from datetime import datetime, timezone
from pathlib import Path

from prodos_vault.formats import meta as _meta
from prodos_vault.formats import notes_md
from prodos_vault.index import indexer

from . import changelog

_CHECKBOX_RE = re.compile(r"^(\s*)- \[( |x|X)\] (.*)$")


def _state_file(sync_conn, entity_id: str) -> str | None:
    row = sync_conn.execute(
        "SELECT file FROM entity_state WHERE entity_id = ?", (entity_id,)).fetchone()
    return row["file"] if row else None


# ------------------------------------------------------------- line helpers

def _render_task_line(d: dict, indent: str = "") -> str:
    md: dict[str, str] = {"id": d["id"]}
    if d.get("due"):
        md["due"] = d["due"]
    if d.get("done_date"):
        md["done"] = d["done_date"]
    if d.get("context"):
        md["ctx"] = d["context"]
    md["updated"] = d["updated"]
    box = "x" if d.get("done") else " "
    return f"{indent}- [{box}] {d['text']}  {_meta.render(md)}"


def _render_inbox_line(d: dict) -> str:
    md = {"id": d["id"]}
    if d.get("updated"):
        md["updated"] = d["updated"]
    return f"- {d.get('time') or '00:00'} {d['text']}  {_meta.render(md)}"


def _replace_or_remove_line(path: Path, entity_id: str,
                            replacement: str | None) -> bool:
    """Replace (or, with None, remove) the line carrying entity_id."""
    if not path.exists():
        return False
    lines = path.read_text(encoding="utf-8").split("\n")
    for i, line in enumerate(lines):
        _text, md = _meta.extract(line)
        if md.get("id") != entity_id:
            continue
        if replacement is None:
            del lines[i]
        else:
            lines[i] = replacement
        path.write_text("\n".join(lines), encoding="utf-8")
        return True
    return False


# ------------------------------------------------------------- upserts

def _upsert_task(vault, sync_conn, d: dict) -> Path:
    rel = _state_file(sync_conn, d["id"])
    if rel:
        path = vault.root / rel
        m = None
        if path.exists():
            for line in path.read_text(encoding="utf-8").split("\n"):
                cb = _CHECKBOX_RE.match(line)
                if cb and _meta.extract(cb.group(3))[1].get("id") == d["id"]:
                    m = cb
                    break
        indent = m.group(1) if m else ""
        if _replace_or_remove_line(path, d["id"], _render_task_line(d, indent)):
            return path
    path = vault.tasks_dir / f"{d.get('project') or 'inbox'}.md"
    if path.exists():
        content = path.read_text(encoding="utf-8")
        if content and not content.endswith("\n"):
            content += "\n"
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        content = f"---\nproject: {path.stem}\n---\n\n"
    path.write_text(content + _render_task_line(d) + "\n", encoding="utf-8")
    return path


def _upsert_inbox(vault, sync_conn, d: dict) -> Path:
    rel = _state_file(sync_conn, d["id"])
    if rel:
        path = vault.root / rel
        if _replace_or_remove_line(path, d["id"], _render_inbox_line(d)):
            return path
    date = d.get("date") or datetime.now(timezone.utc).strftime("%Y-%m-%d")
    path = vault.inbox_dir / f"{date}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(_render_inbox_line(d) + "\n")
    return path


def _upsert_note(vault, sync_conn, d: dict) -> Path:
    from prodos_vault.entities import Note

    rel = _state_file(sync_conn, d["id"])
    if rel:
        path = vault.root / rel
    else:
        stamp = d.get("created") or d["updated"]
        path = (vault.notes_dir / stamp[:4] / stamp[5:7]
                / f"{notes_md.slugify(d.get('title') or 'untitled')}.md")
    note = Note(id=d["id"], updated=d["updated"], title=d.get("title") or "",
                body=d.get("body") or "", tags=d.get("tags") or [],
                created=d.get("created"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(notes_md.render(note), encoding="utf-8")
    return path


def _upsert_event(vault, sync_conn, d: dict) -> Path:
    from icalendar import Calendar, Event as IcsEvent

    rel = _state_file(sync_conn, d["id"])
    if rel:
        path = vault.root / rel
    else:
        safe = re.sub(r"[^A-Za-z0-9@._-]", "_", d.get("uid") or d["id"])
        path = vault.calendars_dir / (d.get("calendar") or "default") / f"{safe}.ics"

    def _parse(s: str):
        if len(s) == 10:  # all-day date
            return datetime.strptime(s, "%Y-%m-%d").date()
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)

    cal = Calendar()
    cal.add("VERSION", "2.0")
    cal.add("PRODID", "-//prodos//sync//EN")
    ev = IcsEvent()
    ev.add("UID", d.get("uid") or d["id"])
    ev.add("SUMMARY", d.get("summary") or "")
    ev.add("DTSTART", _parse(d["dtstart"]))
    if d.get("dtend"):
        ev.add("DTEND", _parse(d["dtend"]))
    if d.get("location"):
        ev.add("LOCATION", d["location"])
    if d.get("description"):
        ev.add("DESCRIPTION", d["description"])
    ev.add("LAST-MODIFIED", _parse(d["updated"]))
    ev.add("DTSTAMP", datetime.now(timezone.utc))
    cal.add_component(ev)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(cal.to_ical().decode("utf-8"), encoding="utf-8")
    return path


def _delete(vault, sync_conn, entity_id: str, entity_type: str) -> Path | None:
    rel = _state_file(sync_conn, entity_id)
    if not rel:
        return None
    path = vault.root / rel
    if entity_type in ("task", "inbox_item"):
        _replace_or_remove_line(path, entity_id, None)
    else:  # note, event: one entity per file
        path.unlink(missing_ok=True)
    return path


# ------------------------------------------------------------- entry point

def apply_changes(vault, index_conn, sync_conn, device_id: str,
                  incoming: list[dict]) -> list[dict]:
    """Apply a batch of client changes. Returns per-change results."""
    # Attribute anything already pending in the vault to the workstation
    # before the push lands, so the pusher only gets credited its own edits.
    indexer.sweep(index_conn, vault)
    changelog.capture(index_conn, sync_conn, "_local")

    results = []
    touched: set[Path] = set()
    for ch in incoming:
        eid = ch.get("id")
        op = ch.get("op", "upsert")
        etype = ch.get("type")
        updated = ch.get("updated")
        d = ch.get("data") or {}
        if not eid or not etype or not updated:
            results.append({"id": eid, "status": "invalid"})
            continue

        current = changelog.latest_updated(sync_conn, eid)
        if changelog.is_tombstoned(sync_conn, eid) and op == "upsert":
            results.append({"id": eid, "status": "rejected_tombstone"})
            continue
        if current is not None and updated <= current and op == "upsert":
            results.append({"id": eid, "status": "stale"})
            continue

        d = {**d, "id": eid, "updated": updated}
        try:
            if op == "delete":
                path = _delete(vault, sync_conn, eid, etype)
            elif etype == "task":
                path = _upsert_task(vault, sync_conn, d)
            elif etype == "inbox_item":
                path = _upsert_inbox(vault, sync_conn, d)
            elif etype == "note":
                path = _upsert_note(vault, sync_conn, d)
            elif etype == "event":
                path = _upsert_event(vault, sync_conn, d)
            else:
                results.append({"id": eid, "status": "unknown_type"})
                continue
        except (KeyError, ValueError) as e:
            results.append({"id": eid, "status": "invalid", "error": str(e)})
            continue
        if path:
            touched.add(path)
        results.append({"id": eid, "status": "applied"})

    for path in touched:
        indexer.index_file(index_conn, vault, path)
    changelog.capture(index_conn, sync_conn, device_id)
    return results
