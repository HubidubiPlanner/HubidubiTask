"""Plugin discovery and the global entity-type registry.

- User dir overrides system dir on name collision.
- Invalid manifests are refused and reported, never loaded silently.
- An unsupported `api` version refuses the plugin.
- Two plugins claiming the same entity type is a fatal configuration error,
  not a merge.
"""

import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path

from . import SUPPORTED_API
from .manifest import ManifestError
from .manifest import load as load_manifest

log = logging.getLogger("hubidubios.plugins")

SYSTEM_DIR = Path("/usr/share/hubidubios/plugins")
USER_DIR = Path.home() / ".local/share/hubidubios/plugins"

# note stays a core (non-plugin) entity type.
CORE_ENTITY_TYPES: dict[str, dict] = {
    "note": {"path": "notes/", "suffix": ".md", "format": "frontmatter", "plugin": None},
}


class RegistryError(Exception):
    pass


@dataclass
class Plugin:
    name: str
    dir: Path
    manifest: dict


@dataclass
class Registry:
    plugins: dict[str, Plugin] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    def entity_types(self) -> dict[str, dict]:
        """Global type registry: core types + every plugin's contributions.
        Duplicate claims are fatal."""
        types = {t: dict(spec) for t, spec in CORE_ENTITY_TYPES.items()}
        for plugin in self.plugins.values():
            for ent in plugin.manifest.get("entities", []):
                t = ent["type"]
                if t in types:
                    other = types[t]["plugin"] or "core"
                    raise RegistryError(
                        f"entity type {t!r} claimed by both {other!r} and "
                        f"{plugin.name!r} — fatal configuration error")
                suffix = ent.get("suffix", ".md")
                fmt = ent.get("format") or ("ics" if suffix == ".ics" else "frontmatter")
                types[t] = {"path": ent["path"], "suffix": suffix, "format": fmt,
                            "plugin": plugin.name,
                            "schema": str(plugin.dir / ent["schema"])}
        return types

    def skill(self, plugin_name: str, skill_name: str) -> tuple[Plugin, dict] | None:
        plugin = self.plugins.get(plugin_name)
        if not plugin:
            return None
        for s in plugin.manifest.get("skills", []):
            if s["name"] == skill_name:
                return plugin, s
        return None

    def all_skills(self):
        for plugin in self.plugins.values():
            for s in plugin.manifest.get("skills", []):
                yield plugin, s

    def view(self, plugin_name: str, view_id: str) -> tuple[Plugin, dict] | None:
        plugin = self.plugins.get(plugin_name)
        if not plugin:
            return None
        for v in plugin.manifest.get("views", []):
            if v["id"] == view_id:
                return plugin, v
        return None


def plugin_dirs() -> list[Path]:
    """Search path, lowest precedence first. Override with
    $HUBIDUBIOS_PLUGIN_DIRS (colon-separated, later wins)."""
    env = os.environ.get("HUBIDUBIOS_PLUGIN_DIRS")
    if env:
        return [Path(p).expanduser() for p in env.split(":") if p]
    return [SYSTEM_DIR, USER_DIR]


def discover(dirs: list[Path] | None = None,
             names: set[str] | None = None) -> Registry:
    """Discover plugins. `names`, when given, restricts loading to those plugin
    directory names — used to pick one install set out of a source tree that
    also holds mutually-superseding plugins (e.g. gtd vs tasks+inbox)."""
    reg = Registry()
    for base in dirs if dirs is not None else plugin_dirs():
        if not base.is_dir():
            continue
        for pdir in sorted(base.iterdir()):
            if not pdir.is_dir():
                continue
            if names is not None and pdir.name not in names:
                continue
            try:
                m = load_manifest(pdir)
            except ManifestError as e:
                msg = f"refusing plugin at {pdir}: {e}"
                log.error(msg)
                reg.errors.append(msg)
                continue
            if m["api"] > SUPPORTED_API:
                msg = (f"refusing plugin {m['name']}: requires api {m['api']}, "
                       f"host supports {SUPPORTED_API}")
                log.error(msg)
                reg.errors.append(msg)
                continue
            if m["name"] in reg.plugins:
                log.info("plugin %s at %s overrides %s", m["name"], pdir,
                         reg.plugins[m["name"]].dir)
            reg.plugins[m["name"]] = Plugin(name=m["name"], dir=pdir, manifest=m)
    return reg


def export_entity_types(vault, reg: Registry) -> Path:
    """Write the merged type registry to .hubidubios/entity-types.json.

    This is what the vault indexer consults: uninstalling a plugin and
    re-exporting removes its types, so its entities leave the index while
    its files stay untouched in the vault.
    """
    types = reg.entity_types()  # raises on duplicates before writing anything
    vault.hubidubios_dir.mkdir(parents=True, exist_ok=True)
    payload = {"types": {t: {"path": s["path"], "suffix": s["suffix"],
                             "format": s.get("format", "frontmatter"),
                             "plugin": s["plugin"]}
                         for t, s in types.items()}}
    vault.entity_types_path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    vault._registry = None  # drop the instance cache
    return vault.entity_types_path
