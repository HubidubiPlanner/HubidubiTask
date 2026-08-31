"""PLUGIN.md parsing and validation.

The manifest is YAML frontmatter; the prose below it is human documentation.
Every manifest is validated against the meta-schema, and every declared
skill/view/entity must have its files present — a plugin that fails any of
this is refused with a clear error, never loaded partially or silently.
"""

import json
import os
from importlib import resources
from pathlib import Path

_META_SCHEMA = None


class ManifestError(Exception):
    pass


def _meta_schema() -> dict:
    global _META_SCHEMA
    if _META_SCHEMA is None:
        with resources.files("prodos_plugin_host.data").joinpath(
                "manifest-schema.json").open() as f:
            _META_SCHEMA = json.load(f)
    return _META_SCHEMA


def parse_frontmatter(text: str) -> dict:
    import yaml

    lines = text.split("\n")
    if not lines or lines[0].strip() != "---":
        raise ManifestError("PLUGIN.md must start with YAML frontmatter (---)")
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            data = yaml.safe_load("\n".join(lines[1:i]))
            if not isinstance(data, dict):
                raise ManifestError("frontmatter is not a mapping")
            return data
    raise ManifestError("unterminated frontmatter")


def load(plugin_dir: Path) -> dict:
    """Parse and fully validate a plugin directory. Raises ManifestError."""
    import jsonschema

    manifest_path = plugin_dir / "PLUGIN.md"
    if not manifest_path.exists():
        raise ManifestError(f"{plugin_dir}: no PLUGIN.md")
    m = parse_frontmatter(manifest_path.read_text(encoding="utf-8"))

    try:
        jsonschema.validate(m, _meta_schema())
    except jsonschema.ValidationError as e:
        raise ManifestError(
            f"{plugin_dir.name}: manifest invalid at "
            f"{'/'.join(str(p) for p in e.absolute_path) or '<root>'}: {e.message}"
        ) from e

    if m["name"] != plugin_dir.name:
        raise ManifestError(
            f"{plugin_dir.name}: manifest name {m['name']!r} does not match directory")

    for ent in m.get("entities", []):
        ent.setdefault("suffix", ".md")
        schema_path = plugin_dir / ent["schema"]
        if not schema_path.is_file():
            raise ManifestError(
                f"{m['name']}: entity {ent['type']} schema missing: {ent['schema']}")
        try:
            json.loads(schema_path.read_text(encoding="utf-8"))
        except ValueError as e:
            raise ManifestError(
                f"{m['name']}: entity schema {ent['schema']} is not JSON: {e}") from e

    for skill in m.get("skills", []):
        sdir = plugin_dir / "skills" / skill["name"]
        for required in ("SKILL.md", "schema.json"):
            if not (sdir / required).is_file():
                raise ManifestError(
                    f"{m['name']}: skill {skill['name']} missing {required}")
        run = sdir / "bin" / "run"
        if not run.is_file():
            raise ManifestError(f"{m['name']}: skill {skill['name']} missing bin/run")
        if not os.access(run, os.X_OK):
            raise ManifestError(
                f"{m['name']}: skill {skill['name']} bin/run is not executable")

    if m.get("views") and not (plugin_dir / "views" / "index.html").is_file():
        raise ManifestError(f"{m['name']}: views declared but views/index.html missing")

    for sub in m.get("cli", []):
        cmd = plugin_dir / "cli" / sub
        if not (cmd.is_file() and os.access(cmd, os.X_OK)):
            raise ManifestError(f"{m['name']}: cli/{sub} missing or not executable")

    return m


def timeout_seconds(sandbox_spec: dict) -> int:
    return int(sandbox_spec["timeout"].rstrip("s"))
