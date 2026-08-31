"""Skill execution under bwrap.

Every skill runs in a bubblewrap sandbox: read-only /usr and a minimal /etc,
only the paths resolved from declared read/write scopes bound in, no network
unless the manifest says `lan`, --die-with-parent, --new-session, enforced
timeout. Protocol: JSON on stdin, JSON on stdout.

bwrap is Linux-only. On other platforms execution is refused unless
PRODOS_SKILL_SANDBOX=dangerously-none is set explicitly (development only —
never ship a code path that quietly skips the sandbox).
"""

import json
import os
import subprocess
import sys
from pathlib import Path

from .manifest import timeout_seconds


class SandboxError(Exception):
    pass


class SkillError(Exception):
    pass


def resolve_scope(vault, scope: str) -> Path:
    """`vault:tasks` -> ~/vault/tasks. Scopes never escape the vault."""
    if not scope.startswith("vault:"):
        raise SandboxError(f"unknown scope {scope!r}")
    sub = scope[len("vault:"):]
    p = (vault.root / sub).resolve()
    if not p.is_relative_to(vault.root.resolve()):
        raise SandboxError(f"scope {scope!r} escapes the vault")
    return p


def bwrap_args(vault, skill_dir: Path, sandbox_spec: dict) -> list[str]:
    args = [
        "bwrap",
        "--die-with-parent",
        "--new-session",
        "--unshare-user", "--unshare-pid", "--unshare-ipc",
        "--unshare-uts", "--unshare-cgroup",
        "--proc", "/proc",
        "--dev", "/dev",
        "--tmpfs", "/tmp",
        "--ro-bind", "/usr", "/usr",
        "--symlink", "usr/lib", "/lib",
        "--symlink", "usr/lib64", "/lib64",
        "--symlink", "usr/bin", "/bin",
        "--symlink", "usr/sbin", "/sbin",
        "--tmpfs", "/etc",
        "--ro-bind-try", "/etc/localtime", "/etc/localtime",
        "--ro-bind-try", "/etc/ssl", "/etc/ssl",
        "--setenv", "PATH", "/usr/bin",
        "--setenv", "HOME", "/tmp",
        "--chdir", "/",
        "--ro-bind", str(skill_dir), str(skill_dir),
    ]
    if sandbox_spec.get("network", "none") != "lan":
        args.append("--unshare-net")
    # With `network: lan` the net namespace is shared; egress is then
    # restricted to the LAN subnet by the host nftables cgroup rule
    # (config/nftables.conf on the target), not by bwrap.
    for scope in sandbox_spec.get("read", []):
        p = resolve_scope(vault, scope)
        args += ["--ro-bind-try", str(p), str(p)]
    for scope in sandbox_spec.get("write", []):
        p = resolve_scope(vault, scope)
        p.mkdir(parents=True, exist_ok=True)
        args += ["--bind", str(p), str(p)]
    return args


def run_skill(vault, plugin, skill_spec: dict, params: dict) -> dict:
    """Execute a skill with params on stdin; returns its JSON result."""
    skill_dir = plugin.dir / "skills" / skill_spec["name"]
    run_path = skill_dir / "bin" / "run"
    timeout = timeout_seconds(skill_spec["sandbox"])

    if sys.platform == "linux":
        cmd = bwrap_args(vault, skill_dir, skill_spec["sandbox"]) + [str(run_path)]
    elif os.environ.get("PRODOS_SKILL_SANDBOX") == "dangerously-none":
        cmd = [str(run_path)]
    else:
        raise SandboxError(
            "bwrap requires Linux; refusing to run the skill unsandboxed "
            "(set PRODOS_SKILL_SANDBOX=dangerously-none for development only)")

    env = {"PATH": "/usr/bin:/bin", "PRODOS_VAULT": str(vault.root)}
    # Skills that declare `network: lan` may need the model endpoint as host
    # config; forward only that one variable, and only to lan skills.
    if skill_spec["sandbox"].get("network") == "lan":
        endpoint = os.environ.get("PRODOS_MODEL_ENDPOINT")
        if endpoint:
            env["PRODOS_MODEL_ENDPOINT"] = endpoint
    try:
        proc = subprocess.run(
            cmd, input=json.dumps(params).encode("utf-8"),
            capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired as e:
        raise SkillError(
            f"{plugin.name}/{skill_spec['name']}: timed out after {timeout}s") from e
    if proc.returncode != 0:
        raise SkillError(
            f"{plugin.name}/{skill_spec['name']}: exit {proc.returncode}: "
            f"{proc.stderr.decode('utf-8', 'replace').strip()[:500]}")
    try:
        return json.loads(proc.stdout)
    except ValueError as e:
        raise SkillError(
            f"{plugin.name}/{skill_spec['name']}: stdout is not JSON") from e
