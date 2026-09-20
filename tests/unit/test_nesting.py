"""Items nest under a parent, and the pointer survives a round trip.

The Projects view is a tree the user rearranges, so nesting has to live in the
data — not in markdown indentation, which is cosmetic and does not survive a
user reordering lines by hand.
"""

from hubidubios_vault import apply
from hubidubios_vault.formats import generic


def _vault(tmp_path):
    from hubidubios_vault.vault import Vault

    v = Vault(tmp_path)
    v.ensure_layout()
    return v


def test_action_parent_round_trips(tmp_path):
    v = _vault(tmp_path)

    path = apply.upsert_next_action(v, {
        "id": "t_child", "type": "next_action", "updated": "2026-09-20T10:00:00Z",
        "text": "draft the spec", "done": False, "project": "website",
        "parent": "p_site",
    })

    (d, _), = generic.parse_checkbox(path.read_text(), path, "2026-09-20T10:00:00Z",
                                     "next_action")
    assert d["parent"] == "p_site", "nesting pointer lost on write-back"
    assert d["text"] == "draft the spec", "parent metadata leaked into the text"


def test_project_parent_round_trips(tmp_path):
    v = _vault(tmp_path)

    path = apply.upsert_project(v, {
        "id": "p_child", "type": "project", "updated": "2026-09-20T10:00:00Z",
        "title": "Redesign", "parent": "p_site",
    })

    (d, _), = generic.parse_frontmatter(path.read_text(), path,
                                        "2026-09-20T10:00:00Z", "project")
    assert d["parent"] == "p_site", "sub-project lost its parent"


def test_top_level_items_carry_no_parent(tmp_path):
    """The common case must not grow metadata it does not need."""
    v = _vault(tmp_path)

    path = apply.upsert_next_action(v, {
        "id": "t_solo", "type": "next_action", "updated": "2026-09-20T10:00:00Z",
        "text": "buy milk", "done": False, "project": "inbox",
    })

    assert "parent:" not in path.read_text()


def test_reparenting_replaces_rather_than_appends(tmp_path):
    """Moving an item twice must leave exactly one parent pointer."""
    v = _vault(tmp_path)
    base = {"id": "t_x", "type": "next_action", "updated": "2026-09-20T10:00:00Z",
            "text": "task", "done": False, "project": "website"}

    p1 = apply.upsert_next_action(v, {**base, "parent": "p_one"})
    rel = str(p1.relative_to(v.root))
    p2 = apply.upsert_next_action(v, {**base, "parent": "p_two"}, existing_rel=rel)

    text = p2.read_text()
    assert text.count("parent:") == 1, text
    (d, _), = generic.parse_checkbox(text, p2, "2026-09-20T10:00:00Z", "next_action")
    assert d["parent"] == "p_two"
