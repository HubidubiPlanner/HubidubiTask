"""POST /v1/agent/ask streaming endpoint (PLAN.md Phase 4).

The phone must survive the stream dropping mid-response without corrupting
local state — verified by cutting the read off early and confirming the
vault/index are intact and the server still serves.
"""

import json
import threading
import urllib.request
from pathlib import Path

import pytest
from prodos_agent.client import ScriptedClient
from prodos_agent.loop import Agent
from prodos_plugin_host.registry import discover, export_entity_types
from prodos_sync import auth
from prodos_sync.server import serve
from prodos_vault.formats import tasks_md
from prodos_vault.vault import Vault

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v


@pytest.fixture
def agent_server(vault, monkeypatch):
    monkeypatch.setenv("PRODOS_SKILL_SANDBOX", "dangerously-none")
    tasks_md.append_task(vault.tasks_dir / "p.md", "buy milk")
    reg = discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"})
    export_entity_types(vault, reg)

    scripted = ScriptedClient([
        '{"tool": "vault-query", "params": {"query": "buy"}}',
        '{"answer": "You need to buy milk."}',
    ])

    def factory():
        # share the server's index connection
        return Agent(srv.vault, srv.index_conn, reg, scripted)

    srv = serve(vault, "127.0.0.1", 0, use_tls=False, agent_factory=factory)
    port = srv.server_address[1]
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"

    token = auth.mint_pair_token(srv.sync_conn)
    req = urllib.request.Request(f"{base}/v1/pair", method="POST",
                                 data=json.dumps({"token": token,
                                                  "device_name": "p"}).encode())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as r:
        device_token = json.loads(r.read())["device_token"]

    yield base, device_token, vault
    srv.shutdown()
    srv.server_close()
    thread.join(timeout=5)


def _ask(base, token, query):
    req = urllib.request.Request(f"{base}/v1/agent/ask", method="POST",
                                 data=json.dumps({"query": query}).encode())
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(req, timeout=10)


def test_agent_ask_streams_ndjson(agent_server):
    base, token, _ = agent_server
    events = []
    with _ask(base, token, "what should I buy") as r:
        assert r.headers["Content-Type"] == "application/x-ndjson"
        for line in r:
            line = line.decode().strip()
            if line:
                events.append(json.loads(line))
    kinds = [e["event"] for e in events]
    assert kinds == ["tool_call", "tool_result", "answer"]
    assert events[-1]["answer"] == "You need to buy milk."


def test_agent_ask_requires_auth(agent_server):
    base, _, _ = agent_server
    req = urllib.request.Request(f"{base}/v1/agent/ask", method="POST",
                                 data=json.dumps({"query": "x"}).encode())
    req.add_header("Content-Type", "application/json")
    try:
        urllib.request.urlopen(req, timeout=10)
        raise AssertionError("expected 401")
    except urllib.error.HTTPError as e:
        assert e.code == 401


def test_stream_drop_leaves_server_healthy(agent_server):
    base, token, vault = agent_server
    # Read only the first line, then abandon the stream.
    r = _ask(base, token, "what should I buy")
    first = r.readline()
    r.close()
    assert first  # got at least one event

    # Vault untouched (no partial writes from a read-only query) and the
    # server still answers subsequent requests.
    assert (vault.tasks_dir / "p.md").read_text().count("buy milk") == 1
    req = urllib.request.Request(f"{base}/v1/plugins",
                                 headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=10) as r2:
        assert r2.status == 200
