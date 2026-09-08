"""Calendar events: iCalendar files under calendars/<name>/*.ics.

The identity of an event is its iCal UID (that is what CalDAV clients
preserve); the entity id is derived deterministically from it so reindexing
is stable and third-party events get ids in our namespace. The raw UID is
kept in the entity for round-tripping.
"""

import hashlib
from datetime import date, datetime, timezone
from pathlib import Path

from ..entities import Event


def _entity_id(uid: str) -> str:
    return "e_" + hashlib.sha256(uid.encode("utf-8")).hexdigest()[:16]


def _fmt_dt(value) -> tuple[str, bool]:
    """Render an ical DTSTART/DTEND value as (RFC 3339 or date string, all_day)."""
    if isinstance(value, datetime):
        if value.tzinfo:
            value = value.astimezone(timezone.utc)
            return value.strftime("%Y-%m-%dT%H:%M:%SZ"), False
        return value.strftime("%Y-%m-%dT%H:%M:%S"), False
    if isinstance(value, date):
        return value.isoformat(), True
    return str(value), False


def parse_file(text: str, path: Path, file_mtime_rfc3339: str) -> list[Event]:
    from icalendar import Calendar

    calendar_name = path.parent.name
    try:
        cal = Calendar.from_ical(text)
    except ValueError:
        return []

    events: list[Event] = []
    for comp in cal.walk("VEVENT"):
        uid = str(comp.get("UID") or "")
        if not uid:
            continue
        dtstart = comp.get("DTSTART")
        if dtstart is None:
            continue
        start_str, all_day = _fmt_dt(dtstart.dt)
        dtend = comp.get("DTEND")
        end_str = _fmt_dt(dtend.dt)[0] if dtend is not None else None

        stamp = comp.get("LAST-MODIFIED") or comp.get("DTSTAMP")
        updated = _fmt_dt(stamp.dt)[0] if stamp is not None else file_mtime_rfc3339

        ev = Event(
            id=_entity_id(uid),
            updated=updated,
            summary=str(comp.get("SUMMARY") or ""),
            dtstart=start_str,
            dtend=end_str,
            all_day=all_day,
            calendar=calendar_name,
            location=str(comp["LOCATION"]) if comp.get("LOCATION") else None,
            description=str(comp["DESCRIPTION"]) if comp.get("DESCRIPTION") else None,
            uid=uid,
        )
        events.append(ev)
    return events


def render_event(summary: str, dtstart: datetime, dtend: datetime, uid: str) -> str:
    """Minimal single-event VCALENDAR, for tests and future `event add`."""
    from icalendar import Calendar
    from icalendar import Event as IcsEvent

    cal = Calendar()
    cal.add("VERSION", "2.0")
    cal.add("PRODID", "-//hubidubios//vault//EN")
    ev = IcsEvent()
    ev.add("UID", uid)
    ev.add("SUMMARY", summary)
    ev.add("DTSTART", dtstart)
    ev.add("DTEND", dtend)
    ev.add("DTSTAMP", datetime.now(timezone.utc))
    cal.add_component(ev)
    return cal.to_ical().decode("utf-8")
