"""GTD skills (PLAN.md Phase 6). Run in dev mode (no bwrap on macOS); the
deterministic skills are fully verified, and gtd-clarify is driven by a fake
model endpoint so the propose-never-commit contract is checked without a GPU.
"""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
from prodos_plugin_host.registry import discover
from prodos_plugin_host.sandbox import run_skill

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


@pytest.fixture
def gtd(monkeypatch):
    monkeypatch.setenv("PRODOS_SKILL_SANDBOX", "dangerously-none")
    return discover([REPO_PLUGINS])


def _run(gtd, vault, skill, params):
    plugin, spec = gtd.skill("gtd", skill)
    return run_skill(vault, plugin, spec, params)


# ------------------------------------------------------------ weekly-review

def test_weekly_review_finds_projects_without_next_action(gtd, vault):
    (vault.tasks_dir / "website.md").write_text(
        "---\nproject: website\n---\n\n- [ ] draft copy  <!-- id:t_a1 ctx:@computer -->\n")
    (vault.root / "projects").mkdir()
    (vault.root / "projects" / "website.md").write_text(
        "---\nid: p_1\ntitle: website\n---\n\nx\n")
    (vault.root / "projects" / "taxes.md").write_text(
        "---\nid: p_2\ntitle: taxes\n---\n\nx\n")  # no next action

    res = _run(gtd, vault, "gtd-weekly-review", {})
    stale = [p["project"] for p in res["projects_without_next_action"]]
    assert stale == ["taxes"]           # website has an action, taxes doesn't
    assert res["inbox_empty"] is True


def test_weekly_review_overdue_waiting_and_stale_someday(gtd, vault):
    (vault.tasks_dir / "waiting.md").write_text(
        "- [ ] report  <!-- id:t_w1 who:sam due:2000-01-01 -->\n")
    (vault.root / "someday").mkdir()
    (vault.root / "someday" / "someday.md").write_text(
        "- [ ] sail  <!-- id:t_s1 updated:2000-01-01T00:00:00Z -->\n")
    res = _run(gtd, vault, "gtd-weekly-review", {})
    assert res["overdue_waiting_for"][0]["who"] == "sam"
    assert res["stale_someday"][0]["text"] == "sail"


# ------------------------------------------------------------ gtd-next

def test_next_ranks_by_context_and_due(gtd, vault):
    (vault.tasks_dir / "p.md").write_text(
        "---\nproject: p\n---\n\n"
        "- [ ] overdue thing  <!-- id:t_1 ctx:@computer due:2000-01-01 -->\n"
        "- [ ] later thing  <!-- id:t_2 ctx:@computer due:2999-01-01 -->\n"
        "- [ ] errand  <!-- id:t_3 ctx:@errands -->\n")
    res = _run(gtd, vault, "gtd-next", {"context": "@computer"})
    ids = [a["id"] for a in res["actions"]]
    assert ids == ["t_1", "t_2"]        # errand filtered out; overdue first


def test_next_energy_filter(gtd, vault):
    (vault.tasks_dir / "p.md").write_text(
        "---\nproject: p\n---\n\n"
        "- [ ] heavy  <!-- id:t_h ctx:@computer energy:high -->\n"
        "- [ ] light  <!-- id:t_l ctx:@computer energy:low -->\n")
    res = _run(gtd, vault, "gtd-next", {"context": "@computer", "energy": "low"})
    # low energy on hand: the light task outranks the heavy one
    assert res["actions"][0]["id"] == "t_l"


# ------------------------------------------------------------ gtd-file

def test_file_creates_next_action_and_removes_inbox(gtd, vault):
    (vault.inbox_dir / "2026-08-31.md").write_text(
        "- 09:00 email the accountant  <!-- id:i_x1 -->\n"
        "- 09:05 keep me  <!-- id:i_x2 -->\n")
    res = _run(gtd, vault, "gtd-file", {
        "inbox_id": "i_x1", "kind": "next_action",
        "project": "admin", "context": "@computer"})
    assert res["filed"] == "next_action" and res["id"].startswith("t_")
    # inbox line gone, sibling kept
    inbox = (vault.inbox_dir / "2026-08-31.md").read_text()
    assert "i_x1" not in inbox and "i_x2" in inbox
    # next action written to the named project file
    task_file = (vault.tasks_dir / "admin.md").read_text()
    assert "email the accountant" in task_file and "ctx:@computer" in task_file


def test_file_project_and_waiting_and_trash(gtd, vault):
    (vault.inbox_dir / "2026-08-31.md").write_text(
        "- 09:00 redesign the website  <!-- id:i_p -->\n"
        "- 09:01 tax refund  <!-- id:i_w -->\n"
        "- 09:02 spam  <!-- id:i_t -->\n")
    p = _run(gtd, vault, "gtd-file", {"inbox_id": "i_p", "kind": "project"})
    assert p["id"].startswith("p_")
    assert list((vault.root / "projects").glob("*.md"))

    _run(gtd, vault, "gtd-file", {"inbox_id": "i_w", "kind": "waiting_for", "who": "irs"})
    assert "who:irs" in (vault.tasks_dir / "waiting.md").read_text()

    t = _run(gtd, vault, "gtd-file", {"inbox_id": "i_t", "kind": "trash"})
    assert t["filed"] == "trash"
    assert (vault.inbox_dir / "2026-08-31.md").read_text().strip() == ""


# ------------------------------------------------------------ gtd-clarify

class _FakeModel:
    def __init__(self, content):
        self.content = content

    def __enter__(self):
        model = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                self.rfile.read(n)
                body = json.dumps(
                    {"choices": [{"message": {"content": model.content}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        return f"http://127.0.0.1:{self.srv.server_address[1]}/v1/chat/completions"

    def __exit__(self, *a):
        self.srv.shutdown()


def test_clarify_proposes_and_does_not_commit(gtd, vault, monkeypatch):
    (vault.inbox_dir / "2026-08-31.md").write_text(
        "- 09:00 book the dentist  <!-- id:i_c1 -->\n")
    model_json = json.dumps({
        "actionable": True, "kind": "next_action", "text": "call the dentist",
        "context": "@calls", "time_minutes": 5, "energy": "low",
        "delegate_to": None, "reason": "single call"})
    with _FakeModel(model_json) as endpoint:
        monkeypatch.setenv("PRODOS_MODEL_ENDPOINT", endpoint)
        res = _run(gtd, vault, "gtd-clarify", {"inbox_id": "i_c1"})

    assert res["proposal"]["kind"] == "next_action"
    assert res["proposal"]["context"] == "@calls"
    assert res["item_text"] == "book the dentist"
    # propose-never-commit: the inbox item is untouched, nothing filed
    assert "i_c1" in (vault.inbox_dir / "2026-08-31.md").read_text()
    assert not (vault.tasks_dir).exists() or not list(vault.tasks_dir.glob("*.md"))


def test_clarify_normalizes_bad_model_output(gtd, vault, monkeypatch):
    with _FakeModel('garbage {"actionable": false, "kind": "weird", "text": "x"} tail') as ep:
        monkeypatch.setenv("PRODOS_MODEL_ENDPOINT", ep)
        res = _run(gtd, vault, "gtd-clarify", {"text": "some reference doc"})
    # invalid kind coerced; non-actionable -> reference
    assert res["proposal"]["kind"] == "reference"
