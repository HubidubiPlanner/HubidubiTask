"""Media blob channel: PUT/GET /v1/blobs/<sha256>, and materialization of the
bytes into a note's file regardless of whether the blob or the note lands first.
"""

import hashlib
import urllib.error
import urllib.request


def _raw(base, token, method, path, data=None):
    req = urllib.request.Request(base + path, method=method, data=data)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/octet-stream")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _note_change(sha, data_len, title="cat", mime="image/png"):
    return {"id": "n_x1", "type": "note", "op": "upsert",
            "updated": "2026-09-12T00:00:00Z",
            "data": {"title": title, "kind": "image", "media_sha256": sha,
                     "media_mime": mime, "media_bytes": data_len}}


def test_blob_then_note_materializes(sync_server, vault):
    srv, client = sync_server
    data = b"\x89PNG binary media bytes"
    sha = hashlib.sha256(data).hexdigest()

    st, _ = _raw(client.base, client.token, "PUT", f"/v1/blobs/{sha}", data)
    assert st == 200
    st, got = _raw(client.base, client.token, "GET", f"/v1/blobs/{sha}")
    assert st == 200 and got == data

    st, res = client.request("POST", "/v1/changes", {"changes": [_note_change(sha, len(data))]})
    assert st == 200 and res["results"][0]["status"] == "applied"
    pngs = list(vault.notes_dir.rglob("*.png"))
    assert pngs and pngs[0].read_bytes() == data


def test_note_then_blob_materializes(sync_server, vault):
    srv, client = sync_server
    data = b"\x89PNG other order"
    sha = hashlib.sha256(data).hexdigest()

    # note first — file written, media file not yet present
    st, res = client.request("POST", "/v1/changes", {"changes": [_note_change(sha, len(data))]})
    assert st == 200 and res["results"][0]["status"] == "applied"
    assert not list(vault.notes_dir.rglob("*.png"))

    # blob arrives -> server fills in the note's media file
    st, _ = _raw(client.base, client.token, "PUT", f"/v1/blobs/{sha}", data)
    assert st == 200
    pngs = list(vault.notes_dir.rglob("*.png"))
    assert pngs and pngs[0].read_bytes() == data


def test_blob_wrong_hash_rejected(sync_server):
    srv, client = sync_server
    st, _ = _raw(client.base, client.token, "PUT", "/v1/blobs/" + "a" * 64, b"nope")
    assert st == 400


def test_blob_requires_auth(sync_server):
    srv, client = sync_server
    st, _ = _raw(client.base, None, "GET", "/v1/blobs/" + "a" * 64)
    assert st == 401
