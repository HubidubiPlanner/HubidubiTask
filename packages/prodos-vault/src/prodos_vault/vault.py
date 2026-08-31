"""Vault root resolution and layout.

Resolution order: $PRODOS_VAULT env var, then ~/vault. Config lives at
.prodos/config.toml and is optional — a vault is just a directory of plain
files, fully usable with vim, grep, and git.
"""

import os
from pathlib import Path


class Vault:
    def __init__(self, root: Path):
        self.root = Path(root).expanduser()

    @classmethod
    def locate(cls) -> "Vault":
        env = os.environ.get("PRODOS_VAULT")
        return cls(Path(env) if env else Path.home() / "vault")

    # Layout
    @property
    def inbox_dir(self) -> Path:
        return self.root / "inbox"

    @property
    def notes_dir(self) -> Path:
        return self.root / "notes"

    @property
    def tasks_dir(self) -> Path:
        return self.root / "tasks"

    @property
    def calendars_dir(self) -> Path:
        return self.root / "calendars"

    @property
    def prodos_dir(self) -> Path:
        return self.root / ".prodos"

    @property
    def index_path(self) -> Path:
        return self.prodos_dir / "index.db"

    @property
    def config_path(self) -> Path:
        return self.prodos_dir / "config.toml"

    def config(self) -> dict:
        if not self.config_path.exists():
            return {}
        import tomllib

        with open(self.config_path, "rb") as f:
            return tomllib.load(f)

    @property
    def entity_types_path(self) -> Path:
        return self.prodos_dir / "entity-types.json"

    def ensure_layout(self) -> None:
        for d in (self.inbox_dir, self.notes_dir, self.tasks_dir,
                  self.calendars_dir, self.prodos_dir):
            d.mkdir(parents=True, exist_ok=True)

    def entity_registry(self) -> dict[str, dict]:
        """type -> {path, suffix} map deciding what gets indexed.

        Written to .prodos/entity-types.json by the plugin host when plugins
        are (un)installed; absent, the builtin Phase 1 map applies so a
        standalone vault works with no plugin system at all. Cached per
        instance — CLI processes are short-lived.
        """
        if getattr(self, "_registry", None) is None:
            self._registry = dict(BUILTIN_ENTITY_TYPES)
            if self.entity_types_path.exists():
                import json

                try:
                    loaded = json.loads(self.entity_types_path.read_text())
                    types = loaded.get("types")
                    if isinstance(types, dict) and types:
                        self._registry = {
                            t: {"path": s["path"], "suffix": s["suffix"]}
                            for t, s in types.items()
                        }
                except (ValueError, KeyError, TypeError):
                    pass  # corrupt registry: builtin map keeps the vault usable
        return self._registry

    def classify(self, path: Path) -> str | None:
        """Entity type a file belongs to, by registry path prefix, or None.

        Skips hidden files and Radicale's .Radicale.props (Phase 2 serves
        calendars/ directly from the vault).
        """
        try:
            rel = path.resolve().relative_to(self.root.resolve())
        except ValueError:
            return None
        if any(part.startswith(".") for part in rel.parts):
            return None
        if not rel.parts:
            return None
        for etype, spec in self.entity_registry().items():
            if rel.parts[0] == spec["path"].rstrip("/") and path.suffix == spec["suffix"]:
                return etype
        return None

    def entity_files(self):
        """Yield (path, type) for every indexable file in the vault."""
        seen_dirs = []
        for spec in self.entity_registry().values():
            d = self.root / spec["path"].rstrip("/")
            if d not in seen_dirs:
                seen_dirs.append(d)
        for d in seen_dirs:
            if not d.exists():
                continue
            for p in sorted(d.rglob("*")):
                if p.is_file():
                    domain = self.classify(p)
                    if domain:
                        yield p, domain


BUILTIN_ENTITY_TYPES: dict[str, dict] = {
    "inbox_item": {"path": "inbox/", "suffix": ".md"},
    "note": {"path": "notes/", "suffix": ".md"},
    "task": {"path": "tasks/", "suffix": ".md"},
    "event": {"path": "calendars/", "suffix": ".ics"},
}
