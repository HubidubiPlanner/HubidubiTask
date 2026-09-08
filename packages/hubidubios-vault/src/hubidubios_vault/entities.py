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

    def __post_init__(self):
        self.type = "note"


@dataclass
class InboxItem(Entity):
    text: str = ""
    time: str = ""  # HH:MM, local capture time
    date: str = ""  # YYYY-MM-DD, from the inbox filename

    def __post_init__(self):
        self.type = "inbox_item"
