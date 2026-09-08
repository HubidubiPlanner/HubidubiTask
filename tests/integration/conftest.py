import json
import threading
import urllib.request

import pytest
from hubidubios_sync import auth
from hubidubios_sync.server import serve
from hubidubios_vault.vault import Vault


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v


class Client:
    def __init__(self, base: str, token: str | None = None, ctx=None):
        self.base = base
        self.token = token
        self.ctx = ctx

    def request(self, method: str, path: str, payload: dict | None = None):
        req = urllib.request.Request(self.base + path, method=method)
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        body = None
        if payload is not None:
            body = json.dumps(payload).encode()
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, body, timeout=10, context=self.ctx) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())


@pytest.fixture
def sync_server(vault):
    """A running no-TLS server plus a paired client. Yields (server, client)."""
    # Phase 2 fixture: no agent wired in (that path lives in test_agent_endpoint).
    srv = serve(vault, "127.0.0.1", 0, use_tls=False, agent_factory=lambda: None)
    port = srv.server_address[1]
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"

    pair_token = auth.mint_pair_token(srv.sync_conn)
    status, granted = Client(base).request(
        "POST", "/v1/pair", {"token": pair_token, "device_name": "test-phone"})
    assert status == 200
    client = Client(base, granted["device_token"])
    client.device_id = granted["device_id"]

    yield srv, client
    srv.shutdown()
    srv.server_close()
    thread.join(timeout=5)
