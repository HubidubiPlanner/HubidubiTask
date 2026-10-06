"""hubidubios-mcp: the vault's skills offered to Claude Code as MCP tools."""

import io
import json
from pathlib import Path

import pytest
from hubidubios_agent.mcp import Server
from hubidubios_plugin_host.registry import discover, export_entity_types
from hubidubios_vault.index import indexer

REPO_PLUGINS = Path(__file__).parents[2] / "src" / "plugins"


@pytest.fixture
def server(vault):
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    export_entity_types(vault, reg)
    (vault.current_dir / "website.md").write_text(
        "---\nproject: website\n---\n\n- [ ] draft the landing copy  <!-- id:t_na000001 -->\n")
    conn = indexer.open_index(vault)
    indexer.rebuild(vault)
    calls = []

    def runner(v, plugin, skill, params):
        calls.append((plugin.name, skill["name"], params))
        return {"ok": True, "skill": skill["name"]}

    s = Server(vault, reg, conn, sandbox_runner=runner)
    s.calls = calls
    yield s
    conn.close()


def rpc(s, method, params=None, mid=1):
    return s.handle({"jsonrpc": "2.0", "id": mid, "method": method, "params": params or {}})


def test_initialize_announces_tools(server):
    r = rpc(server, "initialize", {"protocolVersion": "2025-06-18"})["result"]
    assert r["protocolVersion"] == "2025-06-18"
    assert "tools" in r["capabilities"] and r["serverInfo"]["name"] == "hubidubios"


def test_the_tools_are_the_vault_skills_with_names_mcp_accepts(server):
    tools = rpc(server, "tools/list")["result"]["tools"]
    names = {t["name"] for t in tools}
    assert {"vault-query", "gtd-next", "agenda", "gtd-clarify", "gtd-file"} <= names
    for t in tools:
        assert all(c.isalnum() or c in "-_" for c in t["name"]) and len(t["name"]) <= 64
        assert t["inputSchema"]["type"] == "object" and t["description"]


def test_vault_query_reads_the_index(server):
    r = rpc(server, "tools/call", {"name": "vault-query", "arguments": {"query": "landing"}})["result"]
    assert r["isError"] is False
    assert "landing copy" in r["content"][0]["text"]


def test_a_skill_runs_through_the_sandbox_runner(server):
    r = rpc(server, "tools/call", {"name": "gtd-next", "arguments": {}})["result"]
    assert r["isError"] is False
    assert server.calls[-1][:2] == ("gtd", "gtd-next")


def test_bad_arguments_come_back_as_an_error_the_model_can_read(server):
    r = rpc(server, "tools/call", {"name": "vault-query", "arguments": {"nope": 1}})["result"]
    assert r["isError"] is True and "Invalid arguments" in r["content"][0]["text"]
    assert server.calls == []


def test_unknown_tools_and_methods(server):
    assert rpc(server, "tools/call", {"name": "Bash", "arguments": {}})["result"]["isError"]
    assert rpc(server, "resources/list")["error"]["code"] == -32601


def test_stdio_session_answers_requests_and_ignores_notifications(server):
    inp = io.StringIO("\n".join(json.dumps(m) for m in [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ]) + "\n")
    out = io.StringIO()
    server.serve(inp, out)
    replies = [json.loads(line) for line in out.getvalue().splitlines()]
    assert [r["id"] for r in replies] == [1, 2]
