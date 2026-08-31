import json
from pathlib import Path

import pytest

from prodos_vault.formats import tasks_md
from prodos_vault.index import indexer

from prodos_plugin_host.registry import discover, export_entity_types

from prodos_agent.client import ScriptedClient
from prodos_agent.context import run_vault_query
from prodos_agent.loop import Agent, _parse_action

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def agent_env(vault, monkeypatch, tmp_path):
    monkeypatch.setenv("PRODOS_SKILL_SANDBOX", "dangerously-none")
    # Phase 3 install set (the agent tests use inbox.capture / vault-query).
    reg = discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"})
    export_entity_types(vault, reg)
    conn = indexer.open_index(vault)
    log_file = tmp_path / "agent.log"

    def make(client):
        return Agent(vault, conn, reg, client, log_file=log_file), conn, log_file

    return vault, make


# ------------------------------------------------------------ parsing

def test_parse_action_tolerates_fences_and_prose():
    assert _parse_action('{"answer": "hi"}') == {"answer": "hi"}
    assert _parse_action('```json\n{"answer": "hi"}\n```') == {"answer": "hi"}
    assert _parse_action('Sure!\n{"tool": "x", "params": {}}\nthanks') == {
        "tool": "x", "params": {}}
    assert _parse_action("not json") is None


# ------------------------------------------------------------ loop

def test_direct_answer_no_tools(agent_env):
    vault, make = agent_env
    agent, conn, _ = make(ScriptedClient(['{"answer": "42"}']))
    result = agent.run("what is the answer")
    assert result["answer"] == "42"
    assert [e["event"] for e in result["events"]] == ["answer"]


def test_tool_call_then_answer(agent_env):
    vault, make = agent_env
    tasks_md.append_task(vault.tasks_dir / "p.md", "buy milk")
    client = ScriptedClient([
        '{"tool": "vault-query", "params": {"query": "milk"}}',
        lambda msgs: '{"answer": "You have: buy milk"}',
    ])
    agent, conn, log_file = make(client)
    result = agent.run("what do I need to buy")
    assert "buy milk" in result["answer"]
    kinds = [e["event"] for e in result["events"]]
    assert kinds == ["tool_call", "tool_result", "answer"]
    # the tool result was fed back into the second model call
    second_call = client.calls[1]
    assert any("tool_result for vault-query" in m["content"] for m in second_call)


def test_invalid_then_valid_retry(agent_env):
    vault, make = agent_env
    client = ScriptedClient([
        "I think you should...",                       # unparseable
        '{"tool": "nonesuch", "params": {}}',          # unknown tool
        '{"answer": "ok"}',                            # finally valid
    ])
    agent, conn, log_file = make(client)
    result = agent.run("hi")
    assert result["answer"] == "ok"
    log = log_file.read_text()
    assert log.count('"invalid_action"') == 2


def test_schema_violation_fed_back(agent_env):
    vault, make = agent_env
    seen = []
    client = ScriptedClient([
        '{"tool": "inbox.capture", "params": {}}',      # missing required text
        lambda msgs: seen.append(msgs) or '{"answer": "done"}',
    ])
    agent, conn, _ = make(client)
    result = agent.run("capture something")
    assert result["answer"] == "done"
    retry_prompt = seen[0][-1]["content"]
    assert "invalid" in retry_prompt.lower()


def test_exhausted_retries_gives_up(agent_env):
    vault, make = agent_env
    client = ScriptedClient(["nope", "still nope", "nope again"])
    agent, conn, _ = make(client)
    result = agent.run("hi")
    assert "couldn't produce a valid response" in result["answer"]


def test_turn_cap_forces_answer(agent_env):
    vault, make = agent_env
    # Always call a tool; the loop must cap at MAX_TURNS and force an answer.
    always_tool = ScriptedClient(
        ['{"tool": "vault-query", "params": {"query": "x"}}'] * 20
        + ['{"answer": "forced final"}'] * 5)
    agent, conn, _ = make(always_tool)
    result = agent.run("loop forever")
    answer_events = [e for e in result["events"] if e["event"] == "answer"]
    assert answer_events and answer_events[-1].get("forced")
    tool_calls = [e for e in result["events"] if e["event"] == "tool_call"]
    assert len(tool_calls) <= 5  # capped


def test_skill_execution_writes_vault(agent_env):
    vault, make = agent_env
    client = ScriptedClient([
        '{"tool": "inbox.capture", "params": {"text": "call the dentist"}}',
        '{"answer": "captured"}',
    ])
    agent, conn, _ = make(client)
    agent.run("remind me to call the dentist")
    files = list(vault.inbox_dir.glob("*.md"))
    assert files and "call the dentist" in files[0].read_text()


def test_skill_error_is_fed_back_not_fatal(agent_env):
    vault, make = agent_env
    calls = []

    def failing(*a, **k):
        raise RuntimeError("boom")

    from prodos_plugin_host.registry import discover as _d
    reg = _d([REPO_PLUGINS])
    conn = indexer.open_index(vault)
    client = ScriptedClient([
        '{"tool": "inbox.capture", "params": {"text": "x"}}',
        lambda msgs: calls.append(msgs) or '{"answer": "recovered"}',
    ])
    from prodos_agent.loop import Agent as A
    agent = A(vault, conn, reg, client, sandbox_runner=failing)
    result = agent.run("do it")
    assert result["answer"] == "recovered"
    fed = calls[0][-1]["content"]
    assert "boom" in fed  # the error text reached the model


def test_every_skill_invocation_logged(agent_env):
    vault, make = agent_env
    tasks_md.append_task(vault.tasks_dir / "p.md", "a task")
    client = ScriptedClient([
        '{"tool": "vault-query", "params": {"query": "task"}}',
        '{"answer": "found it"}',
    ])
    agent, conn, log_file = make(client)
    agent.run("what tasks")
    lines = [json.loads(x) for x in log_file.read_text().splitlines()]
    events = [l["event"] for l in lines]
    assert "ask" in events and "tool_result" in events and "answer" in events
    tr = next(l for l in lines if l["event"] == "tool_result")
    assert tr["tool"] == "vault-query" and tr["params"] == {"query": "task"}


# ------------------------------------------------------------ context budget

def test_vault_query_respects_token_budget(vault):
    for i in range(50):
        tasks_md.append_task(vault.tasks_dir / "p.md",
                             f"task {i} with some length to it about budgeting")
    conn = indexer.open_index(vault)
    res = run_vault_query(conn, {"query": "budgeting", "limit": 50},
                          token_budget=100)  # ~400 chars
    assert res["truncated"]
    assert sum(len(s) for s in res["snippets"]) <= 100 * 4 + 200
    conn.close()
