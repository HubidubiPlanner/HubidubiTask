"""`hubidubios next add|list|done` — direct next_action management under the GTD
plugin set (fills the gap where `task add` has no registered type)."""

import json
from pathlib import Path

import pytest
from hubidubios_vault import cli

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def run(vault, monkeypatch, capsys):
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    # Install the GTD set so next_action is the registered type (task is not).
    from hubidubios_plugin_host.registry import discover, export_entity_types
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    export_entity_types(vault, reg)
    vault._registry = None

    def _run(*argv):
        code = cli.main(list(argv))
        return code, capsys.readouterr().out

    return _run


def test_next_add_creates_next_action(run, vault):
    code, out = run("next", "add", "call the vet", "--project", "clinic",
                    "--ctx", "@calls", "--due", "2026-09-20", "--time", "5",
                    "--energy", "low")
    assert code == 0
    aid = out.strip()
    assert aid.startswith("t_")
    content = (vault.tasks_dir / "clinic.md").read_text()
    assert "call the vet" in content
    assert "ctx:@calls" in content and "time:5" in content and "energy:low" in content

    # indexed as next_action, not task
    from hubidubios_vault.index import indexer
    conn = indexer.open_index(vault)
    row = conn.execute("SELECT type FROM entities WHERE id=?", (aid,)).fetchone()
    assert row["type"] == "next_action"
    conn.close()


def test_next_list_and_done(run, vault):
    run("next", "add", "first", "--project", "p", "--ctx", "@computer")
    run("next", "add", "second", "--project", "p")
    _, out = run("next", "list")
    assert "first" in out and "second" in out

    _, js = run("next", "list", "--json")
    actions = json.loads(js)
    assert {a["text"] for a in actions} == {"first", "second"}
    assert all(a["type"] == "next_action" for a in actions)

    aid = actions[0]["id"]
    code, out = run("next", "done", aid)
    assert code == 0 and "done" in out
    _, out = run("next", "list")            # done ones hidden by default
    assert aid not in out
    _, out = run("next", "list", "--all")   # shown with --all
    assert aid in out


def test_next_add_refused_without_gtd(vault, monkeypatch, capsys):
    # No plugins registered -> next_action is not a known type.
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    vault.entity_types_path.write_text('{"types": {"note": {"path":"notes/","suffix":".md"}}}')
    vault._registry = None
    assert cli.main(["next", "add", "nope"]) == 1
    assert "gtd" in capsys.readouterr().err


def test_task_add_still_works_under_phase3_set(vault, monkeypatch, capsys):
    """Sanity: `task add` remains for the Phase-3 tasks plugin."""
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    from hubidubios_plugin_host.registry import discover, export_entity_types
    reg = discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"})
    export_entity_types(vault, reg)
    vault._registry = None
    assert cli.main(["task", "add", "a task", "--project", "p"]) == 0
