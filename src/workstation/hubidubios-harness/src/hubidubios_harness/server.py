"""hubidubios-harness: Claude Code on this machine, served to your own apps.

The phone and Mac apps' "Harness" agent talks to this server in the
OpenAI chat-completions shape. Each request runs one headless Claude Code call
under the login of the user who runs the server, so it uses that user's own
Claude subscription, for that user's own devices.

Tools, per request. A request sets `"hubidubios_tools": true` to let Claude
Code work with: Read, Glob and Grep inside the vault, WebFetch, subagents
(Task), and the vault's own skills over MCP (hubidubios-mcp: vault-query,
gtd-next, agenda, capture, ...). Without it, every tool is off and only text
comes back. The apps send it for chat and reviews and never for feeds, whose
text comes from strangers' web pages.

Safety either way:
- Claude Code runs in --restricted mode: nothing that runs commands or code,
  no user or project settings, file tools confined to the vault, only our
  MCP server, and anything outside the allowed list refused (nobody is there
  to approve it). No session is saved.
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
import shutil
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
DEFAULT_COMMAND = ["claude", "-p", "--output-format", "json", "--no-session-persistence",
                   "--restricted", "--strict-mcp-config", "--permission-prompts", "none"]
# What a request with tools may use. Task is Claude Code's subagent tool.
TOOLS = ["Read", "Glob", "Grep", "WebFetch", "Task"]
MCP_SERVER = "hubidubios"
TIMEOUT = 300
# Looking things up takes turns; on a slow local model, many of them.
TOOLS_TIMEOUT = 540
TOOLS_NOTE = (
    "You run on the user's own workstation. You can read their HubidubiOS vault "
    "(tasks, projects, notes, inbox, calendar as plain files in the working folder) "
    "with Read, Glob and Grep, use the vault tools (vault-query, gtd-next, agenda and "
    "the rest), fetch web pages with WebFetch, and hand a sub-task to a subagent. "
    "Look things up before answering. Change the vault only when the user asked.")
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


def vault_dir() -> Path | None:
    v = Path(os.environ.get("HUBIDUBIOS_VAULT") or Path.home() / "vault").expanduser()
    return v if v.is_dir() else None


def mcp_config(vault: Path) -> dict:
    """The one MCP server Claude Code may use: the vault's skills."""
    raw = os.environ.get("HUBIDUBIOS_HARNESS_MCP")
    cmd = shlex.split(raw) if raw else [shutil.which("hubidubios-mcp") or "hubidubios-mcp"]
    return {"mcpServers": {MCP_SERVER: {
        "command": cmd[0], "args": cmd[1:],
        "env": {"HUBIDUBIOS_VAULT": str(vault), "PATH": os.environ.get("PATH", "/usr/bin:/bin")},
    }}}


def tool_args(tools: bool, vault: Path | None) -> list[str]:
    """The flags that decide what Claude Code may touch for one request."""
    if not tools:
        return ["--tools", ""]
    args = ["--tools", ",".join(TOOLS)]
    allowed = list(TOOLS)
    if vault is not None:
        args += ["--mcp-config", json.dumps(mcp_config(vault))]
        allowed.append(f"mcp__{MCP_SERVER}")
    return args + ["--allowedTools", ",".join(allowed)]


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


def run(system: str, conversation: str, model: str | None, workdir: str,
        tools: bool = False) -> str:
    """One headless harness call. Raises RuntimeError with a readable reason.

    With [tools] it runs in the vault, so Read, Glob and Grep see the user's
    files and nothing else; without, in an empty folder with every tool off."""
    vault = vault_dir() if tools else None
    cmd = command() + tool_args(tools, vault)
    if tools:
        system = (TOOLS_NOTE + "\n\n" + system).strip()
    if system:
        cmd += ["--system-prompt", system]
    if model and model != MODEL_ID:
        cmd += ["--model", model]
    limit = TOOLS_TIMEOUT if tools else TIMEOUT
    try:
        with SLOTS:
            p = subprocess.run(cmd, input=conversation, capture_output=True, text=True,
                               cwd=str(vault) if vault else workdir, timeout=limit)
    except FileNotFoundError:
        raise RuntimeError(f"{cmd[0]} is not installed on this machine")
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"the harness took longer than {limit} s")
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
        # Off unless asked for: a client that knows nothing of this field (or a
        # feed, whose text comes from a stranger's page) gets text only.
        tools = req.get("hubidubios_tools") is True
        try:
            text = run(system, convo, req.get("model"), self.workdir, tools=tools)
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
