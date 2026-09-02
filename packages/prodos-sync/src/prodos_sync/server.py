"""prodos-sync HTTP API on :5233 — transport for the phone app.

    POST /v1/pair                     one-time token -> device token + id
    GET  /v1/changes?since=<cursor>   entities changed since cursor
    POST /v1/changes                  batch of client-side changes
    GET  /v1/plugins                  installed plugin manifests (Phase 3)
    GET  /v1/plugins/<name>/bundle    signed view bundle (Phase 5)
    POST /v1/agent/ask                proxy to the agent (Phase 4)

Bind to the LAN interface explicitly, never 0.0.0.0. Plain stdlib server:
one JSON request/response at a time is exactly the load a LAN of personal
devices produces, and it keeps the no-dependencies discipline.
"""

import json
import ssl
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from prodos_vault.index import indexer

from . import apply as apply_mod
from . import auth, certs, changelog, db


class SyncServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, addr, vault, use_tls: bool = True, agent_factory=None):
        super().__init__(addr, Handler)
        self.vault = vault
        self.lock = threading.Lock()  # serialize sqlite + file mutations
        self.sync_conn = db.connect(db.sync_db_path(vault))
        self.index_conn = indexer.open_index(vault, check_same_thread=False)
        changelog.purge_expired_tombstones(self.sync_conn)
        # Returns a ready Agent, or None if the agent package is absent. The
        # agent shares this server's index_conn (all access is under self.lock).
        self._agent_factory = agent_factory or self._default_agent_factory
        self._agent = None
        self._agent_built = False
        if use_tls:
            cert, key = certs.ensure_cert(vault)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(cert, key)
            self.socket = ctx.wrap_socket(self.socket, server_side=True)
            self.fingerprint = certs.fingerprint(cert)
        else:
            self.fingerprint = None

    def agent_factory(self):
        if not self._agent_built:
            self._agent = self._agent_factory()
            self._agent_built = True
        return self._agent

    def _default_agent_factory(self):
        try:
            from prodos_agent import config as agent_config
            from prodos_agent.loop import Agent
            from prodos_plugin_host.registry import discover
        except ImportError:
            return None
        client = agent_config.client_for(self.vault)
        return Agent(self.vault, self.index_conn, discover(), client)


class Handler(BaseHTTPRequestHandler):
    server: SyncServer
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quiet; agent.log comes in Phase 4
        pass

    # ------------------------------------------------------------ plumbing

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict | None:
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b"{}"
            parsed = json.loads(raw)
            return parsed if isinstance(parsed, dict) else None
        except (ValueError, json.JSONDecodeError):
            return None

    def _device(self) -> str | None:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return None
        with self.server.lock:
            return auth.authenticate(self.server.sync_conn, header[7:])

    # ------------------------------------------------------------- routes

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/v1/pair":
            return self._pair()
        if path == "/v1/changes":
            return self._push_changes()
        if path == "/v1/agent/ask":
            return self._agent_ask()
        self._json(404, {"error": "not found"})

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/v1/changes":
            return self._pull_changes(parsed)
        if parsed.path == "/v1/plugins":
            return self._plugins()
        if parsed.path == "/v1/agent/health":
            return self._agent_health()
        if parsed.path.startswith("/v1/plugins/") and parsed.path.endswith("/bundle"):
            return self._json(404, {"error": "plugin bundles arrive in Phase 5"})
        self._json(404, {"error": "not found"})

    def _agent_health(self):
        """Report inference-server reachability so the phone can show a native
        connection-status chip. The phone can't reach the model directly (it is
        loopback-only on the workstation); the workstation probes on its behalf."""
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        agent = self.server.agent_factory()
        if agent is None:
            return self._json(200, {"connected": False, "server": None,
                                    "models": [], "error": "agent not installed"})
        self._json(200, agent.client.health())

    def _pair(self):
        body = self._body()
        if body is None or not body.get("token"):
            return self._json(400, {"error": "missing token"})
        with self.server.lock:
            granted = auth.redeem_pair_token(
                self.server.sync_conn, body["token"],
                str(body.get("device_name") or "unnamed"))
        if not granted:
            return self._json(403, {"error": "invalid or expired pair token"})
        self._json(200, granted)

    def _pull_changes(self, parsed):
        device_id = self._device()
        if not device_id:
            return self._json(401, {"error": "unauthorized"})
        try:
            since = int(parse_qs(parsed.query).get("since", ["0"])[0])
        except ValueError:
            return self._json(400, {"error": "since must be an integer cursor"})
        with self.server.lock:
            indexer.sweep(self.server.index_conn, self.server.vault)
            changelog.capture(self.server.index_conn, self.server.sync_conn)
            result = changelog.feed(self.server.sync_conn, since)
        self._json(200, result)

    def _push_changes(self):
        device_id = self._device()
        if not device_id:
            return self._json(401, {"error": "unauthorized"})
        body = self._body()
        if body is None or not isinstance(body.get("changes"), list):
            return self._json(400, {"error": "expected {changes: [...]}"})
        with self.server.lock:
            results = apply_mod.apply_changes(
                self.server.vault, self.server.index_conn,
                self.server.sync_conn, device_id, body["changes"])
            cursor = self.server.sync_conn.execute(
                "SELECT COALESCE(MAX(seq), 0) AS c FROM changes").fetchone()["c"]
        self._json(200, {"results": results, "cursor": cursor})

    def _plugins(self):
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        # Populated in Phase 3 from installed plugin manifests.
        self._json(200, {"plugins": []})

    def _agent_ask(self):
        """Stream the agent loop as NDJSON. The phone must survive the stream
        dropping mid-response without corrupting local state, so each event is
        a self-contained JSON line and nothing is committed client-side until
        the terminal answer event arrives."""
        device_id = self._device()
        if not device_id:
            return self._json(401, {"error": "unauthorized"})
        body = self._body()
        if body is None or not body.get("query"):
            return self._json(400, {"error": "missing query"})
        agent = self.server.agent_factory() if self.server.agent_factory else None
        if agent is None:
            return self._json(501, {"error": "agent not installed on this host"})

        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            with self.server.lock:
                for event in agent.run_streamed(str(body["query"])):
                    self._chunk(json.dumps(event) + "\n")
        except (BrokenPipeError, ConnectionResetError):
            return  # client hung up mid-stream; nothing to roll back
        except Exception as e:  # e.g. model unreachable — tell the client, don't 500
            try:
                self._chunk(json.dumps({"event": "error", "error": str(e)}) + "\n")
            except (BrokenPipeError, ConnectionResetError):
                return
        try:
            self._chunk("")  # terminating zero-length chunk
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _chunk(self, text: str) -> None:
        data = text.encode("utf-8")
        self.wfile.write(f"{len(data):X}\r\n".encode("ascii") + data + b"\r\n")
        self.wfile.flush()


def serve(vault, bind: str, port: int = 5233, use_tls: bool = True,
          agent_factory=None) -> SyncServer:
    return SyncServer((bind, port), vault, use_tls=use_tls,
                      agent_factory=agent_factory)
