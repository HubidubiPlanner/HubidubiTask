"""Inbox files: append-only GTD capture, one file per day, newest at the bottom.

    - 09:14 Ask Sam about the quarterly report  <!-- id:i_x9y8 -->

No structure beyond a timestamp and an id — classification happens later, in
the clarify step. The inbox is a queue, not an archive.
"""

import re
from pathlib import Path

from ..entities import InboxItem
from . import meta as _meta

_ITEM_RE = re.compile(r"^- (\d{2}:\d{2}) (.*)$")
_FILENAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})\.md$")


def date_of(path: Path) -> str | None:
    m = _FILENAME_RE.match(path.name)
    return m.group(1) if m else None


def parse_file(text: str, path: Path, file_mtime_rfc3339: str) -> list[tuple[InboxItem, int]]:
    date = date_of(path) or ""
    items: list[tuple[InboxItem, int]] = []
    for lineno, line in enumerate(text.split("\n"), start=1):
        m = _ITEM_RE.match(line)
        if not m:
            continue
        time, rest = m.group(1), m.group(2)
        item_text, md = _meta.extract(rest)
        if "id" not in md:
            continue
        # Capture time is the natural `updated`: inbox items are immutable
        # after capture (processed items are deleted, not edited).
        updated = md.get("updated") or (
            f"{date}T{time}:00Z" if date else file_mtime_rfc3339
        )
        items.append(
            (
                InboxItem(
                    id=md["id"],
                    updated=updated,
                    text=item_text.strip(),
                    time=time,
                    date=date,
                ),
                lineno,
            )
        )
    return items


def format_item_line(text: str, time_hhmm: str, item_id: str) -> str:
    return f"- {time_hhmm} {text}  {_meta.render({'id': item_id})}"
