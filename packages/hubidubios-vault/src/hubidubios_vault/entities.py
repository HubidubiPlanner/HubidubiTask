"""Entity model.

Every entity, without exception, carries `id`, `updated` (RFC 3339), and
`type`. This is load-bearing for Phases 2, 3, and 5 — sync cannot be
retrofitted onto entities without stable identity.

`updated` provenance: entities written by the CLI carry an explicit inline
timestamp; hand-edited entities that lack one get the file's mtime at index
time (the indexer never writes back into the user's file).
"""

from dataclasses import dataclass, field
from datetime import datetime, timezone


def now_rfc3339() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class Entity:
    id: str
    updated: str  # RFC 3339
    type: str = field(init=False, default="")


@dataclass
class Task(Entity):
    text: str = ""
    done: bool = False
    project: str = ""
    due: str | None = None  # YYYY-MM-DD
    done_date: str | None = None  # YYYY-MM-DD
    context: str | None = None  # @computer, @errands, ...
    tags: list[str] = field(default_factory=list)

    def __post_init__(self):
        self.type = "task"


@dataclass
class Event(Entity):
    summary: str = ""
    dtstart: str = ""  # RFC 3339 or YYYY-MM-DD for all-day
    dtend: str | None = None
    all_day: bool = False
    calendar: str = ""
    location: str | None = None
    description: str | None = None
    uid: str = ""  # raw iCal UID; entity id is derived from it

    def __post_init__(self):
        self.type = "event"


@dataclass
class Note(Entity):
    title: str = ""
    body: str = ""
    tags: list[str] = field(default_factory=list)
    created: str | None = None
    # A note is one of three kinds. text = body only; image/audio carry an
    # attachment: a plain file in notes/ referenced by relative path + SHA-256,
    # whose bytes move over the sync blob channel (never inside the entity).
    kind: str = "text"  # "text" | "image" | "audio"
    media: str | None = None        # vault-relative path, e.g. notes/2026/09/x.jpg
    media_sha256: str | None = None
    media_mime: str | None = None
    media_bytes: int | None = None

    def __post_init__(self):
        self.type = "note"


@dataclass
class InboxItem(Entity):
    text: str = ""
    time: str = ""  # HH:MM, local capture time
    date: str = ""  # YYYY-MM-DD, from the inbox filename
    # A capture from the phone may be a photo or a voice memo rather than typed
    # text. The attachment is handled exactly like a note's: a plain file in the
    # vault referenced by relative path + SHA-256, whose bytes travel over the
    # sync blob channel and never inside the entity. `text` stays the human
    # description, so the inbox is still readable and clarifiable as a list.
    kind: str = "text"  # "text" | "image" | "audio"
    media: str | None = None        # vault-relative path, e.g. inbox/media/x.jpg
    media_sha256: str | None = None
    media_mime: str | None = None
    media_bytes: int | None = None

    def __post_init__(self):
        self.type = "inbox_item"
