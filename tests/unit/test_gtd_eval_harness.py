"""The clarify eval harness must itself work — proven with a perfect-oracle
stub model, so the mechanism is trustworthy when run against the real model
and the user's 100-item labelled set on the workstation."""

import sys
from pathlib import Path

EVAL = Path(__file__).parents[1] / "eval"


def test_self_test_passes_with_oracle(monkeypatch, tmp_path):
    monkeypatch.setenv("HUBIDUBIOS_SKILL_SANDBOX", "dangerously-none")
    monkeypatch.setenv("HUBIDUBIOS_VAULT", str(tmp_path / "v"))
    sys.path.insert(0, str(EVAL))
    import gtd_clarify_eval as ev

    endpoint = ev._run_stub_model(ev.SYNTHETIC)
    monkeypatch.setenv("HUBIDUBIOS_MODEL_ENDPOINT", endpoint)
    report = ev.evaluate(ev.SYNTHETIC)
    assert report["rate"] == 1.0
    assert report["passes_threshold"]
    sys.path.remove(str(EVAL))


def test_accepts_matches_declared_fields_only():
    sys.path.insert(0, str(EVAL))
    import gtd_clarify_eval as ev

    assert ev.accepts({"kind": "next_action", "context": "@calls"},
                      {"kind": "next_action"})
    assert not ev.accepts({"kind": "project"}, {"kind": "next_action"})
    assert ev.accepts({"kind": "next_action", "context": "@calls"},
                      {"kind": "next_action", "context": "@calls"})
    sys.path.remove(str(EVAL))
