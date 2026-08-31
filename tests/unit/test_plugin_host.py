import hashlib
import json
import shutil
import sys
from pathlib import Path

import pytest

from prodos_vault.formats import tasks_md
from prodos_vault.index import indexer, queries

from prodos_plugin_host import registry as reg_mod
from prodos_plugin_host.manifest import ManifestError, load as load_manifest
from prodos_plugin_host.registry import RegistryError, discover, export_entity_types

REPO_PLUGINS = Path(__file__).parents[2] / "plugins"


def test_shipped_plugins_are_valid():
    reg = discover([REPO_PLUGINS])
    assert sorted(reg.plugins) == ["calendar", "gtd", "inbox", "tasks"]
    assert reg.errors == []  # all four manifests load


def test_phase3_install_set_entity_types():
    reg = discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"})
    types = reg.entity_types()
    assert sorted(types) == ["event", "inbox_item", "note", "task"]
    assert types["note"]["plugin"] is None  # note stays core
    assert types["task"]["plugin"] == "tasks"


def test_gtd_install_set_entity_types():
    reg = discover([REPO_PLUGINS], names={"gtd", "calendar"})
    types = reg.entity_types()
    assert sorted(types) == [
        "event", "inbox_item", "next_action", "note", "project",
        "someday", "waiting_for"]
    assert types["next_action"]["plugin"] == "gtd"
    assert types["waiting_for"]["path"] == "tasks/waiting.md"


def test_gtd_conflicts_with_inbox():
    # gtd supersedes inbox+tasks; installing both is a fatal config error.
    reg = discover([REPO_PLUGINS], names={"gtd", "inbox"})
    with pytest.raises(RegistryError, match="inbox_item"):
        reg.entity_types()


def test_invalid_manifest_refused_with_error(tmp_path):
    bad = tmp_path / "plugins" / "broken"
    bad.mkdir(parents=True)
    (bad / "PLUGIN.md").write_text(
        "---\nname: broken\nversion: not-semver\ndescription: x\napi: 1\n---\n")
    reg = discover([tmp_path / "plugins"])
    assert reg.plugins == {}
    assert len(reg.errors) == 1 and "version" in reg.errors[0]


def test_missing_skill_files_refused(tmp_path):
    bad = tmp_path / "plugins" / "half"
    (bad / "skills" / "s").mkdir(parents=True)
    (bad / "PLUGIN.md").write_text(
        "---\nname: half\nversion: 1.0.0\ndescription: x\napi: 1\n"
        "skills:\n  - name: s\n    sandbox:\n      read: []\n      write: []\n"
        "      network: none\n      timeout: 5s\n---\n")
    with pytest.raises(ManifestError, match="missing SKILL.md"):
        load_manifest(bad)


def test_unsupported_api_refused(tmp_path):
    p = tmp_path / "plugins" / "future"
    p.mkdir(parents=True)
    (p / "PLUGIN.md").write_text(
        "---\nname: future\nversion: 1.0.0\ndescription: x\napi: 99\n---\n")
    reg = discover([tmp_path / "plugins"])
    assert reg.plugins == {}
    assert "api 99" in reg.errors[0]


def test_user_dir_overrides_system_dir(tmp_path):
    for base, version in (("system", "1.0.0"), ("user", "2.0.0")):
        p = tmp_path / base / "thing"
        p.mkdir(parents=True)
        (p / "PLUGIN.md").write_text(
            f"---\nname: thing\nversion: {version}\ndescription: x\napi: 1\n---\n")
    reg = discover([tmp_path / "system", tmp_path / "user"])
    assert reg.plugins["thing"].manifest["version"] == "2.0.0"


def test_duplicate_entity_type_is_fatal(tmp_path):
    for name in ("one", "two"):
        p = tmp_path / "plugins" / name
        (p / "entities").mkdir(parents=True)
        (p / "entities" / "widget.json").write_text("{}")
        (p / "PLUGIN.md").write_text(
            f"---\nname: {name}\nversion: 1.0.0\ndescription: x\napi: 1\n"
            "entities:\n  - type: widget\n    schema: entities/widget.json\n"
            "    path: widgets/\n---\n")
    reg = discover([tmp_path / "plugins"])
    assert len(reg.plugins) == 2  # both load; the registry merge is what fails
    with pytest.raises(RegistryError, match="widget"):
        reg.entity_types()


def _tree_hash(root: Path) -> dict[str, str]:
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*"))
            if p.is_file() and ".prodos" not in p.parts}


def test_extraction_uninstall_reinstall_cycle(vault, tmp_path):
    """PLAN.md Phase 3 acceptance: uninstalling a plugin leaves its vault
    files intact and its entities absent from the index; reinstalling
    restores them with no data loss; the vault bytes never change."""
    tasks_md.append_task(vault.tasks_dir / "p.md", "keep me", due="2026-09-09")
    (vault.inbox_dir / "2026-08-31.md").write_text(
        "- 09:00 an item  <!-- id:i_keep0001 -->\n")
    before = _tree_hash(vault.root)

    # "install": the Phase 3 set registered
    reg = discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"})
    export_entity_types(vault, reg)
    conn = indexer.open_index(vault)
    assert queries.counts(conn) == {"task": 1, "inbox_item": 1}

    # "uninstall" the tasks plugin: re-export without it
    without = tmp_path / "without-tasks"
    without.mkdir()
    for name in ("calendar", "inbox"):
        shutil.copytree(REPO_PLUGINS / name, without / name)
    export_entity_types(vault, discover([without]))
    indexer.sweep(conn, vault)
    counts = queries.counts(conn)
    assert "task" not in counts and counts["inbox_item"] == 1
    assert (vault.tasks_dir / "p.md").exists()  # files untouched

    # "reinstall": entities come back, nothing lost
    export_entity_types(vault, discover([REPO_PLUGINS], names={"tasks", "calendar", "inbox"}))
    indexer.sweep(conn, vault)
    tasks = queries.task_list(conn)
    assert tasks[0]["text"] == "keep me" and tasks[0]["due"] == "2026-09-09"
    conn.close()

    assert _tree_hash(vault.root) == before  # byte-identical throughout


def test_cli_refuses_unregistered_type(vault, monkeypatch, capsys):
    from prodos_vault import cli

    monkeypatch.setenv("PRODOS_VAULT", str(vault.root))
    # registry present but without the task type
    vault.entity_types_path.write_text(json.dumps(
        {"types": {"note": {"path": "notes/", "suffix": ".md"}}}))
    vault._registry = None
    assert cli.main(["task", "add", "nope"]) == 1
    assert "tasks" in capsys.readouterr().err
    assert not (vault.tasks_dir / "inbox.md").exists()


def test_run_skill_refused_without_sandbox_off_linux(vault, monkeypatch):
    if sys.platform == "linux":
        pytest.skip("refusal path is for non-Linux dev machines")
    from prodos_plugin_host.sandbox import SandboxError, run_skill

    monkeypatch.delenv("PRODOS_SKILL_SANDBOX", raising=False)
    reg = discover([REPO_PLUGINS])
    plugin, spec = reg.skill("inbox", "capture")
    with pytest.raises(SandboxError, match="bwrap requires Linux"):
        run_skill(vault, plugin, spec, {"text": "x"})


def test_run_skill_dev_mode_capture(vault, monkeypatch):
    from prodos_plugin_host.sandbox import run_skill

    monkeypatch.setenv("PRODOS_SKILL_SANDBOX", "dangerously-none")
    reg = discover([REPO_PLUGINS])
    plugin, spec = reg.skill("inbox", "capture")
    result = run_skill(vault, plugin, spec, {"text": "skill capture works"})
    assert result["id"].startswith("i_")
    files = list(vault.inbox_dir.glob("*.md"))
    assert len(files) == 1 and "skill capture works" in files[0].read_text()


def test_run_skill_dev_mode_agenda(vault, monkeypatch):
    from prodos_plugin_host.sandbox import run_skill

    monkeypatch.setenv("PRODOS_SKILL_SANDBOX", "dangerously-none")
    from datetime import date

    tasks_md.append_task(vault.tasks_dir / "p.md", "due today",
                         due=date.today().isoformat())
    reg = discover([REPO_PLUGINS])
    plugin, spec = reg.skill("calendar", "agenda")
    result = run_skill(vault, plugin, spec, {"days": 3})
    assert result["due_tasks"][0]["text"] == "due today"
