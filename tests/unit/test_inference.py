"""Pluggable OpenAI-compatible endpoint, health probe, and status (Phase 8).

Drives the real LlamaClient against a fake OpenAI server so the wire format,
the /v1/models health probe, bearer auth, and endpoint resolution are all
verified without a GPU or selfhostedai-agent running.
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from hubidubios_agent import config as agent_config
from hubidubios_agent.client import DEFAULT_ENDPOINT, LlamaClient


class FakeOpenAI:
    """A minimal OpenAI-compatible server: /v1/models and /v1/chat/completions.
    Optional bearer key enforcement, mirroring selfhostedai-agent's OpenAiApiServer."""

    def __init__(self, models=("local-30b",), require_key=None, up=True):
        self.models = list(models)
        self.require_key = require_key
        self.up = up
        self.seen_auth = []

    def __enter__(self):
        server = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _authed(self):
                server.seen_auth.append(self.headers.get("Authorization"))
                if not server.require_key:
                    return True
                return self.headers.get("Authorization") == f"Bearer {server.require_key}"

            def _send(self, code, obj):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if not server.up:
                    self._send(503, {"error": "down"})
                    return
                if self.path == "/v1/models":
                    if not self._authed():
                        self._send(401, {"error": "unauthorized"})
                        return
                    self._send(200, {"object": "list",
                                     "data": [{"id": m} for m in server.models]})
                else:
                    self._send(404, {"error": "not found"})

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                self.rfile.read(n)
                if not self._authed():
                    self._send(401, {"error": "unauthorized"})
                    return
                self._send(200, {"choices": [{"message": {"content": '{"answer": "hi"}'}}]})

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}/v1"
        return self

    def __exit__(self, *a):
        self.srv.shutdown()


def test_default_endpoint_is_selfhostedai_openai_server():
    assert DEFAULT_ENDPOINT == "http://127.0.0.1:8081/v1/chat/completions"


def test_health_reports_connected_and_models():
    with FakeOpenAI(models=("local-30b", "embed")) as srv:
        c = LlamaClient(endpoint=srv.base + "/chat/completions")
        h = c.health()
    assert h["connected"] is True
    assert h["models"] == ["local-30b", "embed"]
    assert h["server"].endswith("/v1")


def test_health_disconnected_never_raises():
    # nothing listening on this port
    c = LlamaClient(endpoint="http://127.0.0.1:9/v1/chat/completions")
    h = c.health()
    assert h["connected"] is False and h["models"] == []
    assert "error" in h


def test_bearer_key_is_sent():
    with FakeOpenAI(require_key="secret") as srv:
        ok = LlamaClient(endpoint=srv.base + "/chat/completions", api_key="secret")
        assert ok.health()["connected"] is True
        bad = LlamaClient(endpoint=srv.base + "/chat/completions", api_key="wrong")
        assert bad.health()["connected"] is False


def test_complete_round_trip():
    with FakeOpenAI() as srv:
        c = LlamaClient(endpoint=srv.base + "/chat/completions")
        assert c.complete([{"role": "user", "content": "hi"}]) == '{"answer": "hi"}'


def test_config_resolution_precedence(vault, monkeypatch):
    monkeypatch.delenv("HUBIDUBIOS_MODEL_ENDPOINT", raising=False)
    monkeypatch.delenv("HUBIDUBIOS_MODEL_API_KEY", raising=False)

    # default (no config, no env)
    assert agent_config.resolve(vault)["endpoint"] == DEFAULT_ENDPOINT

    # config file wins over default
    vault.config_path.write_text(
        '[inference]\nendpoint = "http://127.0.0.1:9999/v1/chat/completions"\n'
        'api_key = "k"\n')
    r = agent_config.resolve(vault)
    assert r["endpoint"] == "http://127.0.0.1:9999/v1/chat/completions"
    assert r["api_key"] == "k"

    # env wins over config
    monkeypatch.setenv("HUBIDUBIOS_MODEL_ENDPOINT", "http://127.0.0.1:1234/v1/chat/completions")
    assert agent_config.resolve(vault)["endpoint"].endswith(":1234/v1/chat/completions")


def test_status_cli_connected_and_disconnected(vault, monkeypatch, capsys):
    from hubidubios_agent import cli

    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(vault.root))
    with FakeOpenAI(models=("m1",)) as srv:
        monkeypatch.setenv("HUBIDUBIOS_MODEL_ENDPOINT", srv.base + "/chat/completions")
        assert cli.main(["status"]) == 0
        assert "connected" in capsys.readouterr().out
        assert cli.main(["status", "--json"]) == 0
        out = json.loads(capsys.readouterr().out)
        assert out["connected"] and out["models"] == ["m1"]

    monkeypatch.setenv("HUBIDUBIOS_MODEL_ENDPOINT", "http://127.0.0.1:9/v1/chat/completions")
    assert cli.main(["status"]) == 1
    assert "disconnected" in capsys.readouterr().out
