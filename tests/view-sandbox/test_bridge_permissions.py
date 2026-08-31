"""View sandbox suite (PLAN.md Phase 3): a view requesting an entity type it
did not declare, a view attempting to write without write permission, and
the origin-isolation/CSP invariants of the host page.

Rendering-level enforcement (iframe opaque origins, CSP blocking fetch) is
the browser engine's job; what we own — and test here — is the bridge that
validates every request against declared permissions, and that the HTML/CSP
we emit actually requests that enforcement.
"""

import logging
from pathlib import Path

from prodos_vault.formats import tasks_md
from prodos_vault.index import indexer

from prodos_plugin_host import views
from prodos_plugin_host.bridge import Bridge
from prodos_plugin_host.registry import discover

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"

TASK_VIEW = {"id": "task-list", "title": "Tasks",
             "permissions": {"read": ["task"], "write": ["task"]}}
READONLY_VIEW = {"id": "spy", "title": "Spy",
                 "permissions": {"read": ["task"], "write": []}}


def _bridge(vault, view):
    conn = indexer.open_index(vault)
    return Bridge(vault, conn, "tasks", view)


def test_undeclared_entity_type_rejected_and_logged(vault, caplog):
    tasks_md.append_task(vault.tasks_dir / "p.md", "secret task")
    (vault.notes_dir / "2026").mkdir(parents=True)
    bridge = _bridge(vault, TASK_VIEW)
    with caplog.at_level(logging.WARNING, logger="prodos.bridge"):
        res = bridge.handle({"op": "list", "type": "note"})
    assert res["error"] == "permission_denied"
    assert "denied" in caplog.text and "note" in caplog.text
    # rejected, not silently empty
    assert "entities" not in res


def test_get_by_id_checks_actual_type(vault):
    """A view must not read an undeclared entity by guessing its id."""
    note_dir = vault.notes_dir / "2026" / "08"
    note_dir.mkdir(parents=True)
    (note_dir / "n.md").write_text(
        "---\nid: n_secret01\ntitle: Secret\nupdated: 2026-08-31T10:00:00Z\n---\n\nssh\n")
    bridge = _bridge(vault, TASK_VIEW)
    res = bridge.handle({"op": "get", "id": "n_secret01"})
    assert res["error"] == "permission_denied"


def test_write_without_write_permission_rejected(vault):
    bridge = _bridge(vault, READONLY_VIEW)
    res = bridge.handle({"op": "upsert", "type": "task",
                         "data": {"text": "sneaky", "project": "p", "done": False}})
    assert res["error"] == "permission_denied"
    assert not (vault.tasks_dir / "p.md").exists()


def test_search_filters_to_declared_types(vault):
    tasks_md.append_task(vault.tasks_dir / "p.md", "findable task")
    note_dir = vault.notes_dir / "2026" / "08"
    note_dir.mkdir(parents=True)
    (note_dir / "n.md").write_text(
        "---\nid: n_findme01\ntitle: findable note\n"
        "updated: 2026-08-31T10:00:00Z\n---\n\nfindable\n")
    bridge = _bridge(vault, TASK_VIEW)
    res = bridge.handle({"op": "search", "q": "findable"})
    assert res["entities"] and all(e["type"] == "task" for e in res["entities"])


def test_declared_write_roundtrip(vault):
    bridge = _bridge(vault, TASK_VIEW)
    res = bridge.handle({"op": "upsert", "type": "task",
                         "data": {"text": "from the view", "project": "p",
                                  "done": False}})
    assert res["entity"]["id"].startswith("t_")
    listed = bridge.handle({"op": "list", "type": "task"})
    assert listed["entities"][0]["text"] == "from the view"

    deleted = bridge.handle({"op": "delete", "type": "task",
                             "id": res["entity"]["id"]})
    assert deleted == {"deleted": True}
    assert bridge.handle({"op": "list", "type": "task"})["entities"] == []


def test_iframe_is_origin_isolated():
    html = views.iframe_html("tasks", TASK_VIEW)
    assert 'sandbox="allow-scripts"' in html
    assert "allow-same-origin" not in html  # opaque origin = distinct origin


def test_csp_forbids_remote_code_and_fetch():
    for csp in (views.VIEW_CSP, views.HOST_CSP):
        assert "default-src 'none'" in csp
        assert "script-src 'self'" in csp
        assert "style-src 'self'" in csp
        assert "connect-src 'none'" in csp  # no fetch, no websockets
        assert "http" not in csp  # no remote origin whitelisted, ever


def test_host_page_embeds_shipped_views():
    reg = discover([REPO_PLUGINS])
    page = views.host_page(reg)
    assert 'data-plugin="tasks"' in page and 'data-view="task-list"' in page
    assert views.HOST_CSP in page


def test_view_bundle_has_no_remote_loads():
    """No plugin ever loads code over the network at runtime — the shipped
    view must reference only bundle-relative assets."""
    for view_dir in REPO_PLUGINS.glob("*/views"):
        for f in view_dir.rglob("*"):
            if f.is_file():
                text = f.read_text(encoding="utf-8")
                assert "https://" not in text and "http://" not in text, f
                assert "fetch(" not in text, f
