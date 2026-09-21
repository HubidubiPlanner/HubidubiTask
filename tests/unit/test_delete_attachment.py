"""Deleting an item takes its attachment with it.

Otherwise the bytes stay in the vault belonging to nothing: nothing references
them, nothing lists them, and no later operation would ever clean them up.
"""

from hubidubios_vault import apply


def _vault(tmp_path):
    from hubidubios_vault.vault import Vault

    v = Vault(tmp_path)
    v.ensure_layout()
    return v


def test_deleting_a_media_task_removes_the_file(tmp_path):
    v = _vault(tmp_path)
    media = v.inbox_dir / "media" / "i_x9y8.jpg"
    media.parent.mkdir(parents=True, exist_ok=True)
    media.write_bytes(b"jpeg")
    path = apply.upsert_inbox(v, {
        "id": "i_x9y8", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
        "text": "Whiteboard", "time": "09:00", "date": "2026-09-21",
        "kind": "image", "media": str(media.relative_to(v.root)),
        "media_sha256": "a" * 64, "media_mime": "image/jpeg",
    })

    apply.delete(v, "inbox_item", "i_x9y8", str(path.relative_to(v.root)))

    assert not media.exists(), "the photo outlived the task that owned it"


def test_deleting_a_media_note_removes_the_file(tmp_path):
    v = _vault(tmp_path)
    path = apply.upsert_note(v, {
        "id": "n_x9y8", "type": "note", "updated": "2026-09-21T09:00:00Z",
        "title": "Roof", "kind": "image", "media_sha256": "b" * 64,
        "media_mime": "image/jpeg",
    })
    from hubidubios_vault.formats import notes_md
    media = v.root / notes_md.parse_file(path.read_text(), path, "").media
    media.parent.mkdir(parents=True, exist_ok=True)
    media.write_bytes(b"jpeg")

    apply.delete(v, "note", "n_x9y8", str(path.relative_to(v.root)))

    assert not path.exists()
    assert not media.exists(), "the photo outlived the note that owned it"


def test_deleting_a_plain_item_is_unaffected(tmp_path):
    v = _vault(tmp_path)
    path = apply.upsert_inbox(v, {
        "id": "i_plain", "type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
        "text": "call the dentist", "time": "09:00", "date": "2026-09-21",
    })

    apply.delete(v, "inbox_item", "i_plain", str(path.relative_to(v.root)))

    assert "i_plain" not in path.read_text()


def test_a_sibling_item_keeps_its_attachment(tmp_path):
    """Deleting one line must not take the neighbour's file with it."""
    v = _vault(tmp_path)
    keep = v.inbox_dir / "media" / "i_keep.jpg"
    keep.parent.mkdir(parents=True, exist_ok=True)
    keep.write_bytes(b"jpeg")
    base = {"type": "inbox_item", "updated": "2026-09-21T09:00:00Z",
            "time": "09:00", "date": "2026-09-21", "kind": "image",
            "media_sha256": "c" * 64, "media_mime": "image/jpeg"}
    apply.upsert_inbox(v, {**base, "id": "i_keep", "text": "keep",
                           "media": str(keep.relative_to(v.root))})
    path = apply.upsert_inbox(v, {**base, "id": "i_go", "text": "go",
                                  "media": "inbox/media/i_go.jpg"})

    apply.delete(v, "inbox_item", "i_go", str(path.relative_to(v.root)))

    assert keep.exists(), "deleting one item removed another item's attachment"
