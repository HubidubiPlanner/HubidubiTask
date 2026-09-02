"""Write entity dicts back into vault files.

Shared by everything that mutates the vault on behalf of a remote party —
the sync server applying pushed changes, the plugin-view bridge applying
writes. Callers decide policy (conflicts, permissions); this module only
does the file surgery, keeping files the source of truth.

`existing_rel` is the vault-relative path the entity currently lives in,
when the caller knows it (from sync state or the index); without it,
upserts fall back to the canonical location for the type.
"""

import re
from datetime import datetime, timezone
from pathlib import Path

from .formats import meta as _meta
from .formats import notes_md

_CHECKBOX_RE = re.compile(r"^(\s*)- \[( |x|X)\] (.*)$")


# ------------------------------------------------------------- line helpers

def render_task_line(d: dict, indent: str = "") -> str:
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


def render_inbox_line(d: dict) -> str:
    md = {"id": d["id"]}
    if d.get("updated"):
        md["updated"] = d["updated"]
    return f"- {d.get('time') or '00:00'} {d['text']}  {_meta.render(md)}"


def replace_or_remove_line(path: Path, entity_id: str,
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

def upsert_task(vault, d: dict, existing_rel: str | None = None) -> Path:
    if existing_rel:
        path = vault.root / existing_rel
        indent = ""
        if path.exists():
            for line in path.read_text(encoding="utf-8").split("\n"):
                cb = _CHECKBOX_RE.match(line)
                if cb and _meta.extract(cb.group(3))[1].get("id") == d["id"]:
                    indent = cb.group(1)
                    break
        if replace_or_remove_line(path, d["id"], render_task_line(d, indent)):
            return path
    path = vault.tasks_dir / f"{d.get('project') or 'inbox'}.md"
    if path.exists():
        content = path.read_text(encoding="utf-8")
        if content and not content.endswith("\n"):
            content += "\n"
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        content = f"---\nproject: {path.stem}\n---\n\n"
    path.write_text(content + render_task_line(d) + "\n", encoding="utf-8")
    return path


def upsert_inbox(vault, d: dict, existing_rel: str | None = None) -> Path:
    if existing_rel:
        path = vault.root / existing_rel
        if replace_or_remove_line(path, d["id"], render_inbox_line(d)):
            return path
    date = d.get("date") or datetime.now(timezone.utc).strftime("%Y-%m-%d")
    path = vault.inbox_dir / f"{date}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(render_inbox_line(d) + "\n")
    return path


def upsert_note(vault, d: dict, existing_rel: str | None = None) -> Path:
    from .entities import Note

    if existing_rel:
        path = vault.root / existing_rel
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


def upsert_event(vault, d: dict, existing_rel: str | None = None) -> Path:
    from icalendar import Calendar
    from icalendar import Event as IcsEvent

    if existing_rel:
        path = vault.root / existing_rel
    else:
        safe = re.sub(r"[^A-Za-z0-9@._-]", "_", d.get("uid") or d["id"])
        path = vault.calendars_dir / (d.get("calendar") or "default") / f"{safe}.ics"

    def _parse(s: str):
        if len(s) == 10:  # all-day date
            return datetime.strptime(s, "%Y-%m-%d").date()
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)

    cal = Calendar()
    cal.add("VERSION", "2.0")
    cal.add("PRODID", "-//prodos//vault//EN")
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


UPSERT = {
    "task": upsert_task,
    "inbox_item": upsert_inbox,
    "note": upsert_note,
    "event": upsert_event,
}


def upsert(vault, entity_type: str, d: dict,
           existing_rel: str | None = None) -> Path:
    return UPSERT[entity_type](vault, d, existing_rel)


def delete(vault, entity_type: str, entity_id: str,
           existing_rel: str | None) -> Path | None:
    if not existing_rel:
        return None
    path = vault.root / existing_rel
    if entity_type in ("task", "inbox_item"):
        replace_or_remove_line(path, entity_id, None)
    else:  # note, event: one entity per file
        path.unlink(missing_ok=True)
    return path
