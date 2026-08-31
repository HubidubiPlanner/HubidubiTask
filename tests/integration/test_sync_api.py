"""Phase 2 API integration: pairing, cursor pull, push, LWW, tombstones, TLS.

Mirrors PLAN.md's Phase 2 acceptance where it can run off-target:
"curl against /v1/changes with a valid device token returns entity JSON".
"""

import json
import ssl
import threading
import urllib.request

from prodos_vault.formats import tasks_md

from prodos_sync import auth, certs
from prodos_sync.server import serve


def test_changes_requires_token(sync_server):
    srv, client = sync_server
    unauth = type(client)(client.base)
    status, body = unauth.request("GET", "/v1/changes?since=0")
    assert status == 401


def test_pair_token_is_one_time(sync_server, vault):
    srv, client = sync_server
    token = auth.mint_pair_token(srv.sync_conn)
    fresh = type(client)(client.base)
    status, first = fresh.request("POST", "/v1/pair",
                                  {"token": token, "device_name": "a"})
    assert status == 200 and first["device_token"]
    status, second = fresh.request("POST", "/v1/pair",
                                   {"token": token, "device_name": "b"})
    assert status == 403


def test_pull_changes_with_cursor(sync_server, vault):
    srv, client = sync_server
    task_id = tasks_md.append_task(vault.tasks_dir / "prodos.md", "sync me",
                                   due="2026-09-05")

    status, body = client.request("GET", "/v1/changes?since=0")
    assert status == 200
    assert len(body["changes"]) == 1
    ch = body["changes"][0]
    assert ch["id"] == task_id and ch["op"] == "upsert"
    assert ch["data"]["text"] == "sync me"
    assert ch["device_id"] == "_local"
    cursor = body["cursor"]
    assert cursor > 0

    # nothing new: same cursor, empty batch
    status, body = client.request("GET", f"/v1/changes?since={cursor}")
    assert body["changes"] == [] and body["cursor"] == cursor

    # a vim edit shows up on the next pull
    with open(vault.tasks_dir / "prodos.md", "a") as f:
        f.write("- [ ] hand-written  <!-- id:t_hand0001 -->\n")
    status, body = client.request("GET", f"/v1/changes?since={cursor}")
    assert [c["id"] for c in body["changes"]] == ["t_hand0001"]


def test_push_task_lands_in_vault(sync_server, vault):
    srv, client = sync_server
    change = {
        "id": "t_phone001", "type": "task", "op": "upsert",
        "updated": "2026-08-31T18:00:00Z",
        "data": {"text": "from the phone", "project": "mobile", "done": False,
                 "context": "@errands"},
    }
    status, body = client.request("POST", "/v1/changes", {"changes": [change]})
    assert status == 200
    assert body["results"] == [{"id": "t_phone001", "status": "applied"}]

    content = (vault.tasks_dir / "mobile.md").read_text()
    assert "from the phone" in content and "id:t_phone001" in content

    # pusher's own change is attributed to it in the feed
    status, feed = client.request("GET", "/v1/changes?since=0")
    mine = [c for c in feed["changes"] if c["id"] == "t_phone001"]
    assert mine and mine[0]["device_id"] == client.device_id


def test_push_lww_stale_update_rejected(sync_server, vault):
    srv, client = sync_server
    task_id = tasks_md.append_task(vault.tasks_dir / "p.md", "local wins")
    client.request("GET", "/v1/changes?since=0")  # ingest local state

    status, body = client.request("POST", "/v1/changes", {"changes": [{
        "id": task_id, "type": "task", "op": "upsert",
        "updated": "2000-01-01T00:00:00Z",
        "data": {"text": "ancient edit", "project": "p", "done": False},
    }]})
    assert body["results"][0]["status"] == "stale"
    assert "local wins" in (vault.tasks_dir / "p.md").read_text()


def test_push_delete_tombstone_beats_edit(sync_server, vault):
    srv, client = sync_server
    task_id = tasks_md.append_task(vault.tasks_dir / "p.md", "delete me")
    client.request("GET", "/v1/changes?since=0")

    status, body = client.request("POST", "/v1/changes", {"changes": [{
        "id": task_id, "type": "task", "op": "delete",
        "updated": "2026-12-31T00:00:00Z",
    }]})
    assert body["results"][0]["status"] == "applied"
    assert "delete me" not in (vault.tasks_dir / "p.md").read_text()

    # concurrent edit arriving after the tombstone loses, even with newer stamp
    status, body = client.request("POST", "/v1/changes", {"changes": [{
        "id": task_id, "type": "task", "op": "upsert",
        "updated": "2027-01-01T00:00:00Z",
        "data": {"text": "zombie", "project": "p", "done": False},
    }]})
    assert body["results"][0]["status"] == "rejected_tombstone"
    assert "zombie" not in (vault.tasks_dir / "p.md").read_text()


def test_push_note_and_inbox_and_event(sync_server, vault):
    srv, client = sync_server
    status, body = client.request("POST", "/v1/changes", {"changes": [
        {"id": "i_phone001", "type": "inbox_item", "op": "upsert",
         "updated": "2026-08-31T18:05:00Z",
         "data": {"text": "captured on phone", "time": "18:05",
                  "date": "2026-08-31"}},
        {"id": "n_phone001", "type": "note", "op": "upsert",
         "updated": "2026-08-31T18:06:00Z",
         "data": {"title": "Phone note", "body": "written offline",
                  "created": "2026-08-31T18:06:00Z"}},
        {"id": "e_phone001", "type": "event", "op": "upsert",
         "updated": "2026-08-31T18:07:00Z",
         "data": {"uid": "phone-1@prodos", "summary": "Dentist",
                  "dtstart": "2026-09-02T09:00:00Z",
                  "dtend": "2026-09-02T09:30:00Z", "calendar": "personal"}},
    ]})
    assert [r["status"] for r in body["results"]] == ["applied"] * 3

    assert "captured on phone" in (vault.inbox_dir / "2026-08-31.md").read_text()
    note = vault.notes_dir / "2026" / "08" / "phone-note.md"
    assert "written offline" in note.read_text()
    ics_files = list((vault.calendars_dir / "personal").glob("*.ics"))
    assert len(ics_files) == 1 and "Dentist" in ics_files[0].read_text()


def test_plugins_and_agent_stubs(sync_server):
    srv, client = sync_server
    status, body = client.request("GET", "/v1/plugins")
    assert status == 200 and body == {"plugins": []}
    status, body = client.request("POST", "/v1/agent/ask", {"q": "hi"})
    assert status == 501


def test_tls_pinned_fingerprint(vault):
    srv = serve(vault, "127.0.0.1", 0, use_tls=True)
    port = srv.server_address[1]
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        assert srv.fingerprint.startswith("sha256:")
        cert, _ = certs.cert_paths(vault)
        assert certs.fingerprint(cert) == srv.fingerprint

        # trusting the pinned cert works (401 = TLS fine, endpoint wants auth)
        ctx = ssl.create_default_context(cafile=str(cert))
        ctx.check_hostname = False
        url = f"https://127.0.0.1:{port}/v1/plugins"
        try:
            urllib.request.urlopen(url, timeout=10, context=ctx)
            raise AssertionError("expected 401 without a device token")
        except urllib.error.HTTPError as e:
            assert e.code == 401

        # default trust store must NOT accept the self-signed cert
        try:
            urllib.request.urlopen(url, timeout=5)
            raise AssertionError("self-signed cert accepted by default trust store")
        except urllib.error.URLError as e:
            assert isinstance(e.reason, ssl.SSLError) or "certificate" in str(e.reason).lower()
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5)
