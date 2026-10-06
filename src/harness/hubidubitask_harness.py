#!/usr/bin/env python3
"""HubidubiTask Harness: Claude Code as an agent the apps reach by IP and port.

It runs on any computer in the home network that has Claude Code (Linux or
macOS, Python 3.9+, nothing else to install). The phone and the desktop apps
are its clients; they ask in the OpenAI chat shape, over HTTPS:

  GET  /v1/models            one model, "claude-code"; 503 when Claude Code is missing
  POST /v1/chat/completions  {"messages": [...], "hubidubitask_tools": bool,
                              "hubidubitask_items": [{"id", "type", "fields"}, ...]}
                             answered in the OpenAI shape plus
                             "hubidubitask_actions": [{"op": ...}, ...]

Every answer is one `claude -p` run, restricted: nothing that runs commands or
edits files, no user or project settings, and anything outside the allowed list
refused, since no one is there to approve it. With tools, Claude Code may Read,
Glob and Grep a Markdown copy of the items the app sent, fetch web pages, hand
work to subagents, use the items as MCP tools, and use any MCP servers listed
in this computer's mcp.json. The item tools change nothing here: add_task,
capture and mark_done come back as actions, which the app applies to its own
data. Without tools (feeds: their text is a stranger's web page) every tool is
off.

Only clients on private networks are answered. Configuration lives in
~/.config/hubidubitask-harness (or --config DIR):

  harness.env  KEY=VALUE lines for Claude Code, e.g. to use a local model
  mcp.json     {"mcpServers": {...}}, more MCP servers, in Claude Code's format
  tls/         the self-signed certificate, made with openssl on first start
"""

import argparse
import datetime
import hashlib
import ipaddress
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1.0"
MODEL = "claude-code"
DEFAULT_PORT = 8765
MCP_NAME = "hubidubitask"
# Task is Claude Code's subagent tool.
TOOLS = ["Read", "Glob", "Grep", "WebFetch", "Task"]
MAX_BODY = 32 * 1024 * 1024
TOOLS_NOTE = (
    "You run on a computer in the user's home network. The working folder holds a "
    "read-only Markdown copy of their HubidubiTask items (projects/, inbox.md, calendar.md, "
    "notes/), which you can Read, Glob and Grep. The hubidubitask tools search, list and "
    "change their items; change them only when the user asked. You can also fetch web "
    "pages and hand a sub-task to a subagent. Look things up before answering."
)


# ---------------------------------------------------------------- the items

def slug(title):
    """The same slug the apps give a project."""
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")


class Items:
    """The items one request carried, as the tools and the Markdown copy see them."""

    def __init__(self, records):
        self.records = [r for r in records if isinstance(r, dict) and r.get("id")
                        and r.get("type") != "feed" and not r.get("deleted")]
        for r in self.records:
            r["fields"] = {k: v for k, v in (r.get("fields") or {}).items() if v is not None}
        self.by_id = {r["id"]: r for r in self.records}

    @staticmethod
    def f(r, key):
        return (r.get("fields") or {}).get(key) or ""

    def title(self, r):
        return self.f(r, "title") or self.f(r, "text") or self.f(r, "summary") or r["id"]

    @staticmethod
    def is_task(r):
        return r["type"] in ("next_action", "inbox_item")

    def is_open(self, r):
        return self.f(r, "done") != "true"

    def project_of(self, r):
        p = self.by_id.get(self.f(r, "parent"))
        for _ in range(8):
            if p is None or p["type"] == "project":
                return p
            p = self.by_id.get(self.f(p, "parent"))
        return None

    def line(self, r):
        kinds = {"next_action": "task" if self.is_open(r) else "done task", "inbox_item": "inbox"}
        parts = ["[%s] %s" % (kinds.get(r["type"], r["type"]), self.title(r))]
        if self.f(r, "due"):
            parts.append("due " + self.f(r, "due"))
        if self.f(r, "dtstart"):
            parts.append("at " + self.f(r, "dtstart"))
        p = self.project_of(r)
        if p is not None and r["type"] != "project":
            parts.append("in " + self.title(p))
        if self.f(r, "body"):
            parts.append("— " + self.f(r, "body").replace("\n", " ")[:200])
        parts.append("(id %s)" % r["id"])
        return " · ".join(parts)

    def projects(self):
        return sorted((r for r in self.records if r["type"] == "project"), key=lambda p: self.title(p).lower())

    def in_project(self, project):
        inside = [r for r in self.records if r["type"] != "project"
                  and (self.project_of(r) or {}).get("id") == project["id"]]
        return sorted(inside, key=lambda r: (r["type"] == "note", not self.is_open(r), self.f(r, "due") or "9999"))

    def find_project(self, name):
        n = name.strip().lower()
        ps = self.projects()
        return next((p for p in ps if self.title(p).lower() == n or p["id"] == name.strip()), None) \
            or next((p for p in ps if n and n in self.title(p).lower()), None)

    def loose(self):
        return [r for r in self.records if self.is_task(r) and self.is_open(r) and self.project_of(r) is None]

    def search(self, query, limit):
        words = [w for w in query.lower().split() if w]
        hits = [r for r in self.records if all(
            any(w in str(v).lower() for v in r["fields"].values()) for w in words)]
        hits.sort(key=lambda r: (not (self.is_task(r) and self.is_open(r)), self.f(r, "due") or "9999"))
        return hits[:limit]

    def upcoming(self, days, today):
        until = (today + datetime.timedelta(days=days)).isoformat()
        out = [r for r in self.records if
               (self.is_task(r) and self.is_open(r) and self.f(r, "due") and self.f(r, "due") <= until) or
               (r["type"] == "event" and today.isoformat() <= self.f(r, "dtstart")[:10] <= until)]
        return sorted(out, key=lambda r: self.f(r, "due") or self.f(r, "dtstart"))


def write_view(items, folder, today):
    """The items as plain Markdown, so Read, Glob and Grep have files to work on."""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)

    def md(r):
        box = ("- [ ] " if items.is_open(r) else "- [x] ") if items.is_task(r) else "- "
        due = " (due %s)" % items.f(r, "due") if items.f(r, "due") else ""
        body = "\n  " + items.f(r, "body").replace("\n", "\n  ") if items.f(r, "body") else ""
        return "%s%s%s  <!-- id:%s -->%s" % (box, items.title(r), due, r["id"], body)

    (folder / "README.md").write_text(
        "# The user's HubidubiTask items (read-only copy, %s)\n\n"
        "- `projects/<name>.md`: each project's tasks and notes\n"
        "- `inbox.md`: captures and tasks in no project\n"
        "- `calendar.md`: events from today on\n"
        "- `notes/`: notes in no project\n\n"
        "This copy is made for one question. To change anything, use the hubidubitask\n"
        "tools (add_task, capture, mark_done).\n" % today, encoding="utf-8")

    pdir = folder / "projects"
    pdir.mkdir(exist_ok=True)
    for p in items.projects():
        inside = items.in_project(p)
        text = "# %s\n\n" % items.title(p)
        if items.f(p, "body"):
            text += items.f(p, "body") + "\n\n"
        text += "".join(md(r) + "\n" for r in inside if r["type"] != "note")
        notes = [r for r in inside if r["type"] == "note"]
        if notes:
            text += "\n## Notes\n\n" + "".join(md(r) + "\n" for r in notes)
        (pdir / ((slug(items.title(p)) or p["id"]) + ".md")).write_text(text, encoding="utf-8")

    (folder / "inbox.md").write_text(
        "# Inbox and loose tasks\n\n" + "\n".join(md(r) for r in items.loose()) + "\n", encoding="utf-8")

    events = sorted((r for r in items.records if r["type"] == "event"
                     and items.f(r, "dtstart") >= today.isoformat()), key=lambda r: items.f(r, "dtstart"))
    (folder / "calendar.md").write_text("# Calendar from %s\n\n" % today + "\n".join(
        "- %s %s%s" % (items.f(e, "dtstart"), items.f(e, "summary"),
                       " @ " + items.f(e, "location") if items.f(e, "location") else "")
        for e in events) + "\n", encoding="utf-8")

    ndir = folder / "notes"
    ndir.mkdir(exist_ok=True)
    for n in items.records:
        if n["type"] == "note" and items.project_of(n) is None and not items.f(n, "parent"):
            (ndir / ((slug(items.title(n)) or n["id"]) + ".md")).write_text(
                "# %s\n\n%s\n" % (items.title(n), items.f(n, "body")), encoding="utf-8")


# ------------------------------------------------- the item tools over MCP

TOOL_SPECS = [
    ("search", "Find tasks, notes, events and projects whose text matches all the words given.",
     {"query": ("string", "Words to look for"), "limit": ("integer", "At most this many, 1-50")}, ["query"]),
    ("upcoming", "Open tasks due within the next days (overdue included) and events in that time, soonest first.",
     {"days": ("integer", "How many days ahead, default 7")}, []),
    ("projects", "Every project with its number of open tasks.", {}, []),
    ("project", "One project's tasks and notes.", {"name": ("string", "Project name or id")}, ["name"]),
    ("inbox", "Captures and open tasks that are in no project.", {}, []),
    ("add_task", "Add a task, into a project or (without one) the inbox.",
     {"text": ("string", "What to do, starting with a verb"),
      "project": ("string", "Project name; leave out for the inbox"),
      "due": ("string", "Due date, YYYY-MM-DD")}, ["text"]),
    ("capture", "Put a thought in the inbox, exactly as given.", {"text": ("string", "The thought")}, ["text"]),
    ("mark_done", "Mark a task done.", {"id": ("string", "The task's id, as the other tools show it")}, ["id"]),
]
MCP_INSTRUCTIONS = (
    "The user's HubidubiTask items: tasks, projects, notes, inbox and calendar. Look things up "
    "before answering about them. add_task, capture and mark_done change the user's items; use "
    "them only when the user asked for that change.")


def tool_list():
    return [{"name": n, "description": d, "inputSchema": {
        "type": "object", "additionalProperties": False, "required": req,
        "properties": {k: {"type": t, "description": desc} for k, (t, desc) in props.items()}}}
        for n, d, props, req in TOOL_SPECS]


def call_tool(items, name, args, actions, today):
    """One tool call: (text, is_error). Changes are recorded in [actions], not made."""
    def lines(rs, empty):
        return ("\n".join(items.line(r) for r in rs) if rs else empty), False

    def num(key, default, lo, hi):
        try:
            return max(lo, min(hi, int(args.get(key, default))))
        except (TypeError, ValueError):
            return default

    if name == "search":
        q = str(args.get("query") or "").strip()
        if not q:
            return "search needs a query", True
        return lines(items.search(q, num("limit", 20, 1, 50)), 'Nothing matches "%s".' % q)
    if name == "upcoming":
        return lines(items.upcoming(num("days", 7, 0, 366), today), "Nothing due or scheduled.")
    if name == "projects":
        ps = items.projects()
        return ("\n".join("%s · %d open (id %s)" % (items.title(p), sum(
            1 for r in items.in_project(p) if items.is_task(r) and items.is_open(r)), p["id"]) for p in ps)
            or "No projects."), False
    if name == "project":
        p = items.find_project(str(args.get("name") or ""))
        if p is None:
            return 'No project called "%s".' % args.get("name"), True
        return lines(items.in_project(p), "%s is empty." % items.title(p))
    if name == "inbox":
        return lines(items.loose(), "The inbox is empty.")
    if name == "add_task":
        text = str(args.get("text") or "").strip()
        if not text:
            return "add_task needs text", True
        due = str(args.get("due") or "").strip() or None
        if due:
            try:
                datetime.date.fromisoformat(due)
            except ValueError:
                return "due must be YYYY-MM-DD", True
        project = None
        if str(args.get("project") or "").strip():
            project = items.find_project(str(args["project"]))
            if project is None:
                return 'No project called "%s".' % args["project"], True
        actions.append({"op": "add_task", "text": text, "project": project and project["id"], "due": due})
        record = {"id": "new-%d" % len(actions), "type": "next_action",
                  "fields": {"text": text, "due": due or "", "parent": project["id"] if project else ""}}
        items.records.append(record)
        items.by_id[record["id"]] = record
        return "Added: " + items.line(record), False
    if name == "capture":
        text = str(args.get("text") or "").strip()
        if not text:
            return "capture needs text", True
        actions.append({"op": "capture", "text": text})
        return "Captured: " + text, False
    if name == "mark_done":
        r = items.by_id.get(str(args.get("id") or ""))
        if r is None or not items.is_task(r) or not items.is_open(r) or r["id"].startswith("new-"):
            return "No open task with id %s." % args.get("id"), True
        actions.append({"op": "mark_done", "id": r["id"]})
        r["fields"]["done"] = "true"
        return "Done: " + items.title(r), False
    return "No tool named %s." % name, True


def serve_mcp(root):
    """The item tools as an MCP server on stdin/stdout, for one Claude Code run.

    Claude Code starts it with `--mcp-items ROOT`. It reads ROOT/items.json and
    appends every change it is asked for to ROOT/actions.jsonl.
    """
    root = Path(root)
    items = Items(json.loads((root / "items.json").read_text(encoding="utf-8")))
    today = datetime.date.fromisoformat((root / "today").read_text().strip())
    out = sys.stdout

    def send(msg):
        out.write(json.dumps(msg) + "\n")
        out.flush()

    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            continue
        if "id" not in msg:
            continue  # a notification
        method, params = msg.get("method"), msg.get("params") or {}
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion") or "2025-06-18",
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": MCP_NAME, "version": VERSION},
                      "instructions": MCP_INSTRUCTIONS}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": tool_list()}
        elif method == "tools/call":
            actions = []
            text, error = call_tool(items, params.get("name"), params.get("arguments") or {}, actions, today)
            if actions:
                with open(root / "actions.jsonl", "a", encoding="utf-8") as fh:
                    for a in actions:
                        fh.write(json.dumps(a) + "\n")
            result = {"content": [{"type": "text", "text": text}], "isError": error}
        else:
            send({"jsonrpc": "2.0", "id": msg["id"],
                  "error": {"code": -32601, "message": "unknown method %s" % method}})
            continue
        send({"jsonrpc": "2.0", "id": msg["id"], "result": result})


# ------------------------------------------------------ one Claude Code run

class HarnessError(Exception):
    """A failure with a sentence a person can act on."""


class Harness:
    def __init__(self, config_dir, claude=None, script=None):
        self.config_dir = Path(config_dir)
        self.claude = claude
        self.script = script or os.path.abspath(__file__)
        self.slots = threading.Semaphore(2)

    def command(self):
        """The `claude` command, looked for where its installers put it; services get a thin PATH."""
        if self.claude:
            return self.claude
        home = os.path.expanduser("~")
        dirs = os.environ.get("PATH", "").split(os.pathsep) + [
            home + "/.local/bin", home + "/.claude/local", home + "/.npm-global/bin",
            "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"]
        for d in dirs:
            p = os.path.join(d, "claude")
            if d and os.path.isfile(p) and os.access(p, os.X_OK):
                return p
        return None

    def env(self):
        """The service's environment plus harness.env, e.g. a local model's address."""
        env = dict(os.environ)
        f = self.config_dir / "harness.env"
        if f.exists():
            for line in f.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip().strip('"').strip("'")
        return env

    def user_mcp(self):
        """MCP servers this computer's owner listed in mcp.json, in Claude Code's format."""
        f = self.config_dir / "mcp.json"
        if not f.exists():
            return {}
        try:
            servers = json.loads(f.read_text(encoding="utf-8")).get("mcpServers") or {}
        except ValueError as e:
            raise HarnessError("mcp.json on the harness computer is not valid JSON: %s" % e)
        servers.pop(MCP_NAME, None)
        return servers

    def ask(self, messages, tools, records=None, today=None):
        """One question: (answer, actions)."""
        claude = self.command()
        if not claude:
            raise HarnessError("Claude Code is not installed on the harness computer.")
        today = today or datetime.date.today()
        system = "\n\n".join(m.get("content") or "" for m in messages if m.get("role") == "system").strip()
        turns = [m for m in messages if m.get("role") != "system"]
        prompt = turns[0].get("content") or "" if len(turns) == 1 else "\n\n".join(
            ("Assistant: " if m.get("role") == "assistant" else "User: ") + (m.get("content") or "")
            for m in turns)

        root = Path(tempfile.mkdtemp(prefix="hubidubitask-harness-"))
        try:
            work = root / "work"
            work.mkdir()
            cmd = [claude, "-p", "--output-format", "json", "--no-session-persistence",
                   "--restricted", "--strict-mcp-config", "--permission-prompts", "none"]
            if tools:
                items = Items(records or [])
                write_view(items, work, today)
                (root / "items.json").write_text(json.dumps(items.records), encoding="utf-8")
                (root / "today").write_text(today.isoformat())
                servers = {MCP_NAME: {"type": "stdio", "command": sys.executable,
                                      "args": [self.script, "--mcp-items", str(root)]}}
                servers.update(self.user_mcp())
                cmd += ["--tools", ",".join(TOOLS),
                        "--mcp-config", json.dumps({"mcpServers": servers}),
                        "--allowedTools", ",".join(TOOLS + ["mcp__" + n for n in servers])]
                system = (TOOLS_NOTE + "\n\n" + system).strip()
            else:
                cmd += ["--tools", ""]
            if system:
                cmd += ["--system-prompt", system]

            limit = 540 if tools else 300
            if not self.slots.acquire(timeout=limit):
                raise HarnessError("The harness is busy; try again.")
            try:
                p = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=str(work),
                                   env=self.env(), timeout=limit)
            except subprocess.TimeoutExpired:
                raise HarnessError("Claude Code took longer than %d s." % limit)
            finally:
                self.slots.release()
            try:
                out = json.loads(p.stdout.strip())
            except ValueError:
                raise HarnessError((p.stderr.strip() or p.stdout.strip())[-300:]
                                   or "Claude Code exited with %d" % p.returncode)
            if out.get("is_error"):
                raise HarnessError(out.get("result") or "Claude Code failed")
            actions = []
            log = root / "actions.jsonl"
            if log.exists():
                actions = [json.loads(l) for l in log.read_text(encoding="utf-8").splitlines() if l.strip()]
            return out.get("result") or "", actions
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ------------------------------------------------------------ the server

SHARED = ipaddress.ip_network("100.64.0.0/10")  # carrier-grade NAT, Tailscale


def allowed(address):
    """Only the home network: private, link-local and loopback addresses."""
    try:
        ip = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    if getattr(ip, "ipv4_mapped", None):
        ip = ip.ipv4_mapped
    return ip.is_private or ip.is_loopback or ip.is_link_local or (ip.version == 4 and ip in SHARED)


def make_handler(harness, log):
    class Handler(BaseHTTPRequestHandler):
        server_version = "hubidubitask-harness/" + VERSION
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def send_json(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def error(self, code, message):
            self.send_json(code, {"error": {"message": message}})

        def gate(self):
            if not allowed(self.client_address[0]):
                self.error(403, "this harness answers only its home network")
                return False
            return True

        def do_GET(self):
            if not self.gate():
                return
            if self.path.rstrip("/") != "/v1/models":
                return self.error(404, "no such path")
            if not harness.command():
                return self.error(503, "Claude Code is not installed on the harness computer.")
            self.send_json(200, {"object": "list", "data": [
                {"id": MODEL, "object": "model", "owned_by": "hubidubitask-harness"}]})

        def do_POST(self):
            if not self.gate():
                return
            if self.path.rstrip("/") != "/v1/chat/completions":
                return self.error(404, "no such path")
            size = int(self.headers.get("Content-Length") or 0)
            if size <= 0 or size > MAX_BODY:
                return self.error(413 if size > 0 else 400, "the request is empty or too large")
            try:
                req = json.loads(self.rfile.read(size))
                messages = [m for m in req["messages"] if isinstance(m, dict)]
            except (ValueError, KeyError, TypeError):
                return self.error(400, "expected {\"messages\": [...]}")
            tools = bool(req.get("hubidubitask_tools", False))
            records = req.get("hubidubitask_items") or []
            started = time.time()
            try:
                answer, actions = harness.ask(messages, tools, records if tools else None)
            except HarnessError as e:
                log("%s tools=%s items=%d failed after %.0fs: %s" % (
                    self.client_address[0], tools, len(records), time.time() - started, e))
                return self.error(502, str(e))
            log("%s tools=%s items=%d answered in %.0fs, %d change(s)" % (
                self.client_address[0], tools, len(records), time.time() - started, len(actions)))
            self.send_json(200, {
                "id": "chatcmpl-%d" % int(started * 1000), "object": "chat.completion",
                "created": int(started), "model": MODEL,
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": answer}}],
                "hubidubitask_actions": actions})

    return Handler


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A client that drops the line or speaks plain HTTP to the TLS port is
        # not worth a traceback.
        if isinstance(sys.exc_info()[1], (ssl.SSLError, ConnectionError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def certificate(config_dir):
    """The server's self-signed certificate, made once with openssl: (cert, key, sha256)."""
    tls = Path(config_dir) / "tls"
    cert, key = tls / "cert.pem", tls / "key.pem"
    if not (cert.exists() and key.exists()):
        if not shutil.which("openssl"):
            sys.exit("openssl is needed once, to make the harness's certificate.")
        tls.mkdir(parents=True, exist_ok=True)
        os.chmod(tls, 0o700)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
                        "-subj", "/CN=hubidubitask-harness", "-keyout", str(key), "-out", str(cert)],
                       check=True, capture_output=True)
        os.chmod(key, 0o600)
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return cert, key, hashlib.sha256(der).hexdigest()


def serve(host, port, config_dir, tls=True, claude=None, ready=None):
    config_dir = Path(config_dir)
    config_dir.mkdir(parents=True, exist_ok=True)
    harness = Harness(config_dir, claude=claude)

    def log(line):
        print(time.strftime("%Y-%m-%d %H:%M:%S ") + line, file=sys.stderr, flush=True)

    httpd = Server((host, port), make_handler(harness, log))
    fingerprint = None
    if tls:
        cert, key, fingerprint = certificate(config_dir)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(str(cert), str(key))
        # The handshake happens in the request's own thread, so a slow or
        # wrong client never holds up the others.
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True, do_handshake_on_connect=False)
    log("HubidubiTask Harness on %s://%s:%d, Claude Code: %s" % (
        "https" if tls else "http", host, httpd.server_address[1], harness.command() or "NOT FOUND"))
    if fingerprint:
        log("certificate sha256 " + ":".join(fingerprint[i:i + 2] for i in range(0, 64, 2)))
    if ready:
        ready(httpd)
    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()


def main(argv=None):
    ap = argparse.ArgumentParser(description="Claude Code as an agent for the HubidubiTask apps.")
    ap.add_argument("--host", default="0.0.0.0", help="address to listen on (default: every one)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="port (default %d)" % DEFAULT_PORT)
    ap.add_argument("--config", default=os.path.join(
        os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "hubidubitask-harness"),
        help="folder with harness.env, mcp.json and the certificate")
    ap.add_argument("--no-tls", action="store_true", help="plain HTTP, for tests only")
    ap.add_argument("--mcp-items", metavar="ROOT", help=argparse.SUPPRESS)
    a = ap.parse_args(argv)
    if a.mcp_items:
        return serve_mcp(a.mcp_items)
    serve(a.host, a.port, a.config, tls=not a.no_tls)


if __name__ == "__main__":
    main()
