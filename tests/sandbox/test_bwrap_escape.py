"""bwrap escape suite (PLAN.md Phase 3): a skill that reads ~/.ssh, one that
opens an outbound connection to a public IP, one that forks a background
process. All three must fail.

Runs only where bwrap is present (Linux). On the macOS dev machine these are
skipped; CI runs them in a Linux container (see tests/sandbox/run-in-docker.sh).
"""

import shutil

import pytest
from hubidubios_plugin_host.sandbox import run_skill

pytestmark = pytest.mark.skipif(
    shutil.which("bwrap") is None, reason="bwrap not available (Linux only)")


def _run(vault, make_skill, name, body, sandbox=None):
    plugin, spec = make_skill(name, body, sandbox)
    return run_skill(vault, plugin, spec, {})


def test_cannot_read_ssh_keys(vault, make_skill, tmp_path, monkeypatch):
    # Plant a secret in the fake HOME the skill would see if the sandbox leaked.
    ssh = tmp_path / "home" / ".ssh"
    ssh.mkdir(parents=True)
    (ssh / "id_ed25519").write_text("SECRET-KEY-MATERIAL")
    monkeypatch.setenv("HOME", str(tmp_path / "home"))

    result = _run(vault, make_skill, "read-ssh", """
        import os
        found = []
        for base in ('/root/.ssh', os.path.expanduser('~/.ssh')):
            try:
                found += os.listdir(base)
            except OSError:
                pass
        print(json.dumps({"leaked": found}))
        """)
    assert result["leaked"] == []  # ~/.ssh not bound into the sandbox


def test_cannot_open_outbound_connection(vault, make_skill):
    result = _run(vault, make_skill, "net", """
        import socket
        try:
            s = socket.create_connection(("1.1.1.1", 53), timeout=3)
            s.close()
            print(json.dumps({"connected": True}))
        except OSError as e:
            print(json.dumps({"connected": False, "err": str(e)}))
        """)
    assert result["connected"] is False  # --unshare-net: no route


def test_cannot_fork_surviving_background_process(vault, make_skill, tmp_path):
    marker = tmp_path / "survivor.txt"
    _run(vault, make_skill, "fork", f"""
        import os, sys, time
        pid = os.fork()
        if pid == 0:
            time.sleep(30)
            open({str(marker)!r}, "w").write("alive")
            os._exit(0)
        print(json.dumps({{"forked_pid": pid}}))
        sys.exit(0)
        """)
    # --die-with-parent + pid namespace: the child cannot outlive the skill
    # and cannot reach a path outside its binds.
    import time
    time.sleep(1)
    assert not marker.exists()


def test_write_scope_is_the_only_writable_vault_path(vault, make_skill):
    (vault.tasks_dir / "p.md").write_text("- [ ] x\n")
    result = _run(vault, make_skill, "scoped-write", """
        import os
        vault = os.environ["HUBIDUBIOS_VAULT"]
        outcomes = {}
        for rel in ("tasks/new.md", "notes/hack.md"):
            try:
                open(os.path.join(vault, rel), "w").write("x")
                outcomes[rel] = "wrote"
            except OSError:
                outcomes[rel] = "denied"
        print(json.dumps(outcomes))
        """, sandbox={"read": [], "write": ["vault:tasks"],
                      "network": "none", "timeout": "10s"})
    assert result["tasks/new.md"] == "wrote"
    assert result["notes/hack.md"] == "denied"  # notes/ never bound
