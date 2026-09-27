"""A task's description lives beside its line and comes back with it.

A checkbox line cannot hold prose, so `body` goes to details/<id>.md — the
same arrangement as reviews — and the index puts it back on the entity, which
is what sync serves the phone.
"""

import json
import sqlite3
from pathlib import Path

from hubidubios_plugin_host.registry import discover, export_entity_types
from hubidubios_vault import apply
from hubidubios_vault.index import indexer
from hubidubios_vault.sidecars import BODY


def _gtd(vault):
    reg = discover([Path(__file__).parents[2] / "plugins"])
    reg.plugins = {k: v for k, v in reg.plugins.items() if k in ("gtd", "calendar")}
    export_entity_types(vault, reg)
    return vault


def _indexed(vault):
    indexer.rebuild(vault)
    return {json.loads(d)["id"]: json.loads(d) for (d,) in
            sqlite3.connect(vault.index_path).execute("select data from entities")}


def test_description_round_trips_through_the_index(vault):
    _gtd(vault)
    path = apply.upsert_next_action(vault, {
        "id": "t_desc", "updated": "2026-09-27T10:00:00Z", "text": "Order worktop",
        "project": "kitchen", "body": "Oak, 3 m.\nAsk about the edge."})

    assert "Oak" not in path.read_text(), "the description leaked into the line"
    assert _indexed(vault)["t_desc"]["body"] == "Oak, 3 m.\nAsk about the edge."


def test_absent_body_keeps_it_and_empty_clears_it(vault):
    _gtd(vault)
    base = {"id": "t_b", "updated": "2026-09-27T10:00:00Z", "text": "x", "project": "p"}
    apply.upsert_next_action(vault, {**base, "body": "keep me"})
    rel = str(next(vault.current_dir.glob("*.md")).relative_to(vault.root))

    apply.upsert_next_action(vault, dict(base), existing_rel=rel)      # not sent
    assert BODY.read(vault, "t_b") == "keep me"
    apply.upsert_next_action(vault, {**base, "body": ""}, existing_rel=rel)
    assert BODY.read(vault, "t_b") is None


def test_deleting_the_task_deletes_its_description(vault):
    _gtd(vault)
    path = apply.upsert_next_action(vault, {
        "id": "t_d", "updated": "2026-09-27T10:00:00Z", "text": "x", "project": "p",
        "body": "gone with it"})
    apply.delete(vault, "next_action", "t_d", str(path.relative_to(vault.root)))
    assert BODY.read(vault, "t_d") is None


def test_sub_task_parent_survives_the_index(vault):
    """A sub-task points at its parent task; the index must keep that."""
    _gtd(vault)
    apply.upsert_next_action(vault, {"id": "t_par", "updated": "2026-09-27T10:00:00Z",
                                     "text": "Renovate", "project": "home", "parent": "p_home"})
    apply.upsert_next_action(vault, {"id": "t_kid", "updated": "2026-09-27T10:00:00Z",
                                     "text": "Buy paint", "project": "home", "parent": "t_par"})
    assert _indexed(vault)["t_kid"]["parent"] == "t_par"


def test_a_string_false_is_not_done(vault):
    """The phone sends every field as a string; "false" must not tick the box."""
    base = {"updated": "2026-09-27T10:00:00Z", "text": "x", "project": "p"}
    open_line = apply.render_task_line({**base, "id": "t_1", "done": "false"})
    done_line = apply.render_task_line({**base, "id": "t_2", "done": "true"})
    assert open_line.startswith("- [ ] ") and done_line.startswith("- [x] ")


def test_two_notes_of_the_same_name_do_not_overwrite_each_other(vault):
    """Two notes titled alike in one month used to share a file."""
    for nid, parent in (("n_one", "t_a"), ("n_two", "t_b")):
        apply.upsert_note(vault, {"id": nid, "updated": "2026-09-27T10:00:00Z",
                                  "title": "Buy paint", "parent": parent})
    texts = [p.read_text() for p in (vault.notes_dir / "2026" / "09").glob("*.md")]
    assert len(texts) == 2
    assert any("n_one" in t for t in texts) and any("n_two" in t for t in texts)
