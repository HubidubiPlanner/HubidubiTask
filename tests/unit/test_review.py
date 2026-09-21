"""An agent review survives the round trip to the vault and back.

The review is prose — colons, quotes, newlines — and the phone shows it when
an item is opened. If it does not come back out of the vault byte for byte,
the app either shows nothing or shows something mangled, and the only fix is
paying the model to think again.
"""

from hubidubios_vault import apply, reviews
from hubidubios_vault.formats import notes_md
from hubidubios_vault.index import indexer

PROSE = 'Next action: email the "claim" form.\nBlocked until: Friday.'


def _rel(vault, path):
    return str(path.relative_to(vault.root))


# ------------------------------------------------------------------ notes

def test_a_note_keeps_its_review_in_its_own_file(vault):
    path = apply.upsert_note(vault, {
        "id": "n_abc", "type": "note", "updated": "2026-09-21T09:00:00Z",
        "title": "Framework", "body": "body", "review": PROSE,
    })

    assert "review:" in path.read_text(), "the review is not in the note"
    assert notes_md.parse_file(path.read_text(), path, "").review == PROSE


def test_a_note_without_a_review_says_nothing_about_one(vault):
    path = apply.upsert_note(vault, {
        "id": "n_abc", "type": "note", "updated": "2026-09-21T09:00:00Z",
        "title": "Framework",
    })

    assert "review" not in path.read_text()
    assert notes_md.parse_file(path.read_text(), path, "").review is None


# ------------------------------------- line types: task, inbox item, action

def test_an_inbox_item_keeps_its_review_beside_the_vault(vault):
    path = apply.upsert_inbox(vault, {
        "id": "i_abc", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
        "text": "Whiteboard photo", "time": "09:00", "date": "2026-09-21",
        "review": PROSE,
    })

    # The line itself stays readable: prose cannot go in `key:value` metadata.
    assert "review" not in path.read_text()
    assert reviews.read(vault, "i_abc") == PROSE


def test_a_next_action_keeps_its_review_too(vault):
    apply.upsert_next_action(vault, {
        "id": "t_abc", "type": "next_action", "updated": "2026-09-21T09:00:00Z",
        "text": "Email the form", "project": "framework", "review": PROSE,
    })

    assert reviews.read(vault, "t_abc") == PROSE


def test_the_indexer_hands_the_review_back(vault):
    path = apply.upsert_inbox(vault, {
        "id": "i_abc", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
        "text": "Whiteboard photo", "time": "09:00", "date": "2026-09-21",
        "review": PROSE,
    })
    conn = indexer.open_index(vault)
    indexer.index_file(conn, vault, path)

    import json
    row = conn.execute("SELECT data FROM entities WHERE id = ?", ("i_abc",)).fetchone()
    assert json.loads(row["data"])["review"] == PROSE


def test_a_later_write_without_a_review_does_not_erase_it(vault):
    d = {"id": "i_abc", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
         "text": "Whiteboard photo", "time": "09:00", "date": "2026-09-21"}
    path = apply.upsert_inbox(vault, dict(d, review=PROSE))

    # A phone that has not been told about reviews sends the item without one.
    apply.upsert_inbox(vault, dict(d, text="Whiteboard photo (kitchen)"),
                       _rel(vault, path))

    assert reviews.read(vault, "i_abc") == PROSE, "an old client erased the review"


def test_an_empty_review_clears_it(vault):
    d = {"id": "i_abc", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
         "text": "Whiteboard photo", "time": "09:00", "date": "2026-09-21"}
    path = apply.upsert_inbox(vault, dict(d, review=PROSE))

    apply.upsert_inbox(vault, dict(d, review=""), _rel(vault, path))

    assert reviews.read(vault, "i_abc") is None


def test_deleting_an_item_takes_its_review_with_it(vault):
    path = apply.upsert_inbox(vault, {
        "id": "i_abc", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
        "text": "Whiteboard photo", "time": "09:00", "date": "2026-09-21",
        "review": PROSE,
    })

    apply.delete(vault, "inbox_item", "i_abc", _rel(vault, path))

    assert reviews.read(vault, "i_abc") is None
    assert not reviews.path_for(vault, "i_abc").exists()


def test_a_review_file_cannot_escape_the_directory(vault):
    import pytest

    with pytest.raises(ValueError):
        reviews.path_for(vault, "../../etc/passwd")
