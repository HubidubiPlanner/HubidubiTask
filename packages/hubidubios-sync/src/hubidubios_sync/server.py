"""hubidubios-sync HTTP API on :5233 — transport for the phone app.

    POST /v1/pair                     one-time token -> device token + id
    GET  /v1/changes?since=<cursor>   entities changed since cursor
    POST /v1/changes                  batch of client-side changes
    PUT  /v1/blobs/<sha256>           upload media bytes for an image/audio note
    GET  /v1/blobs/<sha256>           download media bytes by content hash
    GET  /v1/plugins                  installed plugin manifests (Phase 3)
    GET  /v1/plugins/<name>/bundle    signed view bundle (Phase 5)
    POST /v1/agent/ask                proxy to the agent (Phase 4)
    GET  /v1/agent/config             inference settings (secrets redacted)
    POST /v1/agent/config             point the agent at an inference server

Bind to the LAN interface explicitly, never 0.0.0.0. Plain stdlib server:
one JSON request/response at a time is exactly the load a LAN of personal
devices produces, and it keeps the no-dependencies discipline.
"""

import json
import ssl
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from hubidubios_vault import blobs
from hubidubios_vault.index import indexer

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
            from hubidubios_agent import config as agent_config
            from hubidubios_agent.loop import Agent
            from hubidubios_plugin_host.registry import discover
        except ImportError:
            return None
        client = agent_config.client_for(self.vault)
        return Agent(self.vault, self.index_conn, discover(), client)


class Handler(BaseHTTPRequestHandler):
    server: SyncServer
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # One line per request to stderr, which systemd puts in the journal:
        #   journalctl --user -u hubidubios-sync -f
        # Silence here meant a failing request left no trace anywhere at all.
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stderr.flush()

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

    def _body_bytes(self) -> bytes:
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length) if length else b""

    def _send_bytes(self, code: int, content_type: str, data: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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
        if path == "/v1/agent/config":
            return self._set_agent_config()
        self._json(404, {"error": "not found"})

    def do_PUT(self):
        path = urlparse(self.path).path
        if path.startswith("/v1/blobs/"):
            return self._put_blob(path[len("/v1/blobs/"):])
        self._json(404, {"error": "not found"})

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/v1/changes":
            return self._pull_changes(parsed)
        if parsed.path.startswith("/v1/blobs/"):
            return self._get_blob(parsed.path[len("/v1/blobs/"):])
        if parsed.path == "/v1/plugins":
            return self._plugins()
        if parsed.path == "/v1/agent/health":
            return self._agent_health()
        if parsed.path == "/v1/agent/config":
            return self._get_agent_config()
        if parsed.path.startswith("/v1/plugins/") and parsed.path.endswith("/bundle"):
            return self._json(404, {"error": "plugin bundles arrive in Phase 5"})
        self._json(404, {"error": "not found"})

    # ------------------------------------------------------------- blobs

    def _put_blob(self, sha: str):
        """Upload media bytes for an image/audio note, addressed by SHA-256.
        Verifies the hash, enforces the size cap, and materializes the bytes into
        any note already referencing them (so blob + note can arrive in any
        order)."""
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        data = self._body_bytes()
        with self.server.lock:
            try:
                stored = blobs.store(self.server.vault, data, expected_sha=sha)
            except blobs.BlobError as e:
                code = 413 if "too large" in str(e) else 400
                return self._json(code, {"error": str(e)})
            self._materialize_blob(stored)
        self._json(200, {"stored": True, "sha256": stored})

    def _get_blob(self, sha: str):
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        with self.server.lock:
            data = blobs.get(self.server.vault, sha)
        if data is None:
            return self._json(404, {"error": "no such blob"})
        self._send_bytes(200, "application/octet-stream", data)

    def _materialize_blob(self, sha: str) -> None:
        """Fill in the media file of any entity already referencing this blob.

        A phone capture is either a note or an inbox item ("task or note?" is
        asked at capture time), and both carry media the same way, so both must
        be materialized — otherwise a photo captured as a task uploads its bytes
        and then has no file in the vault.
        """
        idx = self.server.index_conn
        indexer.sweep(idx, self.server.vault)
        rows = idx.execute(
            "SELECT data FROM entities WHERE type IN ('note','inbox_item') "
            "AND json_extract(data,'$.media_sha256')=?", (sha,)).fetchall()
        if not rows:
            return
        staged = blobs.staged_path(self.server.vault, sha)
        if not staged.exists():
            return
        data = staged.read_bytes()
        for row in rows:
            media_rel = json.loads(row["data"]).get("media")
            if not media_rel:
                continue
            target = self.server.vault.root / media_rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    def _agent_plain(self, agent, query: str):
        """One completion, plain text in and out. No tools, no JSON contract.

        Everything here is logged to agent.log: this path has no tool trace to
        look at afterwards, so without it a bad answer leaves nothing to debug
        — which is exactly the state it was in.
        """
        from hubidubios_agent.log import log_event
        from hubidubios_agent.loop import strip_reasoning

        system = ("You are the user's assistant. Answer in plain prose. "
                  "Be concise and concrete. Do not use JSON, markdown fences, "
                  "or tool calls.")
        log_event({"event": "ask_plain", "query": query[:2000]})
        try:
            with self.server.lock:
                text = agent.client.complete([
                    {"role": "system", "content": system},
                    {"role": "user", "content": query},
                ], json_mode=False)
        except Exception as e:
            log_event({"event": "ask_plain_failed", "error": str(e)})
            return self._json(502, {"error": str(e)})

        answer = strip_reasoning(text)
        log_event({"event": "answer_plain", "raw_chars": len(text),
                   "answer_chars": len(answer), "raw": text[:4000]})
        if not answer.strip():
            # The model replied, but all of it was reasoning. Returning 200 with
            # an empty string left the phone showing a blank sheet and no reason
            # for it; say what happened instead.
            return self._json(502, {
                "error": "the model returned only reasoning, no answer "
                         f"({len(text)} characters of it). Try again, or give "
                         "it a shorter item."})
        return self._json(200, {"answer": answer})

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

    # --------------------------------------------------- inference config

    # The phone cannot reach the model itself (it is the workstation that holds
    # the endpoint and the pinned certificate), so pointing HubidubiOS at an
    # inference server has to be done here. Exposing it over sync is what makes
    # that possible from the phone instead of by hand-editing config.toml.

    _AGENT_KEYS = ("protocol", "endpoint", "model", "tls_fingerprint")

    def _get_agent_config(self):
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        section = (self.server.vault.config().get("inference") or {})
        out = {k: section.get(k) for k in self._AGENT_KEYS}
        # Never hand a secret back to a device; report only whether one is set.
        out["api_key_set"] = bool(section.get("api_key"))
        return self._json(200, out)

    def _set_agent_config(self):
        if not self._device():
            return self._json(401, {"error": "unauthorized"})
        body = self._body()
        if body is None:
            return self._json(400, {"error": "invalid body"})
        endpoint = str(body.get("endpoint") or "").strip()
        if not endpoint.startswith(("http://", "https://")):
            return self._json(400, {"error": "endpoint must start with http:// or https://"})
        protocol = str(body.get("protocol") or "openai").strip().lower()
        if protocol not in ("openai", "selfhostedai"):
            return self._json(400, {"error": "protocol must be 'openai' or 'selfhostedai'"})

        from hubidubios_vault.config_edit import set_section

        section = dict(self.server.vault.config().get("inference") or {})
        section.update({k: (str(body[k]).strip() or None) for k in self._AGENT_KEYS
                        if k in body})
        section["endpoint"], section["protocol"] = endpoint, protocol
        with self.server.lock:
            set_section(self.server.vault.config_path, "inference",
                        {k: v for k, v in section.items() if v})
        # A new endpoint means the cached client is pointing at the old one.
        self.server._agent = None
        self.server._agent_built = False
        return self._json(200, {"saved": True, "endpoint": endpoint, "protocol": protocol})

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

        # Summarising a note needs prose, not a tool call. The agent loop makes
        # the model emit one JSON object per turn so tools can be dispatched;
        # asking that of a model whose own server wraps it in a reasoning
        # persona produces <think> blocks and commentary, none of which parse,
        # and the user gets "I couldn't produce a valid response" for what was
        # only ever a request for a sentence of text.
        if body.get("plain"):
            return self._agent_plain(agent, str(body["query"]))

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
