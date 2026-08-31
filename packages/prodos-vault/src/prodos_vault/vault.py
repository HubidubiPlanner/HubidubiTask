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
            self._registry = {t: dict(s) for t, s in BUILTIN_ENTITY_TYPES.items()}
            if self.entity_types_path.exists():
                import json

                try:
                    loaded = json.loads(self.entity_types_path.read_text())
                    types = loaded.get("types")
                    if isinstance(types, dict) and types:
                        self._registry = {
                            t: {
                                "path": s["path"],
                                "suffix": s["suffix"],
                                "format": s.get("format") or _default_format(s["suffix"]),
                            }
                            for t, s in types.items()
                        }
                except (ValueError, KeyError, TypeError):
                    pass  # corrupt registry: builtin map keeps the vault usable
        return self._registry

    def entity_format(self, etype: str) -> str:
        spec = self.entity_registry().get(etype, {})
        return spec.get("format") or _default_format(spec.get("suffix", ".md"))

    def classify(self, path: Path) -> str | None:
        """Entity type a file belongs to, or None.

        A spec whose `path` names a file (no trailing slash) is an exact match
        and takes precedence over a directory spec covering the same file —
        this is how `tasks/waiting.md` (waiting_for) is distinguished from
        `tasks/*.md` (next_action). Skips hidden files and .Radicale.props.
        """
        try:
            rel = path.resolve().relative_to(self.root.resolve())
        except ValueError:
            return None
        if any(part.startswith(".") for part in rel.parts):
            return None
        if not rel.parts:
            return None
        rel_posix = rel.as_posix()
        registry = self.entity_registry()
        # Exact-file specs win over directory specs.
        for etype, spec in registry.items():
            p = spec["path"]
            if not p.endswith("/") and rel_posix == p and path.suffix == spec["suffix"]:
                return etype
        for etype, spec in registry.items():
            p = spec["path"]
            if p.endswith("/") and rel.parts[0] == p.rstrip("/") and path.suffix == spec["suffix"]:
                return etype
        return None

    def entity_files(self):
        """Yield (path, type) for every indexable file in the vault."""
        candidates: list[Path] = []
        for spec in self.entity_registry().values():
            p = spec["path"]
            if p.endswith("/"):
                d = self.root / p.rstrip("/")
                if d.exists():
                    candidates.extend(x for x in d.rglob("*") if x.is_file())
            else:
                f = self.root / p
                if f.is_file():
                    candidates.append(f)
        seen: set[Path] = set()
        for p in sorted(candidates):
            if p in seen:
                continue
            seen.add(p)
            domain = self.classify(p)
            if domain:
                yield p, domain


def _default_format(suffix: str) -> str:
    return "ics" if suffix == ".ics" else "frontmatter"


# format: how the indexer parses this type's files.
#   checkbox    -> "- [ ] text <!-- id:.. key:val -->" lines (tasks, next_action, ...)
#   inbox       -> "- HH:MM text <!-- id:.. -->" append-only lines
#   frontmatter -> YAML frontmatter + markdown body (notes, project)
#   ics         -> iCalendar
BUILTIN_ENTITY_TYPES: dict[str, dict] = {
    "inbox_item": {"path": "inbox/", "suffix": ".md", "format": "inbox"},
    "note": {"path": "notes/", "suffix": ".md", "format": "frontmatter"},
    "task": {"path": "tasks/", "suffix": ".md", "format": "checkbox"},
    "event": {"path": "calendars/", "suffix": ".ics", "format": "ics"},
}
