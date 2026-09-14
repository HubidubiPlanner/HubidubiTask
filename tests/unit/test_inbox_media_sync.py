"""A media capture pushed from the phone must survive the sync write-back.

This is the path the phone actually uses, and it is *not* the one
`hubidubios capture --media` exercises: the phone sends an entity dict which
`apply.upsert_inbox` renders into the inbox file. An earlier version dropped the
media metadata here, so a photo captured as a task synced as plain text and its
uploaded bytes could never be matched back to the item.

The phone also never sends a `media` path — it has no idea where the vault keeps
files — so the workstation must derive it from the hash.
"""

import hashlib

from hubidubios_vault import apply
from hubidubios_vault.formats import inbox_md


def _vault(tmp_path):
    from hubidubios_vault.vault import Vault

    v = Vault(tmp_path)
    v.ensure_layout()
    return v


def _pushed(sha: str) -> dict:
    """What the phone puts on the wire — note every value is a string."""
    return {
        "id": "i_x9y8", "type": "inbox_item", "updated": "2026-09-14T09:14:00Z",
        "text": "Whiteboard from standup", "time": "09:14", "date": "2026-09-14",
        "kind": "image", "media_sha256": sha,
        "media_mime": "image/jpeg", "media_bytes": "15",
    }


def test_media_survives_the_sync_writeback(tmp_path):
    v = _vault(tmp_path)
    data = b"fake jpeg bytes"
    sha = hashlib.sha256(data).hexdigest()

    path = apply.upsert_inbox(v, _pushed(sha))

    (item, _), = inbox_md.parse_file(path.read_text(), path, "2026-09-14T00:00:00Z")
    assert item.kind == "image", "kind was dropped on write-back"
    assert item.media_sha256 == sha, "the blob reference was dropped on write-back"
    assert item.media_mime == "image/jpeg"
    assert item.media, "no media path was derived for the phone's capture"


def test_media_path_is_derived_with_the_right_extension(tmp_path):
    v = _vault(tmp_path)
    sha = hashlib.sha256(b"x").hexdigest()

    path = apply.upsert_inbox(v, _pushed(sha))
    (item, _), = inbox_md.parse_file(path.read_text(), path, "2026-09-14T00:00:00Z")

    assert item.media.endswith(".jpg"), f"expected a .jpg path, got {item.media}"
    assert item.media.startswith("inbox/")


def test_bytes_are_materialized_when_the_blob_arrived_first(tmp_path):
    """Blob upload then entity — the common order, since the app uploads eagerly."""
    from hubidubios_vault import blobs

    v = _vault(tmp_path)
    data = b"fake jpeg bytes"
    sha = blobs.store(v, data)

    path = apply.upsert_inbox(v, _pushed(sha))

    (item, _), = inbox_md.parse_file(path.read_text(), path, "2026-09-14T00:00:00Z")
    target = v.root / item.media
    assert target.exists(), "staged blob was never materialized into the vault"
    assert target.read_bytes() == data


def test_plain_text_capture_writeback_is_unchanged(tmp_path):
    v = _vault(tmp_path)

    path = apply.upsert_inbox(v, {
        "id": "i_plain", "type": "inbox_item", "updated": "2026-09-14T09:14:00Z",
        "text": "call the dentist", "time": "09:14", "date": "2026-09-14",
    })

    line = path.read_text().strip()
    assert "kind:" not in line and "media:" not in line and "sha:" not in line
    assert line.startswith("- 09:14 call the dentist")
