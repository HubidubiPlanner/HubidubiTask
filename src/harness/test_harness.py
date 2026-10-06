"""Tests for the HubidubiTask Harness. Run: python3 -m unittest -v (in src/harness)."""

import datetime
import http.client
import json
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import textwrap
import threading
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hubidubitask_harness as h  # noqa: E402

TODAY = datetime.date(2026, 10, 6)
RECORDS = [
    {"id": "p_web", "type": "project", "fields": {"title": "Website"}},
    {"id": "p_home", "type": "project", "fields": {"title": "Dom"}},
    {"id": "t_host", "type": "next_action", "fields": {"text": "Pick a hosting provider",
                                                       "parent": "p_web", "due": "2026-10-06"}},
    {"id": "t_old", "type": "next_action", "fields": {"text": "Buy a domain", "parent": "p_web", "done": "true"}},
    {"id": "n_spec", "type": "note", "fields": {"title": "Spec", "body": "Static, one page", "parent": "p_web"}},
    {"id": "i_milk", "type": "inbox_item", "fields": {"text": "milk", "date": "2026-10-05"}},
    {"id": "e_dentist", "type": "event", "fields": {"summary": "Dentist", "dtstart": "2026-10-08T09:00",
                                                    "location": "Rynek 1"}},
    {"id": "f_feed", "type": "feed", "fields": {"url": "https://example.com"}},
    {"id": "t_gone", "type": "next_action", "deleted": True, "fields": {}},
]

# Stands in for `claude`: writes down how it was started, and when it has MCP
# servers it does what Claude Code would: starts ours and calls a tool.
FAKE_CLAUDE = textwrap.dedent('''\
    #!%s
    import json, os, subprocess, sys
    args = sys.argv[1:]
    prompt = sys.stdin.read()
    log = {"args": args, "prompt": prompt, "cwd": os.getcwd(),
           "files": sorted(str(p) for p in __import__("pathlib").Path(".").rglob("*.md")),
           "env": os.environ.get("HARNESS_TEST_VAR")}
    mode = os.environ.get("FAKE_MODE", "ok")
    if "--mcp-config" in args:
        server = json.loads(args[args.index("--mcp-config") + 1])["mcpServers"]["hubidubitask"]
        p = subprocess.Popen([server["command"]] + server["args"], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, text=True)
        calls = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                 {"jsonrpc": "2.0", "method": "notifications/initialized"},
                 {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                 {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": "add_task", "arguments": {"text": "Compare prices", "project": "website"}}},
                 {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                  "params": {"name": "mark_done", "arguments": {"id": "t_host"}}}]
        out, _ = p.communicate("".join(json.dumps(c) + "\\n" for c in calls), timeout=30)
        log["mcp"] = [json.loads(l) for l in out.splitlines()]
    with open(os.environ["FAKE_LOG"], "w") as fh:
        json.dump(log, fh)
    if mode == "error":
        print(json.dumps({"is_error": True, "result": "model unavailable"}))
    elif mode == "garbage":
        print("not json"); sys.exit(3)
    else:
        print(json.dumps({"is_error": False, "result": "One task is due today."}))
''') % sys.executable


class ViewAndTools(unittest.TestCase):
    def setUp(self):
        self.items = h.Items(json.loads(json.dumps(RECORDS)))

    def test_feeds_and_deleted_records_are_left_out(self):
        self.assertNotIn("f_feed", self.items.by_id)
        self.assertNotIn("t_gone", self.items.by_id)

    def test_the_markdown_copy(self):
        d = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, d)
        h.write_view(self.items, d, TODAY)
        web = (d / "projects" / "website.md").read_text()
        self.assertIn("- [ ] Pick a hosting provider (due 2026-10-06)  <!-- id:t_host -->", web)
        self.assertIn("- [x] Buy a domain", web)
        self.assertIn("## Notes", web)
        self.assertIn("milk", (d / "inbox.md").read_text())
        self.assertIn("2026-10-08T09:00 Dentist @ Rynek 1", (d / "calendar.md").read_text())

    def test_reading_tools(self):
        def call(tool, **a):
            return h.call_tool(self.items, tool, a, [], TODAY)
        self.assertIn("Pick a hosting provider", call("search", query="hosting")[0])
        self.assertIn("Dentist", call("upcoming", days=3)[0])
        self.assertEqual("Dom · 0 open (id p_home)\nWebsite · 1 open (id p_web)", call("projects")[0])
        self.assertIn("Spec", call("project", name="web")[0])
        self.assertIn("milk", call("inbox")[0])
        self.assertTrue(call("project", name="nowhere")[1])

    def test_changes_become_actions_and_nothing_else(self):
        actions = []
        call = lambda tool, **a: h.call_tool(self.items, tool, a, actions, TODAY)  # noqa: E731
        self.assertFalse(call("add_task", text="Compare prices", project="Website", due="2026-10-09")[1])
        self.assertFalse(call("capture", text="call mum")[1])
        self.assertFalse(call("mark_done", id="t_host")[1])
        self.assertTrue(call("mark_done", id="t_host")[1], "already done")
        self.assertTrue(call("mark_done", id="n_spec")[1], "a note is not a task")
        self.assertTrue(call("add_task", text="x", due="tomorrow")[1])
        self.assertTrue(call("add_task", text="x", project="Nowhere")[1])
        self.assertEqual([{"op": "add_task", "text": "Compare prices", "project": "p_web", "due": "2026-10-09"},
                          {"op": "capture", "text": "call mum"},
                          {"op": "mark_done", "id": "t_host"}], actions)
        # What was added is found by the next lookup in the same run.
        self.assertIn("Compare prices", call("project", name="Website")[0])

    def test_only_the_home_network_is_answered(self):
        for ok in ["192.168.1.63", "10.0.0.2", "172.20.1.1", "127.0.0.1", "::1", "::ffff:192.168.1.63",
                   "fe80::1%en0", "100.101.1.2", "fd12::1"]:
            self.assertTrue(h.allowed(ok), ok)
        for bad in ["8.8.8.8", "2a00:1450::1", "::ffff:8.8.8.8", "nonsense"]:
            self.assertFalse(h.allowed(bad), bad)


class Server(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.claude = self.dir / "claude"
        self.claude.write_text(FAKE_CLAUDE)
        self.claude.chmod(0o755)
        self.log = self.dir / "fake.json"
        self.config = self.dir / "config"
        self.config.mkdir()
        (self.config / "harness.env").write_text(
            "# a comment\nFAKE_LOG=%s\nHARNESS_TEST_VAR=\"from harness.env\"\n" % self.log)
        os.environ.pop("FAKE_MODE", None)

    def start(self, tls=False, claude=True):
        got = {}
        ready = threading.Event()

        def run():
            h.serve("127.0.0.1", 0, self.config, tls=tls, claude=str(self.claude) if claude else None,
                    ready=lambda s: (got.update(s=s), ready.set()))
        threading.Thread(target=run, daemon=True).start()
        self.assertTrue(ready.wait(30))
        self.addCleanup(got["s"].shutdown)
        port = got["s"].server_address[1]
        if tls:
            return lambda: http.client.HTTPSConnection("127.0.0.1", port, timeout=60,
                                                       context=ssl._create_unverified_context())
        return lambda: http.client.HTTPConnection("127.0.0.1", port, timeout=60)

    def request(self, conn, method, path, body=None):
        c = conn()
        c.request(method, path, body=json.dumps(body) if body is not None else None,
                  headers={"Content-Type": "application/json"})
        r = c.getresponse()
        return r.status, json.loads(r.read() or b"{}")

    def ask(self, conn, tools, items=None, messages=None):
        return self.request(conn, "POST", "/v1/chat/completions", {
            "model": "claude-code",
            "messages": messages or [{"role": "system", "content": "Be brief."},
                                     {"role": "user", "content": "What is due?"}],
            "hubidubitask_tools": tools, "hubidubitask_items": items or []})

    def test_models(self):
        conn = self.start()
        self.assertEqual((200, "claude-code"), (lambda s, b: (s, b["data"][0]["id"]))(
            *self.request(conn, "GET", "/v1/models")))

    def test_models_says_when_claude_code_is_missing(self):
        conn = self.start(claude=False)
        os.environ["PATH"], path = "/nonexistent", os.environ["PATH"]
        home, os.environ["HOME"] = os.environ["HOME"], str(self.dir)
        try:
            status, body = self.request(conn, "GET", "/v1/models")
        finally:
            os.environ["PATH"], os.environ["HOME"] = path, home
        if Path("/usr/bin/claude").exists() or Path("/usr/local/bin/claude").exists() \
                or Path("/opt/homebrew/bin/claude").exists():
            self.skipTest("a system-wide claude is installed")
        self.assertEqual(503, status)
        self.assertIn("not installed", body["error"]["message"])

    def test_without_tools_every_tool_is_off_and_no_items_are_written(self):
        conn = self.start()
        status, body = self.ask(conn, tools=False, items=RECORDS)
        self.assertEqual(200, status)
        self.assertEqual("One task is due today.", body["choices"][0]["message"]["content"])
        self.assertEqual([], body["hubidubitask_actions"])
        log = json.loads(self.log.read_text())
        args = log["args"]
        self.assertEqual("", args[args.index("--tools") + 1])
        for flag in ["--restricted", "--strict-mcp-config", "--no-session-persistence"]:
            self.assertIn(flag, args)
        self.assertNotIn("--mcp-config", args)
        self.assertEqual([], log["files"])
        self.assertEqual("Be brief.", args[args.index("--system-prompt") + 1])
        self.assertEqual("What is due?", log["prompt"])
        self.assertEqual("from harness.env", log["env"])

    def test_with_tools_the_items_are_there_and_changes_come_back(self):
        (self.config / "mcp.json").write_text(json.dumps({"mcpServers": {
            "notes": {"type": "stdio", "command": "true"},
            "hubidubitask": {"type": "stdio", "command": "evil"}}}))
        conn = self.start()
        status, body = self.ask(conn, tools=True, items=RECORDS)
        self.assertEqual(200, status, body)
        log = json.loads(self.log.read_text())
        args = log["args"]
        self.assertEqual("Read,Glob,Grep,WebFetch,Task", args[args.index("--tools") + 1])
        self.assertEqual("Read,Glob,Grep,WebFetch,Task,mcp__hubidubitask,mcp__notes",
                         args[args.index("--allowedTools") + 1])
        servers = json.loads(args[args.index("--mcp-config") + 1])["mcpServers"]
        self.assertEqual(sys.executable, servers["hubidubitask"]["command"], "ours is not replaced")
        self.assertIn("projects/website.md", log["files"])
        self.assertIn("inbox.md", log["files"])
        self.assertTrue(args[args.index("--system-prompt") + 1].startswith("You run on a computer"))
        tools = [t["name"] for t in log["mcp"][1]["result"]["tools"]]
        self.assertEqual(["search", "upcoming", "projects", "project", "inbox", "add_task", "capture",
                          "mark_done"], tools)
        self.assertEqual([{"op": "add_task", "text": "Compare prices", "project": "p_web", "due": None},
                          {"op": "mark_done", "id": "t_host"}], body["hubidubitask_actions"])
        self.assertFalse(Path(log["cwd"]).exists(), "the copy is gone after the answer")

    def test_a_conversation_is_sent_as_turns(self):
        conn = self.start()
        self.ask(conn, tools=False, messages=[{"role": "user", "content": "Hi"},
                                              {"role": "assistant", "content": "Hello"},
                                              {"role": "user", "content": "What next?"}])
        self.assertEqual("User: Hi\n\nAssistant: Hello\n\nUser: What next?",
                         json.loads(self.log.read_text())["prompt"])

    def test_failures_reach_the_app_as_a_sentence(self):
        conn = self.start()
        os.environ["FAKE_MODE"] = "error"
        status, body = self.ask(conn, tools=False)
        self.assertEqual((502, "model unavailable"), (status, body["error"]["message"]))
        os.environ["FAKE_MODE"] = "garbage"
        status, body = self.ask(conn, tools=False)
        self.assertEqual(502, status)
        self.assertIn("not json", body["error"]["message"])

    def test_bad_requests(self):
        conn = self.start()
        self.assertEqual(400, self.request(conn, "POST", "/v1/chat/completions", {"nope": 1})[0])
        self.assertEqual(404, self.request(conn, "GET", "/v1/other")[0])

    @unittest.skipUnless(shutil.which("openssl"), "openssl is not installed")
    def test_https_with_a_certificate_made_once(self):
        conn = self.start(tls=True)
        self.assertEqual(200, self.request(conn, "GET", "/v1/models")[0])
        first = (self.config / "tls" / "cert.pem").read_text()
        self.assertEqual(first, h.certificate(self.config)[0].read_text(), "kept, not remade")
        self.assertEqual(0o600, (self.config / "tls" / "key.pem").stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
