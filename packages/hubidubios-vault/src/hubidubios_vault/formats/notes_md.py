"""Notes: markdown with YAML frontmatter, at notes/YYYY/MM/<slug>.md.

    ---
    id: n_ab12cd34
    title: Sandbox design
    created: 2026-08-31T10:00:00Z
    updated: 2026-08-31T10:00:00Z
    tags: [dev]
    ---

    Body text with [[wiki-links]] to other notes.
"""

import re
from pathlib import Path

from ..entities import Note

WIKILINK_RE = re.compile(r"\[\[([^\]|#]+)(?:[#|][^\]]*)?\]\]")
_FM_DELIM = "---"


def split_frontmatter(text: str) -> tuple[dict, str]:
    lines = text.split("\n")
    if not lines or lines[0].strip() != _FM_DELIM:
        return {}, text
    for i in range(1, len(lines)):
        if lines[i].strip() == _FM_DELIM:
            import yaml

            try:
                fm = yaml.safe_load("\n".join(lines[1:i])) or {}
            except yaml.YAMLError:
                fm = {}
            body = "\n".join(lines[i + 1:])
            return (fm if isinstance(fm, dict) else {}), body
    return {}, text


def _ts(value) -> str:
    """YAML parses RFC 3339 timestamps into datetime; render them back."""
    from datetime import datetime, timezone

    if isinstance(value, datetime):
        if value.tzinfo:
            value = value.astimezone(timezone.utc)
        return value.strftime("%Y-%m-%dT%H:%M:%SZ")
    return str(value)


def parse_file(text: str, path: Path, file_mtime_rfc3339: str) -> Note | None:
    fm, body = split_frontmatter(text)
    note_id = fm.get("id")
    if not note_id:
        return None
    tags = fm.get("tags") or []
    if not isinstance(tags, list):
        tags = [str(tags)]
    return Note(
        id=str(note_id),
        updated=_ts(fm.get("updated") or file_mtime_rfc3339),
        title=str(fm.get("title") or path.stem),
        body=body.strip("\n"),
        tags=[str(t) for t in tags],
        created=_ts(fm["created"]) if fm.get("created") else None,
        kind=str(fm.get("kind") or "text"),
        media=str(fm["media"]) if fm.get("media") else None,
        media_sha256=str(fm["media_sha256"]) if fm.get("media_sha256") else None,
        media_mime=str(fm["media_mime"]) if fm.get("media_mime") else None,
        media_bytes=int(fm["media_bytes"]) if fm.get("media_bytes") else None,
        parent=str(fm["parent"]) if fm.get("parent") else None,
        due=str(fm["due"]) if fm.get("due") else None,
    )


def wikilinks(body: str) -> list[str]:
    return [m.strip() for m in WIKILINK_RE.findall(body)]


def render(note: Note) -> str:
    fm_lines = [
        _FM_DELIM,
        f"id: {note.id}",
        f"title: {note.title}",
    ]
    if note.created:
        fm_lines.append(f"created: {note.created}")
    fm_lines.append(f"updated: {note.updated}")
    if note.kind and note.kind != "text":
        fm_lines.append(f"kind: {note.kind}")
    if note.media:
        fm_lines.append(f"media: {note.media}")
    if note.media_sha256:
        fm_lines.append(f"media_sha256: {note.media_sha256}")
    if note.media_mime:
        fm_lines.append(f"media_mime: {note.media_mime}")
    if note.media_bytes:
        fm_lines.append(f"media_bytes: {note.media_bytes}")
    if note.parent:
        fm_lines.append(f"parent: {note.parent}")
    if note.due:
        fm_lines.append(f"due: {note.due}")
    if note.tags:
        fm_lines.append(f"tags: [{', '.join(note.tags)}]")
    fm_lines.append(_FM_DELIM)
    body = note.body
    return "\n".join(fm_lines) + "\n\n" + (body + "\n" if body else "")


def slugify(title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return slug or "untitled"
