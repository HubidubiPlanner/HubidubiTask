import re

import pytest
from prodos_vault import cli


@pytest.fixture
def run(vault, monkeypatch, capsys):
    monkeypatch.setenv("PRODOS_VAULT", str(vault.root))

    def _run(*argv):
        code = cli.main(list(argv))
        return code, capsys.readouterr().out

    return _run


def test_capture_appends_no_index(run, vault):
    code, _ = run("capture", "buy", "milk")
    assert code == 0
    files = list(vault.inbox_dir.glob("*.md"))
    assert len(files) == 1
    line = files[0].read_text().strip()
    assert re.match(r"^- \d{2}:\d{2} buy milk  <!-- id:i_[a-z2-7]{8} -->$", line)
    assert not vault.index_path.exists()  # capture never touches the index

    run("capture", "second thing")
    assert len(files[0].read_text().splitlines()) == 2  # append-only


def test_task_add_list_done(run):
    code, out = run("task", "add", "write docs", "--project", "prodos",
                    "--due", "2026-09-10", "--ctx", "@computer")
    assert code == 0
    task_id = out.strip()
    assert task_id.startswith("t_")

    _, out = run("task", "list")
    assert "write docs" in out and task_id in out and "due:2026-09-10" in out

    code, out = run("task", "done", task_id)
    assert code == 0

    _, out = run("task", "list")
    assert "write docs" not in out
    _, out = run("task", "list", "--all")
    assert "[x] write docs" in out


def test_task_done_unknown_id(run):
    code, _ = run("task", "done", "t_missing1")
    assert code == 1


def test_note_new_and_search(run, vault):
    code, out = run("note", "new", "Sandbox Design", "--tags", "dev")
    assert code == 0
    path = out.strip()
    assert path.endswith("sandbox-design.md")
    _, out = run("search", "sandbox")
    assert "[note]" in out and "Sandbox Design" in out


def test_inbox_lists_captures(run):
    run("capture", "call the dentist")
    _, out = run("inbox")
    assert "call the dentist" in out


def test_index_rebuild(run, vault):
    run("task", "add", "a task")
    run("capture", "an item")
    code, out = run("index", "rebuild")
    assert code == 0
    assert "indexed 2 entities" in out


def test_agenda_shows_due_task(run):
    from datetime import date, timedelta

    due = (date.today() + timedelta(days=3)).isoformat()  # future-relative, not fragile
    run("task", "add", "due soon", "--due", due)
    _, out = run("agenda", "--days", "365")
    assert "due soon" in out
