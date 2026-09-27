"""One-time Todoist import: projects become projects, open tasks become next
actions filed under them, and a second run changes nothing."""

from hubidubios_vault import apply, todoist
from hubidubios_vault.formats import generic

PROJECTS = [
    {"id": "100", "name": "Inbox", "inbox_project": True},
    {"id": "200", "name": "Kitchen renovation"},
    {"id": "300", "name": "Old stuff", "is_archived": True},
]
TASKS = [
    {"id": "1", "content": "Buy printer toner", "project_id": "100"},
    {"id": "2", "content": "Order worktop\nsamples", "project_id": "200",
     "due": {"date": "2026-09-30", "is_recurring": False}},
    {"id": "3", "content": "Water plants", "project_id": "200",
     "due": {"date": "2026-10-01T09:00:00", "is_recurring": True},
     "description": "the big one too", "labels": ["home"], "parent_id": "2"},
    {"id": "4", "content": "Done already", "project_id": "200", "checked": True},
]


def _fetch(pages):
    """A fake API: each path answers from `pages`, split across two cursor pages
    so pagination is exercised."""
    def fetch(path, token, params):
        assert token == "tok"
        rows = pages[path]
        if "cursor" not in params:
            return {"results": rows[:1], "next_cursor": "c1"}
        return {"results": rows[1:], "next_cursor": None}
    return fetch


def _actions(vault):
    out = {}
    for f in vault.current_dir.glob("*.md"):
        for d, _ in generic.parse_checkbox(f.read_text(), f, "x", "next_action"):
            out[d["text"]] = (f.stem, d)
    return out


def test_imports_projects_and_open_tasks(vault):
    res = todoist.import_all(vault, "tok", _fetch({"/projects": PROJECTS, "/tasks": TASKS}))

    assert res["projects"] == 1 and res["tasks"] == 3
    assert [p.stem for p in vault.projects_dir.glob("*.md")] == ["kitchen-renovation"]
    pid = todoist._existing_projects(vault)["kitchen-renovation"]

    acts = _actions(vault)
    assert "Done already" not in acts, "a completed task was imported"
    # Inbox tasks are loose: no project, no parent.
    assert acts["Buy printer toner"][0] == "inbox"
    assert "parent" not in acts["Buy printer toner"][1]
    # Project tasks sit in the project's file and point at it.
    home, d = acts["Order worktop samples"]
    assert home == "kitchen-renovation" and d["parent"] == pid
    assert d["due"] == "2026-09-30"
    # A datetime due keeps just the day.
    assert acts["Water plants"][1]["due"] == "2026-10-01"
    assert res["recurring"] == 1 and res["with_description"] == 1
    assert res["with_labels"] == 1 and res["subtasks"] == 1


def test_second_run_changes_nothing(vault):
    fetch = _fetch({"/projects": PROJECTS, "/tasks": TASKS})
    todoist.import_all(vault, "tok", fetch)
    before = {f: f.read_text() for f in vault.root.rglob("*.md")}

    res = todoist.import_all(vault, "tok", fetch)

    assert res["projects"] == 0 and res["tasks"] == 0 and res["skipped"] == 3
    assert {f: f.read_text() for f in vault.root.rglob("*.md")} == before


def test_same_named_project_joins_the_existing_one(vault):
    """Writing it anew would overwrite the user's project file."""
    apply.upsert_project(vault, {"id": "p_mine", "title": "Kitchen renovation",
                                 "updated": "2026-09-01T00:00:00Z",
                                 "body": "my own notes\n"})

    res = todoist.import_all(vault, "tok", _fetch({"/projects": PROJECTS, "/tasks": TASKS}))

    assert res["projects"] == 0 and res["merged"] == 1
    text = (vault.projects_dir / "kitchen-renovation.md").read_text()
    assert "p_mine" in text and "my own notes" in text
    assert _actions(vault)["Order worktop samples"][1]["parent"] == "p_mine"


def test_imported_items_reach_the_index(vault):
    """The index is what sync serves the phone: imports must land in it as a
    project and next actions pointing at it, with their dates."""
    import json
    import sqlite3
    from pathlib import Path

    from hubidubios_plugin_host.registry import discover, export_entity_types
    from hubidubios_vault.index import indexer

    reg = discover([Path(__file__).parents[2] / "plugins"])
    reg.plugins = {k: v for k, v in reg.plugins.items() if k in ("gtd", "calendar")}
    export_entity_types(vault, reg)

    todoist.import_all(vault, "tok", _fetch({"/projects": PROJECTS, "/tasks": TASKS}))
    indexer.rebuild(vault)

    rows = [(t, json.loads(d)) for t, d in
            sqlite3.connect(vault.index_path).execute("select type, data from entities")]
    projects = [d for t, d in rows if t == "project"]
    actions = {d["text"]: d for t, d in rows if t == "next_action"}
    assert [p["title"] for p in projects] == ["Kitchen renovation"]
    assert actions["Order worktop samples"]["parent"] == projects[0]["id"]
    assert actions["Order worktop samples"]["due"] == "2026-09-30"
    assert set(actions) == {"Buy printer toner", "Order worktop samples", "Water plants"}
