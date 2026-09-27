"""One-way, read-only Google Calendar (or any iCal URL) pull.

The user's ONLY sanctioned outbound in an otherwise no-cloud system: fetch a
private iCal URL and drop its events into the vault. Never writes back. A Google
export bundles the whole calendar in one .ics, so we split it into one file per
VEVENT (calendars/google/<uid>.ics) — matching the vault's one-event-per-file
model so the existing indexer, Radicale, and sync carry them unchanged — and
prune events that have disappeared from the source.

Configured in .hubidubios/config.toml:

    [calendar]
    google_ics_urls = ["https://calendar.google.com/calendar/ical/…/basic.ics"]

With no URLs configured, this is a pure no-op and touches the network zero times.
"""

from __future__ import annotations

import re
import urllib.request
from pathlib import Path

GOOGLE_SUBDIR = "google"


def _safe(uid: str) -> str:
    return re.sub(r"[^A-Za-z0-9@._-]", "_", uid)[:120] or "event"


def _default_fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "hubidubios/1"})
    with urllib.request.urlopen(req, timeout=30) as r:  # noqa: S310 (user's own URL)
        return r.read()


def pull(vault, fetch=_default_fetch) -> dict:
    """Fetch every configured iCal URL, split into per-event files under
    calendars/google/, prune stale ones. Returns {fetched, written, pruned}."""
    urls = (vault.config().get("calendar") or {}).get("google_ics_urls") or []
    if not urls:
        return {"fetched": 0, "written": 0, "pruned": 0}
    res = write_calendars(vault, [fetch(url) for url in urls])
    return {"fetched": len(urls), **res}


def write_calendars(vault, raws: list[bytes]) -> dict:
    """Split already-fetched iCal feeds into per-event files under
    calendars/google/ and prune events no longer in any of them. Kept apart
    from the fetching so a feed pulled elsewhere (the import skill fetches on
    another machine) lands exactly as a pull here would. Returns {written, pruned}."""
    from icalendar import Calendar

    dest = vault.calendars_dir / GOOGLE_SUBDIR
    dest.mkdir(parents=True, exist_ok=True)
    keep: set[Path] = set()
    written = 0
    for raw in raws:
        cal = Calendar.from_ical(raw)
        for comp in cal.walk("VEVENT"):
            uid = str(comp.get("UID") or "")
            if not uid:
                continue
            out = Calendar()
            out.add("VERSION", "2.0")
            out.add("PRODID", "-//hubidubios//gcal-pull//EN")
            out.add_component(comp)
            path = dest / f"{_safe(uid)}.ics"
            path.write_bytes(out.to_ical())
            keep.add(path)
            written += 1

    pruned = 0
    for existing in dest.glob("*.ics"):
        if existing not in keep:
            existing.unlink()
            pruned += 1
    return {"written": written, "pruned": pruned}
