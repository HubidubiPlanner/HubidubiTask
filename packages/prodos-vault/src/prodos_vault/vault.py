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

    def ensure_layout(self) -> None:
        for d in (self.inbox_dir, self.notes_dir, self.tasks_dir,
                  self.calendars_dir, self.prodos_dir):
            d.mkdir(parents=True, exist_ok=True)

    def classify(self, path: Path) -> str | None:
        """Entity domain a file belongs to, by path prefix, or None.

        Skips hidden files and Radicale's .Radicale.props (Phase 2 serves
        calendars/ directly from the vault).
        """
        try:
            rel = path.resolve().relative_to(self.root.resolve())
        except ValueError:
            return None
        if any(part.startswith(".") for part in rel.parts):
            return None
        top = rel.parts[0] if rel.parts else None
        if top == "inbox" and path.suffix == ".md":
            return "inbox_item"
        if top == "notes" and path.suffix == ".md":
            return "note"
        if top == "tasks" and path.suffix == ".md":
            return "task"
        if top == "calendars" and path.suffix == ".ics":
            return "event"
        return None

    def entity_files(self):
        """Yield (path, domain) for every indexable file in the vault."""
        for d in (self.inbox_dir, self.notes_dir, self.tasks_dir, self.calendars_dir):
            if not d.exists():
                continue
            for p in sorted(d.rglob("*")):
                if p.is_file():
                    domain = self.classify(p)
                    if domain:
                        yield p, domain
