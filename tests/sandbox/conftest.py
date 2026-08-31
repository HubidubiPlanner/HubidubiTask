import json
import textwrap
from pathlib import Path

import pytest

from prodos_vault.vault import Vault


@pytest.fixture
def vault(tmp_path):
    v = Vault(tmp_path / "vault")
    v.ensure_layout()
    return v


@pytest.fixture
def make_skill(tmp_path):
    """Build a throwaway plugin containing one skill with the given body.

    The body is the Python source of bin/run; params arrive as `params`,
    and it must print a JSON result.
    """

    def _make(name: str, body: str, sandbox: dict | None = None):
        sandbox = sandbox or {"read": [], "write": [],
                              "network": "none", "timeout": "10s"}
        pdir = tmp_path / "plugins" / "evil"
        sdir = pdir / "skills" / name
        (sdir / "bin").mkdir(parents=True, exist_ok=True)
        (sdir / "SKILL.md").write_text(f"# {name}\n")
        (sdir / "schema.json").write_text('{"type": "object"}')
        run = sdir / "bin" / "run"
        run.write_text(
            "#!/usr/bin/env python3\n"
            "import json, sys\nparams = json.load(sys.stdin)\n"
            + textwrap.dedent(body))
        run.chmod(0o755)

        skills = []
        for existing in (pdir / "skills").iterdir():
            spec = sandbox if existing.name == name else {
                "read": [], "write": [], "network": "none", "timeout": "10s"}
            skills.append({"name": existing.name, "sandbox": spec})
        manifest = {"name": "evil", "version": "1.0.0",
                    "description": "sandbox escape suite", "api": 1,
                    "skills": skills}
        import yaml

        (pdir / "PLUGIN.md").write_text(
            "---\n" + yaml.safe_dump(manifest, sort_keys=False) + "---\n")
        from prodos_plugin_host.registry import discover

        reg = discover([tmp_path / "plugins"])
        assert reg.errors == []
        return reg.skill("evil", name)

    return _make
