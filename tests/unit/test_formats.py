from datetime import datetime, timezone
from pathlib import Path

from prodos_vault.formats import ics, inbox_md, notes_md, tasks_md
from prodos_vault.entities import Note

MTIME = "2026-08-31T12:00:00Z"

TASK_FILE = """---
project: prodos
tags: [dev]
---

- [ ] Design plugin manifest schema  <!-- id:t_a1b2 due:2026-09-05 ctx:@computer -->
- [x] Pick vault layout  <!-- id:t_c3d4 done:2026-08-30 -->
- [ ] no id, hand-written, skipped
"""


def test_task_parse():
    tasks = tasks_md.parse_file(TASK_FILE, Path("tasks/prodos.md"), MTIME)
    assert len(tasks) == 2
    t1, line1 = tasks[0]
    assert t1.id == "t_a1b2"
    assert t1.project == "prodos"
    assert t1.due == "2026-09-05"
    assert t1.context == "@computer"
    assert not t1.done
    assert t1.updated == MTIME  # no inline updated -> file mtime fallback
    assert line1 == 6
    t2, _ = tasks[1]
    assert t2.done and t2.done_date == "2026-08-30"


def test_task_append_and_done(tmp_path):
    path = tmp_path / "proj.md"
    task_id = tasks_md.append_task(path, "write tests", due="2026-09-01")
    content = path.read_text()
    assert content.startswith("---\nproject: proj\n---\n")
    assert f"id:{task_id}" in content
    tasks = tasks_md.parse_file(content, path, MTIME)
    assert len(tasks) == 1
    task, _ = tasks[0]
    assert task.text == "write tests"
    assert task.updated != MTIME  # CLI writes stamp updated inline

    assert tasks_md.mark_done(path, task_id, "2026-08-31")
    task, _ = tasks_md.parse_file(path.read_text(), path, MTIME)[0]
    assert task.done and task.done_date == "2026-08-31"
    assert task.due == "2026-09-01"  # due survives completion


def test_task_done_preserves_other_lines(tmp_path):
    path = tmp_path / "proj.md"
    id1 = tasks_md.append_task(path, "first")
    tasks_md.append_task(path, "second")
    before = path.read_text().split("\n")
    tasks_md.mark_done(path, id1, "2026-08-31")
    after = path.read_text().split("\n")
    assert len(before) == len(after)
    # only the first task's line changed
    assert sum(1 for a, b in zip(before, after) if a != b) == 1


def test_inbox_roundtrip():
    line = inbox_md.format_item_line("Ask Sam about the report", "09:14", "i_x9y8abcd")
    items = inbox_md.parse_file(line + "\n", Path("inbox/2026-08-31.md"), MTIME)
    assert len(items) == 1
    item, lineno = items[0]
    assert item.id == "i_x9y8abcd"
    assert item.text == "Ask Sam about the report"
    assert item.time == "09:14"
    assert item.date == "2026-08-31"
    assert item.updated == "2026-08-31T09:14:00Z"
    assert lineno == 1


def test_note_roundtrip():
    note = Note(id="n_abcd2345", updated=MTIME, title="Sandbox design",
                body="Uses bwrap. See [[plugin-api]] and [[Vault Layout]].",
                tags=["dev"], created=MTIME)
    rendered = notes_md.render(note)
    parsed = notes_md.parse_file(rendered, Path("notes/2026/08/sandbox-design.md"), "x")
    assert parsed is not None
    assert parsed.id == note.id
    assert parsed.title == note.title
    assert parsed.body == note.body
    assert parsed.tags == ["dev"]
    assert notes_md.render(parsed) == rendered  # byte-identical round-trip
    assert notes_md.wikilinks(parsed.body) == ["plugin-api", "Vault Layout"]


def test_note_without_id_skipped():
    assert notes_md.parse_file("# just markdown\n", Path("n.md"), MTIME) is None


def test_slugify():
    assert notes_md.slugify("Sandbox Design: v2!") == "sandbox-design-v2"
    assert notes_md.slugify("???") == "untitled"


def test_ics_roundtrip():
    start = datetime(2026, 9, 3, 14, 0, tzinfo=timezone.utc)
    end = datetime(2026, 9, 3, 15, 0, tzinfo=timezone.utc)
    text = ics.render_event("Design review", start, end, "abc-123@prodos")
    events = ics.parse_file(text, Path("calendars/work/design.ics"), MTIME)
    assert len(events) == 1
    ev = events[0]
    assert ev.summary == "Design review"
    assert ev.dtstart == "2026-09-03T14:00:00Z"
    assert ev.dtend == "2026-09-03T15:00:00Z"
    assert ev.calendar == "work"
    assert ev.uid == "abc-123@prodos"
    assert ev.id.startswith("e_")
    # deterministic id from UID
    assert ics.parse_file(text, Path("calendars/work/design.ics"), MTIME)[0].id == ev.id


def test_ics_all_day():
    text = (
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//x//EN\r\n"
        "BEGIN:VEVENT\r\nUID:allday-1\r\nDTSTART;VALUE=DATE:20260904\r\n"
        "DTEND;VALUE=DATE:20260905\r\nSUMMARY:Conference\r\n"
        "DTSTAMP:20260831T120000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    )
    ev = ics.parse_file(text, Path("calendars/personal/c.ics"), MTIME)[0]
    assert ev.all_day
    assert ev.dtstart == "2026-09-04"
    assert ev.updated == "2026-08-31T12:00:00Z"


def test_ics_garbage_returns_empty():
    assert ics.parse_file("not an ics file", Path("calendars/x/y.ics"), MTIME) == []
