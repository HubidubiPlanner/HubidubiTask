"""The prose path: what the phone's Review actually calls.

It has no tool trace behind it, so when it goes wrong there is nothing to read
afterwards unless this path logs — and an answer of "" reaching the phone as
HTTP 200 is a blank screen with no reason given.
"""

import json
from unittest.mock import MagicMock

import pytest

from hubidubios_sync import server as server_mod


class _Handler(server_mod.Handler):
    def __init__(self):  # noqa: D107 - no socket setup
        self.server = MagicMock()
        self.server.lock = MagicMock(__enter__=lambda s: None, __exit__=lambda *a: False)
        self.sent: tuple[int, dict] | None = None

    def _device(self):
        return "device-1"

    def _json(self, code, payload):
        self.sent = (code, payload)


class _Client:
    def __init__(self, reply):
        self.reply = reply
        self.calls: list[dict] = []

    def complete(self, messages, json_mode=True):
        self.calls.append({"messages": messages, "json_mode": json_mode})
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply


@pytest.fixture(autouse=True)
def log_to_tmp(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path))
    return tmp_path / "hubidubios" / "agent.log"


def _agent(reply):
    agent = MagicMock()
    agent.client = _Client(reply)
    return agent


def test_prose_is_not_asked_for_as_json(log_to_tmp):
    h = _Handler()
    agent = _agent("A roof leak. Call the roofer.")

    h._agent_plain(agent, "review this")

    assert h.sent[0] == 200
    assert agent.client.calls[0]["json_mode"] is False, \
        "asked a model for prose and for a JSON object in the same breath"


def test_a_reply_that_is_all_reasoning_is_an_error_not_a_blank(log_to_tmp):
    h = _Handler()
    h._agent_plain(_agent("<think>" + "thinking " * 50 + "</think>"), "review this")

    code, payload = h.sent
    assert code == 502
    assert "reasoning" in payload["error"]


def test_the_answer_is_what_survived_the_reasoning(log_to_tmp):
    h = _Handler()
    h._agent_plain(_agent("<think>hmm</think>It is about the roof."), "review this")

    assert h.sent == (200, {"answer": "It is about the roof."})


def test_an_unreachable_model_is_reported(log_to_tmp):
    h = _Handler()
    h._agent_plain(_agent(RuntimeError("cannot reach model")), "review this")

    assert h.sent[0] == 502
    assert "cannot reach model" in h.sent[1]["error"]


def test_every_outcome_leaves_a_trace_in_the_log(log_to_tmp):
    h = _Handler()
    h._agent_plain(_agent("<think>x</think>Roof."), "what about the roof?")

    events = [json.loads(line) for line in log_to_tmp.read_text().splitlines()]
    kinds = [e["event"] for e in events]
    assert kinds == ["ask_plain", "answer_plain"]
    assert events[0]["query"] == "what about the roof?"
    # The raw reply is kept: without it there is no way to tell a model that
    # said nothing from one whose answer we threw away.
    assert "<think>" in events[1]["raw"]
    assert events[1]["answer_chars"] == len("Roof.")


def test_a_failure_is_logged_too(log_to_tmp):
    h = _Handler()
    h._agent_plain(_agent(RuntimeError("boom")), "q")

    events = [json.loads(line) for line in log_to_tmp.read_text().splitlines()]
    assert [e["event"] for e in events] == ["ask_plain", "ask_plain_failed"]
