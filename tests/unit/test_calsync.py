"""One-way Google/iCal pull: split into per-event files, prune stale, no network
when unconfigured."""

from hubidubios_vault import calsync

ICS_TWO = b"""BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Google//EN
BEGIN:VEVENT
UID:a@google.com
SUMMARY:Standup
DTSTART:20260915T090000Z
DTEND:20260915T091500Z
END:VEVENT
BEGIN:VEVENT
UID:b@google.com
SUMMARY:Review
DTSTART:20260916T140000Z
DTEND:20260916T150000Z
END:VEVENT
END:VCALENDAR
"""

ICS_ONE = b"""BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Google//EN
BEGIN:VEVENT
UID:a@google.com
SUMMARY:Standup moved
DTSTART:20260915T100000Z
DTEND:20260915T101500Z
END:VEVENT
END:VCALENDAR
"""


def _configure(vault, urls):
    vault.hubidubios_dir.mkdir(parents=True, exist_ok=True)
    vault.config_path.write_text(
        "[calendar]\ngoogle_ics_urls = [" + ", ".join(f'"{u}"' for u in urls) + "]\n")


def test_pull_splits_events_into_files(vault):
    vault.ensure_layout()
    _configure(vault, ["https://cal.example/basic.ics"])
    res = calsync.pull(vault, fetch=lambda url: ICS_TWO)
    assert res == {"fetched": 1, "written": 2, "pruned": 0}
    files = sorted(p.name for p in (vault.calendars_dir / "google").glob("*.ics"))
    assert files == ["a@google.com.ics", "b@google.com.ics"]


def test_pull_prunes_disappeared_events(vault):
    vault.ensure_layout()
    _configure(vault, ["https://cal.example/basic.ics"])
    calsync.pull(vault, fetch=lambda url: ICS_TWO)
    res = calsync.pull(vault, fetch=lambda url: ICS_ONE)   # b is gone
    assert res["written"] == 1 and res["pruned"] == 1
    files = list((vault.calendars_dir / "google").glob("*.ics"))
    assert [p.name for p in files] == ["a@google.com.ics"]
    assert b"Standup moved" in files[0].read_bytes()


def test_pull_no_urls_is_a_noop_and_never_fetches(vault):
    vault.ensure_layout()

    def _boom(url):  # must never be called when unconfigured (no-cloud invariant)
        raise AssertionError("fetched with no URLs configured")

    assert calsync.pull(vault, fetch=_boom) == {"fetched": 0, "written": 0, "pruned": 0}
