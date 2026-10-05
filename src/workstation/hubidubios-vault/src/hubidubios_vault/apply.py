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

from . import reviews
from .sidecars import BODY
from .formats import meta as _meta
from .formats import notes_md

_CHECKBOX_RE = re.compile(r"^(\s*)- \[( |x|X)\] (.*)$")


# ------------------------------------------------------------- line helpers

def _yes(v) -> bool:
    return v is True or (isinstance(v, str) and v.strip().lower() in ("true", "1", "yes"))


def render_task_line(d: dict, indent: str = "") -> str:
    md: dict[str, str] = {"id": d["id"]}
    if d.get("due"):
        md["due"] = d["due"]
    if d.get("done_date"):
        md["done"] = d["done_date"]
    if d.get("context"):
        md["ctx"] = d["context"]
    if d.get("who"):
        md["who"] = d["who"]
    if d.get("parent"):
        # Nesting is explicit rather than inferred from indentation: indentation
        # is cosmetic and does not survive a user reordering lines by hand.
        md["parent"] = d["parent"]
    md["updated"] = d["updated"]
    # The phone's fields are all strings, so "false" arrives as a non-empty
    # (truthy) string; only a real yes counts, or every task it creates would
    # be written already done.
    box = "x" if _yes(d.get("done")) else " "
    return f"{indent}- [{box}] {d['text']}  {_meta.render(md)}"


def render_inbox_line(d: dict) -> str:
    md = {"id": d["id"]}
    # A capture from the phone may be a photo or voice memo. Without these the
    # attachment reference is silently dropped on write-back and the uploaded
    # blob can never be matched to its item.
    if (d.get("kind") or "text") != "text":
        md["kind"] = d["kind"]
    if d.get("media"):
        md["media"] = d["media"]
    if d.get("media_sha256"):
        md["sha"] = d["media_sha256"]
    if d.get("media_mime"):
        md["mime"] = d["media_mime"]
    if d.get("media_bytes") is not None:
        md["bytes"] = str(d["media_bytes"])
    if d.get("due"):
        md["due"] = d["due"]
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


def _store_review(d: dict, vault) -> None:
    """Park a line entity's review in its sidecar.

    A note holds its own in frontmatter; a checkbox or inbox line cannot hold
    prose at all. "review" absent from the payload means "not sent" — an older
    client that knows nothing about reviews must not wipe one — so only an
    explicit empty value clears it.
    """
    if "review" in d:
        reviews.write(vault, d["id"], d.get("review"))
    # The item's own description (Todoist's, or anything else longer than a
    # line) follows the same rule: absent means "not sent", empty clears.
    if "body" in d:
        BODY.write(vault, d["id"], d.get("body"))


# ------------------------------------------------------------- upserts

def _checkbox_upsert(vault, d: dict, default_path: Path,
                     existing_rel: str | None, project_header: bool) -> Path:
    """Update the checkbox line for d in place if we know where it lives, else
    append it to default_path. Shared by all checkbox-format types (task,
    next_action, waiting_for, someday, archived) so each lands in its own
    state directory."""
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
            _store_review(d, vault)
            return path
    path = default_path
    if path.exists():
        content = path.read_text(encoding="utf-8")
        if content and not content.endswith("\n"):
            content += "\n"
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        content = f"---\nproject: {path.stem}\n---\n\n" if project_header else ""
    path.write_text(content + render_task_line(d) + "\n", encoding="utf-8")
    _store_review(d, vault)
    return path


def upsert_task(vault, d: dict, existing_rel: str | None = None) -> Path:
    return _checkbox_upsert(
        vault, d, vault.tasks_dir / f"{d.get('project') or 'inbox'}.md",
        existing_rel, project_header=True)


def upsert_next_action(vault, d: dict, existing_rel: str | None = None) -> Path:
    return _checkbox_upsert(
        vault, d, vault.current_dir / f"{d.get('project') or 'inbox'}.md",
        existing_rel, project_header=True)


def upsert_waiting(vault, d: dict, existing_rel: str | None = None) -> Path:
    slug = re.sub(r"[^a-z0-9]+", "-", (d.get("who") or "").lower()).strip("-")
    return _checkbox_upsert(
        vault, d, vault.waiting_dir / f"{slug or 'waiting'}.md",
        existing_rel, project_header=False)


def upsert_someday(vault, d: dict, existing_rel: str | None = None) -> Path:
    return _checkbox_upsert(
        vault, d, vault.maybe_dir / "maybe.md", existing_rel, project_header=False)


def upsert_archived(vault, d: dict, existing_rel: str | None = None) -> Path:
    ym = (d.get("done_date") or d["updated"])[:7]
    return _checkbox_upsert(
        vault, d, vault.archive_dir / f"{ym}.md", existing_rel, project_header=False)


def upsert_project(vault, d: dict, existing_rel: str | None = None) -> Path:
    """A project is a frontmatter file under projects/. Preserves the body if we
    are updating one we can find."""
    if existing_rel:
        path = vault.root / existing_rel
    else:
        slug = re.sub(r"[^a-z0-9]+", "-", (d.get("title") or "project").lower()).strip("-")
        path = vault.projects_dir / f"{slug or 'project'}.md"
    fm_lines = [f"id: {d['id']}", f"title: {d.get('title') or ''}"]
    if d.get("status"):
        fm_lines.append(f"status: {d['status']}")
    if d.get("outcome"):
        fm_lines.append(f"outcome: {d['outcome']}")
    if d.get("parent"):
        fm_lines.append(f"parent: {d['parent']}")
    items = d.get("items") or []
    fm_lines.append("items: " + ("[]" if not items else ""))
    for it in items:
        fm_lines.append(f"  - {it}")
    if d.get("created"):
        fm_lines.append(f"created: {d['created']}")
    fm_lines.append(f"updated: {d['updated']}")
    body = d.get("body") or f"Outcome: {d.get('title') or ''}\n\n## Next actions\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("---\n" + "\n".join(fm_lines) + "\n---\n\n" + body, encoding="utf-8")
    return path


def upsert_inbox(vault, d: dict, existing_rel: str | None = None) -> Path:
    # The phone sends only the content hash — it has no idea where the vault
    # keeps files — so derive the canonical location here, exactly as a note
    # does. Without this a media capture has a sha but no path, and the bytes
    # are never materialized.
    d = dict(d)
    if d.get("media_sha256") and not d.get("media"):
        d["media"] = str((vault.inbox_dir / "media"
                          / f"{d['id']}.{_media_ext(d)}").relative_to(vault.root))

    if existing_rel:
        path = vault.root / existing_rel
        if replace_or_remove_line(path, d["id"], render_inbox_line(d)):
            _materialize_inbox_media(vault, d)
            _store_review(d, vault)
            return path
    date = d.get("date") or datetime.now(timezone.utc).strftime("%Y-%m-%d")
    path = vault.inbox_dir / f"{date}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(render_inbox_line(d) + "\n")
    _materialize_inbox_media(vault, d)
    _store_review(d, vault)
    return path


def _materialize_inbox_media(vault, d: dict) -> None:
    """Copy the staged blob into the vault when the bytes are already here.

    The blob upload and the entity can arrive in either order: if the blob is
    still missing the item is written now and the file is filled in when the
    upload lands (see the sync server's blob handler).
    """
    from . import blobs

    sha, media_rel = d.get("media_sha256"), d.get("media")
    if not sha or not media_rel:
        return
    target = vault.root / media_rel
    staged = blobs.staged_path(vault, sha)
    need = not target.exists() or blobs.sha256_of(target.read_bytes()) != sha
    if need and staged.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(staged.read_bytes())


_MIME_EXT = {
    "application/pdf": "pdf", "text/plain": "txt", "text/markdown": "md",
    "application/msword": "doc", "text/csv": "csv",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp",
    "image/heic": "heic", "image/gif": "gif",
    "audio/mp4": "m4a", "audio/m4a": "m4a", "audio/aac": "m4a",
    "audio/mpeg": "mp3", "audio/ogg": "ogg", "audio/wav": "wav",
    "audio/x-wav": "wav", "audio/webm": "webm",
}


def _media_ext(d: dict) -> str:
    if d.get("media"):
        suf = Path(d["media"]).suffix.lstrip(".")
        if suf:
            return suf
    return _MIME_EXT.get((d.get("media_mime") or "").lower(), "bin")


def upsert_note(vault, d: dict, existing_rel: str | None = None) -> Path:
    from . import blobs
    from .entities import Note

    if existing_rel:
        path = vault.root / existing_rel
    else:
        stamp = d.get("created") or d["updated"]
        folder = vault.notes_dir / stamp[:4] / stamp[5:7]
        slug = notes_md.slugify(d.get('title') or 'untitled')
        path = folder / f"{slug}.md"
        # Two notes of the same name in one month would share a file and the
        # second would overwrite the first; give the newcomer its own.
        n = 2
        while path.exists() and f"id: {d['id']}" not in path.read_text(encoding="utf-8"):
            path = folder / f"{slug}-{n}.md"
            n += 1

    media_rel = d.get("media")
    if d.get("media_sha256") and not media_rel:
        # Canonical: the media file sits next to its note .md, same stem.
        media_rel = str(path.with_suffix("." + _media_ext(d)).relative_to(vault.root))

    note = Note(id=d["id"], updated=d["updated"], title=d.get("title") or "",
                body=d.get("body") or "", tags=d.get("tags") or [],
                created=d.get("created"), kind=d.get("kind") or "text",
                media=media_rel, media_sha256=d.get("media_sha256"),
                media_mime=d.get("media_mime"), media_bytes=d.get("media_bytes"),
                parent=d.get("parent"), due=d.get("due"),
                review=d.get("review"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(notes_md.render(note), encoding="utf-8")

    # Materialize the media file from the staged blob when we have the bytes and
    # the target is missing or stale. If the blob hasn't arrived yet, the note is
    # written now and the file is filled in when the blob upload lands.
    if note.media_sha256 and media_rel:
        target = vault.root / media_rel
        staged = blobs.staged_path(vault, note.media_sha256)
        need = not target.exists() or blobs.sha256_of(target.read_bytes()) != note.media_sha256
        if need and staged.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(staged.read_bytes())
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
    cal.add("PRODID", "-//hubidubios//vault//EN")
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


# Checkbox-line types: edits/deletes are line surgery within a shared file.
_LINE_TYPES = {"task", "inbox_item", "next_action", "waiting_for",
               "someday", "archived"}

UPSERT = {
    "task": upsert_task,
    "inbox_item": upsert_inbox,
    "note": upsert_note,
    "event": upsert_event,
    "next_action": upsert_next_action,
    "waiting_for": upsert_waiting,
    "someday": upsert_someday,
    "archived": upsert_archived,
    "project": upsert_project,
}


def upsert(vault, entity_type: str, d: dict,
           existing_rel: str | None = None) -> Path:
    return UPSERT[entity_type](vault, d, existing_rel)


def _attachment_of(vault, entity_type: str, entity_id: str, path: Path) -> Path | None:
    """The media file an entity owns, read before the entity is removed."""
    if not path.exists():
        return None
    try:
        if entity_type in _LINE_TYPES:
            for line in path.read_text(encoding="utf-8").split("\n"):
                _text, md = _meta.extract(line)
                if md.get("id") == entity_id and md.get("media"):
                    return vault.root / md["media"]
        elif entity_type == "note":
            note = notes_md.parse_file(path.read_text(encoding="utf-8"), path, "")
            if note is not None and note.media:
                return vault.root / note.media
    except (OSError, ValueError):
        return None
    return None


def delete(vault, entity_type: str, entity_id: str,
           existing_rel: str | None) -> Path | None:
    if not existing_rel:
        return None
    path = vault.root / existing_rel
    # Look the attachment up first: once the line or the file is gone there is
    # nothing left pointing at it, and the bytes would sit in the vault forever
    # belonging to nothing.
    media = _attachment_of(vault, entity_type, entity_id, path)
    if entity_type in _LINE_TYPES:
        replace_or_remove_line(path, entity_id, None)
    else:  # note, event, project: one entity per file
        path.unlink(missing_ok=True)
    if media is not None:
        try:
            media.unlink(missing_ok=True)
        except OSError:
            pass
    # The review and description belong to the item, so they go with it.
    reviews.remove(vault, entity_id)
    if entity_type in _LINE_TYPES:
        BODY.remove(vault, entity_id)
    return path
