"""Indexing of GTD's multi-type layout: each state has its own directory
(current/ waiting/ maybe/ projects/), and the core indexer learns each type via
the registry `format`."""

import json
from pathlib import Path

import pytest
from hubidubios_plugin_host.registry import discover, export_entity_types
from hubidubios_vault.index import indexer, queries

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def gtd_vault(vault):
    """A vault with the gtd + calendar plugins registered."""
    reg = discover([REPO_PLUGINS])
    # Only register gtd + calendar (gtd supersedes tasks + inbox).
    reg.plugins = {k: v for k, v in reg.plugins.items() if k in ("gtd", "calendar")}
    export_entity_types(vault, reg)
    return vault


def _seed(v):
    (v.current_dir / "website.md").write_text(
        "---\nproject: website\n---\n\n"
        "- [ ] draft the landing copy  <!-- id:t_na000001 ctx:@computer time:30 energy:high -->\n")
    (v.waiting_dir / "waiting.md").write_text(
        "- [ ] invoice from contractor  <!-- id:t_wf000001 who:sam due:2026-08-01 -->\n")
    (v.maybe_dir / "maybe.md").write_text(
        "- [ ] learn the cello  <!-- id:t_sd000001 updated:2020-01-01T00:00:00Z -->\n")
    (v.projects_dir / "website.md").write_text(
        "---\nid: p_web00001\ntitle: website\nstatus: active\n"
        "updated: 2026-08-31T10:00:00Z\n---\n\nOutcome: ship the site.\n")
    (v.projects_dir / "taxes.md").write_text(
        "---\nid: p_tax00001\ntitle: taxes\nstatus: active\n"
        "updated: 2026-08-31T10:00:00Z\n---\n\nOutcome: file 2025 taxes.\n")
    (v.inbox_dir / "2026-08-31.md").write_text(
        "- 09:00 think about Q4 planning  <!-- id:i_inb00001 -->\n")


def test_multi_type_directory_indexing(gtd_vault):
    _seed(gtd_vault)
    conn = indexer.open_index(gtd_vault)
    counts = queries.counts(conn)
    # current/ -> next_action, waiting/ -> waiting_for (separate directories).
    assert counts["next_action"] == 1
    assert counts["waiting_for"] == 1
    assert counts["someday"] == 1
    assert counts["project"] == 2
    assert counts["inbox_item"] == 1

    na = json.loads(conn.execute(
        "SELECT data FROM entities WHERE id='t_na000001'").fetchone()["data"])
    assert na["type"] == "next_action"
    assert na["context"] == "@computer"
    assert na["time"] == "30"
    wf = json.loads(conn.execute(
        "SELECT data FROM entities WHERE id='t_wf000001'").fetchone()["data"])
    assert wf["type"] == "waiting_for" and wf["who"] == "sam"
    conn.close()


def test_waiting_not_misclassified_as_next_action(gtd_vault):
    _seed(gtd_vault)
    conn = indexer.open_index(gtd_vault)
    row = conn.execute("SELECT type FROM entities WHERE id='t_wf000001'").fetchone()
    assert row["type"] == "waiting_for"  # lives in waiting/, distinct from current/
    conn.close()


def test_project_is_searchable(gtd_vault):
    _seed(gtd_vault)
    conn = indexer.open_index(gtd_vault)
    hits = queries.search(conn, "taxes")
    assert any(h["type"] == "project" for h in hits)
    conn.close()


def test_rebuild_identical_with_gtd_types(gtd_vault):
    _seed(gtd_vault)
    indexer.rebuild(gtd_vault)

    def snap():
        conn = indexer.open_index(gtd_vault, do_sweep=False)
        s = (queries.counts(conn),
             [tuple(r) for r in conn.execute(
                 "SELECT id, type, updated FROM entities ORDER BY id")])
        conn.close()
        return s

    before = snap()
    gtd_vault.index_path.unlink()
    indexer.rebuild(gtd_vault)
    assert snap() == before
