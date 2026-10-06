"""Media notes: a note gains kind (text/image/audio) + an attachment reference;
bytes live in a content-addressed blob store and are materialized into notes/."""

import json

import pytest
from hubidubios_vault import apply as vault_apply
from hubidubios_vault import blobs, cli
from hubidubios_vault.entities import Note, now_rfc3339
from hubidubios_vault.formats import notes_md

# ------------------------------------------------------------ model round-trip

def test_image_note_frontmatter_round_trips():
    n = Note(id="n_img1", updated=now_rfc3339(), title="beach", kind="image",
             media="notes/2026/09/beach.jpg", media_sha256="a" * 64,
             media_mime="image/jpeg", media_bytes=1234)
    from pathlib import Path
    parsed = notes_md.parse_file(notes_md.render(n), Path("beach.md"), n.updated)
    assert parsed.kind == "image"
    assert parsed.media == "notes/2026/09/beach.jpg"
    assert parsed.media_sha256 == "a" * 64 and parsed.media_bytes == 1234


def test_text_note_omits_media_frontmatter():
    n = Note(id="n_t1", updated=now_rfc3339(), title="idea", body="hello")
    rendered = notes_md.render(n)
    assert "kind:" not in rendered and "media" not in rendered  # clean text note


# ------------------------------------------------------------ blob store

def test_blob_store_and_get_by_hash(vault):
    vault.ensure_layout()
    data = b"\x89PNG fake image bytes"
    sha = blobs.store(vault, data)
    assert sha == blobs.sha256_of(data)
    assert blobs.staged_path(vault, sha).read_bytes() == data
    assert blobs.get(vault, sha) == data


def test_blob_rejects_wrong_hash_and_oversize(vault):
    vault.ensure_layout()
    with pytest.raises(blobs.BlobError):
        blobs.store(vault, b"abc", expected_sha="b" * 64)
    with pytest.raises(blobs.BlobError):
        blobs.store(vault, b"x" * (blobs.MAX_BLOB_BYTES + 1))


# ------------------------------------------------------------ CLI create + read

def test_cli_note_new_with_media(vault, tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    vault.ensure_layout()
    img = tmp_path / "cat.png"
    img.write_bytes(b"\x89PNG\r\n\x1a\n cat pixels")
    code = cli.main(["note", "new", "my cat", "--media", str(img)])
    assert code == 0
    nid = capsys.readouterr().out.strip()
    # the .md note + the media file both exist under notes/
    md = list(vault.notes_dir.rglob("*.md"))[0].read_text()
    assert "kind: image" in md and "media_sha256:" in md
    assert list(vault.notes_dir.rglob("*.png"))
    # indexed + listable + showable
    cli.main(["index", "rebuild"])
    capsys.readouterr()
    cli.main(["note", "list", "--kind", "image", "--json"])
    listed = json.loads(capsys.readouterr().out)
    assert listed and listed[0]["kind"] == "image" and listed[0]["id"] == nid


# ------------------------------------------------------------ sync materialize

def test_apply_note_materializes_media_from_blob(vault):
    vault.ensure_layout()
    data = b"RIFF fake wav audio"
    sha = blobs.store(vault, data)   # blob arrives first (staged)
    vault_apply.upsert(vault, "note", {
        "id": "n_a1", "updated": now_rfc3339(), "title": "memo", "kind": "audio",
        "media_sha256": sha, "media_mime": "audio/mp4", "media_bytes": len(data)})
    # the note file was written AND the media file materialized from the blob
    md = list(vault.notes_dir.rglob("*.md"))
    m4a = list(vault.notes_dir.rglob("*.m4a"))
    assert md and m4a and m4a[0].read_bytes() == data
