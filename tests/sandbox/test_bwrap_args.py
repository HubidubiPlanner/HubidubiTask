"""Platform-independent checks of the bwrap invocation we construct."""

import pytest

from prodos_plugin_host.sandbox import SandboxError, bwrap_args, resolve_scope


SPEC = {"read": ["vault:calendars"], "write": ["vault:tasks"],
        "network": "none", "timeout": "30s"}


def test_baseline_hardening(vault, tmp_path):
    args = bwrap_args(vault, tmp_path / "skill", SPEC)
    for flag in ("--die-with-parent", "--new-session", "--unshare-pid",
                 "--unshare-net", "--proc", "--tmpfs"):
        assert flag in args
    # /usr read-only, minimal /etc
    i = args.index("--ro-bind")
    assert args[i + 1 : i + 3] == ["/usr", "/usr"]
    assert "--tmpfs" in args and "/etc" in args


def _binds(args):
    """Extract (flag, src, dst) triples for every bind-family flag."""
    bind_flags = {"--ro-bind", "--ro-bind-try", "--bind", "--bind-try"}
    out = []
    i = 0
    while i < len(args):
        if args[i] in bind_flags:
            out.append((args[i], args[i + 1], args[i + 2]))
            i += 3
        else:
            i += 1
    return out


def test_declared_scopes_bound_with_correct_mode(vault, tmp_path):
    args = bwrap_args(vault, tmp_path / "skill", SPEC)
    cal = str(vault.root / "calendars")
    tasks = str(vault.root / "tasks")
    binds = _binds(args)

    cal_binds = [b for b in binds if b[1] == cal]
    assert cal_binds and cal_binds[0][0] == "--ro-bind-try" and cal_binds[0][2] == cal
    tasks_binds = [b for b in binds if b[1] == tasks]
    assert tasks_binds and tasks_binds[0][0] == "--bind" and tasks_binds[0][2] == tasks
    # nothing binds the vault root itself
    assert not any(b[1] == str(vault.root) for b in binds)


def test_lan_network_keeps_namespace_shared(vault, tmp_path):
    spec = {**SPEC, "network": "lan"}
    args = bwrap_args(vault, tmp_path / "skill", spec)
    assert "--unshare-net" not in args


def test_scope_cannot_escape_vault(vault):
    with pytest.raises(SandboxError, match="escapes the vault"):
        resolve_scope(vault, "vault:../../../etc")
    with pytest.raises(SandboxError, match="unknown scope"):
        resolve_scope(vault, "host:/etc")
