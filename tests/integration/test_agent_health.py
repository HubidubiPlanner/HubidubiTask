"""GET /v1/agent/health on prodos-sync (Phase 8): the phone learns whether the
workstation's inference endpoint is reachable, so it can show a native
connection-status chip. The phone can't reach the loopback-only model directly;
the workstation probes on its behalf."""

import json
import threading
import urllib.request
from pathlib import Path

import pytest

from prodos_vault.vault import Vault

from prodos_agent.client import ScriptedClient
from prodos_agent.loop import Agent
from prodos_plugin_host.registry import discover, export_entity_types
from prodos_sync import auth
from prodos_sync.server import serve

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v


def _paired_client(srv):
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    token = auth.mint_pair_token(srv.sync_conn)
    req = urllib.request.Request(f"{base}/v1/pair", method="POST",
                                 data=json.dumps({"token": token, "device_name": "p"}).encode())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as r:
        return base, json.loads(r.read())["device_token"]


def _run(base, token, path):
    req = urllib.request.Request(base + path,
                                 headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def _serve_with(vault, health):
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    export_entity_types(vault, reg)

    def factory():
        client = ScriptedClient([], health=health)
        return Agent(srv.vault, srv.index_conn, reg, client)

    srv = serve(vault, "127.0.0.1", 0, use_tls=False, agent_factory=factory)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def test_agent_health_connected(vault):
    srv = _serve_with(vault, {"connected": True, "server": "http://127.0.0.1:8081/v1",
                              "models": ["local-30b"]})
    try:
        base, token = _paired_client(srv)
        status, body = _run(base, token, "/v1/agent/health")
        assert status == 200
        assert body["connected"] is True and body["models"] == ["local-30b"]
    finally:
        srv.shutdown()


def test_agent_health_disconnected(vault):
    srv = _serve_with(vault, {"connected": False, "server": "http://127.0.0.1:8081/v1",
                              "models": [], "error": "unreachable"})
    try:
        base, token = _paired_client(srv)
        status, body = _run(base, token, "/v1/agent/health")
        assert status == 200 and body["connected"] is False
    finally:
        srv.shutdown()


def test_agent_health_requires_auth(vault):
    srv = _serve_with(vault, {"connected": True, "server": "x", "models": []})
    try:
        base = f"http://127.0.0.1:{srv.server_address[1]}"
        try:
            urllib.request.urlopen(base + "/v1/agent/health", timeout=10)
            raise AssertionError("expected 401")
        except urllib.error.HTTPError as e:
            assert e.code == 401
    finally:
        srv.shutdown()
