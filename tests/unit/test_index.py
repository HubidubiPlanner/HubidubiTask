import os
import time
from datetime import datetime, timezone

from prodos_vault.formats import ics, tasks_md
from prodos_vault.index import indexer, queries


def _seed(vault):
    tasks_md.append_task(vault.tasks_dir / "prodos.md", "build the indexer",
                         due="2026-09-02", ctx="@computer")
    tasks_md.append_task(vault.tasks_dir / "home.md", "fix the door")
    (vault.inbox_dir / "2026-08-31.md").write_text(
        "- 09:14 Ask Sam about the report  <!-- id:i_seed0001 -->\n")
    note_dir = vault.notes_dir / "2026" / "08"
    note_dir.mkdir(parents=True)
    (note_dir / "sandbox.md").write_text(
        "---\nid: n_seed0001\ntitle: Sandbox design\nupdated: 2026-08-31T10:00:00Z\n"
        "---\n\nbwrap notes, see [[plugin-api]].\n")
    cal = vault.calendars_dir / "work"
    cal.mkdir(parents=True)
    (cal / "review.ics").write_text(ics.render_event(
        "Design review",
        datetime(2026, 9, 3, 14, 0, tzinfo=timezone.utc),
        datetime(2026, 9, 3, 15, 0, tzinfo=timezone.utc),
        "rev-1@test"))


def test_index_and_queries(vault):
    _seed(vault)
    conn = indexer.open_index(vault)

    assert queries.counts(conn) == {
        "task": 2, "note": 1, "event": 1, "inbox_item": 1}

    tasks = queries.task_list(conn)
    assert [t["project"] for t in tasks] == ["prodos", "home"]  # due first
    assert queries.task_list(conn, context="@computer")[0]["text"] == "build the indexer"

    hits = queries.search(conn, "sandbox")
    assert any(h["type"] == "note" for h in hits)

    ag = queries.agenda(conn, "2026-09-01", "2026-09-07")
    assert [e["summary"] for e in ag["events"]] == ["Design review"]
    assert [t["text"] for t in ag["tasks"]] == ["build the indexer"]

    items = queries.inbox_items(conn)
    assert items[0]["id"] == "i_seed0001"

    # wikilink extracted
    links = conn.execute("SELECT dst FROM links WHERE src_id='n_seed0001'").fetchall()
    assert [row["dst"] for row in links] == ["plugin-api"]
    conn.close()


def test_sweep_picks_up_external_edit(vault):
    _seed(vault)
    conn = indexer.open_index(vault)
    assert queries.counts(conn)["task"] == 2

    # simulate a vim edit: append a task line directly
    path = vault.tasks_dir / "home.md"
    with open(path, "a") as f:
        f.write("- [ ] water the plants  <!-- id:t_vimedit1 -->\n")
    os.utime(path, ns=(time.time_ns(), time.time_ns()))

    changed = indexer.sweep(conn, vault)
    assert changed == 1
    assert queries.find_task(conn, "t_vimedit1") is not None
    conn.close()


def test_sweep_removes_deleted_file(vault):
    _seed(vault)
    conn = indexer.open_index(vault)
    (vault.tasks_dir / "home.md").unlink()
    indexer.sweep(conn, vault)
    assert queries.counts(conn)["task"] == 1
    conn.close()


def test_rebuild_identical_results(vault):
    _seed(vault)
    conn = indexer.open_index(vault)

    def snapshot(c):
        return (
            queries.counts(c),
            queries.task_list(c, include_done=True),
            queries.inbox_items(c),
            queries.agenda(c, "2026-01-01", "2026-12-31"),
            [tuple(r) for r in c.execute(
                "SELECT id, type, updated, file FROM entities ORDER BY id")],
        )

    before = snapshot(conn)
    conn.close()

    vault.index_path.unlink()
    indexer.rebuild(vault)
    conn = indexer.open_index(vault, do_sweep=False)
    after = snapshot(conn)
    conn.close()
    assert before == after


def test_index_survives_malformed_files(vault):
    _seed(vault)
    (vault.tasks_dir / "broken.md").write_text("---\n: not yaml [\n---\n- [ ] x\n")
    (vault.calendars_dir / "work" / "junk.ics").write_text("BEGIN:GARBAGE")
    conn = indexer.open_index(vault)
    assert queries.counts(conn)["task"] == 2  # broken file contributes nothing, no crash
    conn.close()
