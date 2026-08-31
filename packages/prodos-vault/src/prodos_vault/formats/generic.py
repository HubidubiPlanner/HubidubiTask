"""Generic parsers for plugin-contributed entity types.

The four core types keep their typed parsers (tasks_md, inbox_md, notes_md,
ics). Plugin types that reuse the same on-disk shapes are parsed here into
plain dicts, dispatched by the `format` declared in the entity registry:

- checkbox    -> a task-style file, but the emitted `type` is the plugin's
                 (e.g. next_action, waiting_for, someday). All inline metadata
                 keys are carried through, so a type can add its own (person:,
                 defer:, energy:) without new parser code.
- frontmatter -> a note-style YAML-frontmatter document, emitted under the
                 plugin's type (e.g. project).

Every entity still carries id/type/updated; the metadata `ctx` key is
normalized to `context` to match the core task shape.
"""

from pathlib import Path

from . import meta as _meta
from .notes_md import split_frontmatter, wikilinks
from .tasks_md import _CHECKBOX_RE, _parse_frontmatter

# Metadata keys with a canonical field name; anything else passes through as-is.
_KEY_ALIASES = {"ctx": "context", "done": "done_date"}


def parse_checkbox(text: str, path: Path, mtime: str, entity_type: str,
                   ) -> list[tuple[dict, int]]:
    lines = text.split("\n")
    fm, body_start = _parse_frontmatter(lines)
    project = str(fm.get("project") or path.stem)
    file_tags = fm.get("tags") or []
    if not isinstance(file_tags, list):
        file_tags = [str(file_tags)]

    out: list[tuple[dict, int]] = []
    for lineno, line in enumerate(lines[body_start:], start=body_start + 1):
        m = _CHECKBOX_RE.match(line)
        if not m:
            continue
        done = m.group(1) in ("x", "X")
        item_text, md = _meta.extract(m.group(2))
        if "id" not in md:
            continue
        d: dict = {
            "id": md["id"],
            "type": entity_type,
            "updated": md.get("updated", mtime),
            "text": item_text.strip(),
            "done": done,
            "project": project,
            "tags": [str(t) for t in file_tags],
        }
        for k, v in md.items():
            if k in ("id", "updated"):
                continue
            d[_KEY_ALIASES.get(k, k)] = v
        d.setdefault("context", None)
        d.setdefault("due", None)
        out.append((d, lineno))
    return out


def parse_frontmatter(text: str, path: Path, mtime: str, entity_type: str,
                      ) -> list[tuple[dict, None]]:
    fm, body = split_frontmatter(text)
    entity_id = fm.get("id")
    if not entity_id:
        return []
    tags = fm.get("tags") or []
    if not isinstance(tags, list):
        tags = [str(tags)]
    d: dict = {
        "id": str(entity_id),
        "type": entity_type,
        "updated": _ts(fm.get("updated") or mtime),
        "title": str(fm.get("title") or path.stem),
        "body": body.strip("\n"),
        "tags": [str(t) for t in tags],
    }
    for k, v in fm.items():
        if k in ("id", "title", "tags", "updated"):
            continue
        d[k] = _ts(v) if k in ("created", "due") and _looks_like_ts(v) else v
    return [(d, None)]


def wikilinks_of(body: str) -> list[str]:
    return wikilinks(body)


def _looks_like_ts(v) -> bool:
    from datetime import date, datetime

    return isinstance(v, (date, datetime))


def _ts(value) -> str:
    from datetime import datetime, timezone

    if isinstance(value, datetime):
        if value.tzinfo:
            value = value.astimezone(timezone.utc)
        return value.strftime("%Y-%m-%dT%H:%M:%SZ")
    return str(value)
