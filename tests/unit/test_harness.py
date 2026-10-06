"""hubidubios-harness: the OpenAI-shaped front of a local Claude Code.

A stand-in harness command replaces `claude`, so these run anywhere: it
echoes what it was given, which is what the server's contract is about.
"""

import json
import ssl
import sys
import threading
import urllib.error
import urllib.request

import pytest
from hubidubios_harness import server

FAKE = (
    "import json,sys; args=sys.argv[1:]; prompt=sys.stdin.read();"
    "sp=args[args.index('--system-prompt')+1] if '--system-prompt' in args else '';"
    "print(json.dumps({'type':'result','is_error':False,'result':'SYS['+sp+'] '+prompt}))"
)


@pytest.fixture
def harness(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setenv("HUBIDUBIOS_HARNESS_COMMAND", f"{sys.executable} -c \"{FAKE}\"")
    d = server.state_dir()
    httpd = server.make_server("127.0.0.1", 0, d)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    yield f"https://127.0.0.1:{httpd.server_address[1]}", server.token(d), d
    httpd.shutdown()


def call(base, path, token=None, body=None):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE   # a self-signed LAN certificate, as the apps see it
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                                 method="POST" if body else "GET")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=20) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def test_no_token_no_answer(harness):
    base, _, _ = harness
    assert call(base, "/v1/models")[0] == 401
    assert call(base, "/v1/models", token="wrong")[0] == 401


def test_models_lists_the_harness(harness):
    base, tok, _ = harness
    status, body = call(base, "/v1/models", tok)
    assert status == 200 and body["data"][0]["id"] == "claude-code"


def test_a_question_runs_the_harness_with_the_system_prompt(harness):
    base, tok, _ = harness
    status, body = call(base, "/v1/chat/completions", tok, {"model": "claude-code", "messages": [
        {"role": "system", "content": "Be brief."},
        {"role": "system", "content": "Open: buy milk"},
        {"role": "user", "content": "What first?"}]})
    assert status == 200
    assert body["choices"][0]["message"]["content"] == "SYS[Be brief.\n\nOpen: buy milk] What first?"


def test_the_default_command_has_every_tool_switched_off(monkeypatch):
    monkeypatch.delenv("HUBIDUBIOS_HARNESS_COMMAND", raising=False)
    cmd = server.command()
    assert cmd[:2] == ["claude", "-p"]
    assert cmd[cmd.index("--tools") + 1] == ""
    assert "--no-session-persistence" in cmd


def test_a_conversation_is_flattened_into_turns():
    system, convo = server.prompt_of([
        {"role": "system", "content": "S"}, {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Hello"}, {"role": "user", "content": "Plan?"}])
    assert system == "S"
    assert convo == "User: Hi\n\nAssistant: Hello\n\nUser: Plan?"


def test_a_failing_harness_is_reported(harness, monkeypatch):
    base, tok, _ = harness
    monkeypatch.setenv("HUBIDUBIOS_HARNESS_COMMAND", "definitely-not-a-command-xyz")
    status, body = call(base, "/v1/chat/completions", tok, {"messages": [{"role": "user", "content": "x"}]})
    assert status == 502 and "not installed" in body["error"]["message"]


def test_token_and_certificate_are_kept_between_runs(harness):
    _, tok, d = harness
    cert, _ = server.certificate(d)
    assert server.token(d) == tok
    assert len(server.fingerprint(cert)) == 64
    assert oct((d / "token").stat().st_mode)[-3:] == "600"
