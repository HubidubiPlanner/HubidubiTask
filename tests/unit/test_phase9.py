"""Phase 9 — the user's GTD vocabulary (current/waiting/maybe/archive),
project references, phone write-back for GTD types, and layout migration."""

import json
from pathlib import Path

import pytest
from hubidubios_plugin_host.registry import discover, export_entity_types
from hubidubios_vault import apply as vault_apply
from hubidubios_vault import cli
from hubidubios_vault.index import indexer, queries

REPO_PLUGINS = Path(__file__).parents[2] / "src" / "plugins"


@pytest.fixture
def gtd_run(vault, monkeypatch, capsys):
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    export_entity_types(vault, reg)
    vault._registry = None

    def _run(*argv):
        code = cli.main(list(argv))
        return code, capsys.readouterr().out
    return _run


# ---------------------------------------------------------------- archive

def test_archive_moves_item_out_of_current(gtd_run, vault):
    code, out = gtd_run("next", "add", "ship it", "--project", "web")
    aid = out.strip()
    code, _ = gtd_run("archive", aid)
    assert code == 0
    assert not list(vault.current_dir.glob("*.md")) or \
        "- [" not in (vault.current_dir / "web.md").read_text()
    archived = list(vault.archive_dir.glob("*.md"))
    assert archived and aid in archived[0].read_text()
    # indexed under the archived type, gone from next_action
    conn = indexer.open_index(vault)
    row = conn.execute("SELECT type FROM entities WHERE id=?", (aid,)).fetchone()
    conn.close()
    assert row["type"] == "archived"


# ---------------------------------------------------------------- project show

def test_project_show_lists_current_items(gtd_run, vault):
    gtd_run("next", "add", "first", "--project", "site")
    gtd_run("next", "add", "second", "--project", "site")
    (vault.projects_dir / "site.md").write_text(
        "---\nid: p_site\ntitle: My Site\nstatus: active\nitems: []\n"
        "updated: 2026-09-11T00:00:00Z\n---\n\nOutcome: launch\n")
    gtd_run("index", "rebuild")
    _, js = gtd_run("project", "show", "site", "--json")
    data = json.loads(js)
    assert data["project"]["title"] == "My Site"
    assert {a["text"] for a in data["current"]} == {"first", "second"}


# ---------------------------------------------------------------- write-back

def test_writeback_gtd_types_land_in_state_dirs(vault):
    """The sync/view path can now create the GTD types (was rejected before)."""
    vault.ensure_layout()
    vault_apply.upsert(vault, "next_action",
                       {"id": "t_n1", "updated": "2026-09-11T00:00:00Z",
                        "text": "call", "done": False, "project": "clinic"})
    vault_apply.upsert(vault, "waiting_for",
                       {"id": "t_w1", "updated": "2026-09-11T00:00:00Z",
                        "text": "refund", "done": False, "who": "irs"})
    vault_apply.upsert(vault, "someday",
                       {"id": "t_s1", "updated": "2026-09-11T00:00:00Z",
                        "text": "learn cello", "done": False})
    vault_apply.upsert(vault, "project",
                       {"id": "p_p1", "updated": "2026-09-11T00:00:00Z",
                        "title": "House", "status": "active", "items": ["t_n1"]})
    assert "call" in (vault.current_dir / "clinic.md").read_text()
    assert "who:irs" in (vault.waiting_dir / "irs.md").read_text()
    assert "learn cello" in (vault.maybe_dir / "maybe.md").read_text()
    assert "House" in (vault.projects_dir / "house.md").read_text()
    # all are indexable under their declared types
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    export_entity_types(vault, reg)
    vault._registry = None
    counts = queries.counts(indexer.open_index(vault))
    assert counts.get("next_action") == 1 and counts.get("waiting_for") == 1
    assert counts.get("someday") == 1 and counts.get("project") == 1


# ---------------------------------------------------------------- migration

def test_migrate_layout_moves_old_dirs(gtd_run, vault):
    (vault.tasks_dir).mkdir(exist_ok=True)
    (vault.tasks_dir / "old.md").write_text(
        "- [ ] legacy action  <!-- id:t_old1 -->\n")
    (vault.tasks_dir / "waiting.md").write_text(
        "- [ ] legacy wait  <!-- id:t_old2 who:bob -->\n")
    (vault.root / "someday").mkdir(exist_ok=True)
    (vault.root / "someday" / "someday.md").write_text(
        "- [ ] legacy maybe  <!-- id:t_old3 -->\n")
    code, out = gtd_run("migrate-layout")
    assert code == 0
    assert "legacy action" in (vault.current_dir / "old.md").read_text()
    assert "legacy wait" in (vault.waiting_dir / "waiting.md").read_text()
    assert "legacy maybe" in (vault.maybe_dir / "someday.md").read_text()
    assert not list(vault.tasks_dir.glob("*.md"))
