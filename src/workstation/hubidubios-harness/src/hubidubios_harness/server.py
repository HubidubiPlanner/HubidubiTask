"""hubidubios-harness: Claude Code on this machine, served to your own apps.

The phone and Mac apps' "Harness" agent talks to this server in the
OpenAI chat-completions shape. Each request runs one headless Claude Code call
under the login of the user who runs the server, so it uses that user's own
Claude subscription, for that user's own devices.

Safety, because the prompt can carry text from any web page a feed watches:
- Claude Code runs with every tool switched off (`--tools ""`), in an empty
  folder, with no session saved. It can only write text back.
- Every request needs the bearer token from `hubidubios-harness show`.
- The server binds the LAN address only, never 0.0.0.0, over TLS with its
  own self-signed certificate (the apps pin or accept it like selfhostedai's).

    hubidubios-harness serve          # what the systemd user unit runs
    hubidubios-harness show           # address, token and fingerprint for the apps
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import shlex
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEFAULT_PORT = 5240
MODEL_ID = "claude-code"
DEFAULT_COMMAND = ["claude", "-p", "--output-format", "json", "--tools", "",
                   "--no-session-persistence"]
TIMEOUT = 300
# Two at once: a phone and a Mac checking feeds together; more only queues
# work that a single subscription would rate-limit anyway.
SLOTS = threading.BoundedSemaphore(2)


def state_dir() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    d = base / "hubidubios-harness"
    d.mkdir(parents=True, exist_ok=True)
    return d


def token(d: Path) -> str:
    f = d / "token"
    if not f.exists():
        f.write_text(secrets.token_urlsafe(24) + "\n")
        f.chmod(0o600)
    return f.read_text().strip()


def certificate(d: Path) -> tuple[Path, Path]:
    cert, key = d / "cert.pem", d / "key.pem"
    if not cert.exists() or not key.exists():
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
             "-nodes", "-days", "3650", "-subj", "/CN=hubidubios-harness",
             "-keyout", str(key), "-out", str(cert)],
            check=True, capture_output=True)
        key.chmod(0o600)
    return cert, key


def fingerprint(cert: Path) -> str:
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return hashlib.sha256(der).hexdigest()


def lan_ip() -> str:
    """The address other devices on the LAN reach this machine by."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))   # no packet is sent
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def command() -> list[str]:
    raw = os.environ.get("HUBIDUBIOS_HARNESS_COMMAND")
    return shlex.split(raw) if raw else list(DEFAULT_COMMAND)


def prompt_of(messages: list[dict]) -> tuple[str, str]:
    """(system prompt, conversation) from OpenAI-shaped messages."""
    system = "\n\n".join(str(m.get("content") or "") for m in messages
                         if m.get("role") == "system").strip()
    turns = [m for m in messages if m.get("role") != "system"]
    if len(turns) == 1:
        return system, str(turns[0].get("content") or "")
    names = {"user": "User", "assistant": "Assistant"}
    convo = "\n\n".join(f"{names.get(m.get('role'), 'User')}: {m.get('content') or ''}" for m in turns)
    return system, convo


def run(system: str, conversation: str, model: str | None, workdir: str) -> str:
    """One headless harness call. Raises RuntimeError with a readable reason."""
    cmd = command()
    if system:
        cmd += ["--system-prompt", system]
    if model and model != MODEL_ID:
        cmd += ["--model", model]
    try:
        with SLOTS:
            p = subprocess.run(cmd, input=conversation, capture_output=True, text=True,
                               cwd=workdir, timeout=TIMEOUT)
    except FileNotFoundError:
        raise RuntimeError(f"{cmd[0]} is not installed on this machine")
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"the harness took longer than {TIMEOUT} s")
    out = p.stdout.strip()
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        data = None
    if isinstance(data, dict):
        if data.get("is_error"):
            raise RuntimeError(str(data.get("result") or data.get("subtype") or "the harness failed"))
        return str(data.get("result") or "")
    if p.returncode != 0:
        tail = (p.stderr.strip() or out)[-300:]
        raise RuntimeError(tail or f"the harness exited with {p.returncode}")
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "hubidubios-harness"
    secret: str = ""
    workdir: str = ""

    def log_message(self, fmt, *args):   # no prompts in logs; method and status only
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _send(self, code: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorised(self) -> bool:
        given = (self.headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        if given and hmac.compare_digest(given, self.secret):
            return True
        self._send(401, {"error": {"message": "wrong or missing token"}})
        return False

    def do_GET(self):
        if self.path.rstrip("/") != "/v1/models":
            return self._send(404, {"error": {"message": "not found"}})
        if self._authorised():
            self._send(200, {"object": "list", "data": [{"id": MODEL_ID, "object": "model"}]})

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/chat/completions":
            return self._send(404, {"error": {"message": "not found"}})
        if not self._authorised():
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(min(n, 2_000_000)) or b"{}")
            messages = req["messages"]
        except (ValueError, KeyError, TypeError):
            return self._send(400, {"error": {"message": "expected {\"messages\": [...]}"}})
        system, convo = prompt_of(messages)
        try:
            text = run(system, convo, req.get("model"), self.workdir)
        except RuntimeError as e:
            return self._send(502, {"error": {"message": str(e)}})
        self._send(200, {
            "id": "chatcmpl-" + secrets.token_hex(8), "object": "chat.completion",
            "created": int(time.time()), "model": MODEL_ID,
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": text}}],
        })


def make_server(bind: str, port: int, d: Path, tls: bool = True) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {
        "secret": token(d), "workdir": tempfile.mkdtemp(prefix="hubidubios-harness-")})
    httpd = ThreadingHTTPServer((bind, port), handler)
    if tls:
        cert, key = certificate(d)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    return httpd


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="hubidubios-harness", description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve", help="serve the harness on the LAN")
    s.add_argument("--bind", default="auto", help="address to listen on, or 'auto' for the LAN address")
    s.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub.add_parser("show", help="print the address, token and fingerprint to enter in the apps")
    a = ap.parse_args(argv)

    d = state_dir()
    if a.cmd == "show":
        cert, _ = certificate(d)
        print(f"Address:      https://{lan_ip()}:{DEFAULT_PORT}")
        print(f"Token:        {token(d)}")
        print(f"Fingerprint:  {fingerprint(cert)}")
        return 0

    bind = lan_ip() if a.bind == "auto" else a.bind
    httpd = make_server(bind, a.port, d)
    print(f"hubidubios-harness on https://{bind}:{a.port} running: {shlex.join(command())}",
          file=sys.stderr)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
