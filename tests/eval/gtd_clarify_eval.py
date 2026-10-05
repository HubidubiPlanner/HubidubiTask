#!/usr/bin/env python3
"""gtd-clarify accuracy harness (PLAN.md Phase 6).

The plan's acceptance bar: gtd-clarify proposals accepted without edit at least
70% of the time on a labelled set of 100 of the *user's own* captured items.
Below that, the step costs more than it saves.

This is the measurement mechanism. It cannot hit the number for you — that
needs your real captures and the local model — but it runs the exact
comparison against whatever endpoint and labelled set you point it at.

Usage:
    # against the real model on the workstation, with your labelled set:
    HUBIDUBIOS_MODEL_ENDPOINT=http://127.0.0.1:8080/v1/chat/completions \
        python tests/eval/gtd_clarify_eval.py path/to/labels.jsonl

    # smoke the harness with the bundled synthetic set (no model needed):
    python tests/eval/gtd_clarify_eval.py --self-test

Labelled-set format (JSONL), one item per line:
    {"text": "book the dentist", "expected": {"kind": "next_action", "context": "@calls"}}

`expected` may specify any subset of {kind, context, actionable}. A proposal
"accepts" when every specified field matches — i.e. the user would not need to
edit those fields.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ACCEPT_THRESHOLD = 0.70

# A tiny synthetic set so the harness itself is testable with a stub model.
SYNTHETIC = [
    {"text": "call the dentist", "expected": {"kind": "next_action", "actionable": True}},
    {"text": "redesign the whole website", "expected": {"kind": "project"}},
    {"text": "waiting on tax refund from the IRS", "expected": {"kind": "waiting_for"}},
    {"text": "maybe learn to sail someday", "expected": {"kind": "someday"}},
    {"text": "interesting article about sleep", "expected": {"kind": "reference"}},
]


def clarify(text: str) -> dict:
    """Run the gtd-clarify skill on one item; return its proposal."""
    from hubidubios_plugin_host.registry import discover
    from hubidubios_plugin_host.sandbox import run_skill
    from hubidubios_vault.vault import Vault

    reg = discover([REPO / "src" / "plugins"])
    plugin, spec = reg.skill("gtd", "gtd-clarify")
    vault = Vault(REPO / "tests" / "eval" / "_scratch_vault")
    vault.ensure_layout()
    os.environ.setdefault("HUBIDUBIOS_SKILL_SANDBOX", "dangerously-none")
    result = run_skill(vault, plugin, spec, {"text": text})
    return result["proposal"]


def accepts(proposal: dict, expected: dict) -> bool:
    return all(proposal.get(k) == v for k, v in expected.items())


def evaluate(items: list[dict]) -> dict:
    accepted = 0
    rows = []
    for item in items:
        proposal = clarify(item["text"])
        ok = accepts(proposal, item["expected"])
        accepted += ok
        rows.append({"text": item["text"], "expected": item["expected"],
                     "kind": proposal.get("kind"), "accepted": ok})
    rate = accepted / len(items) if items else 0.0
    return {"n": len(items), "accepted": accepted, "rate": rate,
            "passes_threshold": rate >= ACCEPT_THRESHOLD, "rows": rows}


def _run_stub_model(items) -> str:
    """A perfect-oracle stub endpoint for --self-test: echoes the expected
    label. Proves the harness plumbing end to end with no real model."""
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    by_text = {i["text"]: i["expected"] for i in items}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n))
            text = body["messages"][-1]["content"]
            exp = by_text.get(text, {})
            proposal = {"actionable": exp.get("kind") not in ("reference", "trash"),
                        "kind": exp.get("kind", "next_action"),
                        "text": text, "context": exp.get("context"),
                        "time_minutes": None, "energy": None,
                        "delegate_to": None, "reason": "stub"}
            out = json.dumps({"choices": [{"message":
                              {"content": json.dumps(proposal)}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{srv.server_address[1]}/v1/chat/completions"


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        os.environ["HUBIDUBIOS_MODEL_ENDPOINT"] = _run_stub_model(SYNTHETIC)
        items = SYNTHETIC
    else:
        if len(argv) < 2:
            print(__doc__)
            return 2
        items = [json.loads(line) for line in Path(argv[1]).read_text().splitlines() if line.strip()]

    report = evaluate(items)
    for r in report["rows"]:
        mark = "ok " if r["accepted"] else "MISS"
        print(f"  [{mark}] {r['kind']:12} <- {r['text']}")
    print(f"\naccept rate: {report['accepted']}/{report['n']} = "
          f"{report['rate']:.0%}  (threshold {ACCEPT_THRESHOLD:.0%})")
    print("PASS" if report["passes_threshold"] else "BELOW THRESHOLD")
    return 0 if report["passes_threshold"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
