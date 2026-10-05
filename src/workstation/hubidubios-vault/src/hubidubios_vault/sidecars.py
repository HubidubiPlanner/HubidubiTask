"""Prose kept beside the vault, one plain file per item.

A task or an inbox capture is a single markdown line whose metadata is
`key:value` pairs with no whitespace allowed in a value — prose cannot go
there without turning the line into an unreadable blob. So what such an item
says at length lives in a file of its own, named by the item's id: readable,
diffable, and deleted with the item.

Two kinds today: the agent's review (`reviews/<id>.md`) and the item's own
description (`details/<id>.md`, the `body` field — what Todoist calls a task's
description). A note keeps both in its own file and needs neither.
"""

from pathlib import Path


class Sidecar:
    def __init__(self, dirname: str, field: str):
        self.dirname, self.field = dirname, field

    def dir(self, vault) -> Path:
        return vault.root / self.dirname

    def path_for(self, vault, entity_id: str) -> Path:
        # Ids are generated (`i_`/`n_`/`t_` + base32), so nothing here can
        # escape the directory; refuse anything that tries rather than trust that.
        if "/" in entity_id or "\\" in entity_id or entity_id in ("", ".", ".."):
            raise ValueError(f"unusable entity id {entity_id!r}")
        return self.dir(vault) / f"{entity_id}.md"

    def read(self, vault, entity_id: str) -> str | None:
        path = self.path_for(vault, entity_id)
        if not path.exists():
            return None
        text = path.read_text(encoding="utf-8").strip()
        return text or None

    def write(self, vault, entity_id: str, text: str | None) -> Path | None:
        """Store the text, or remove it when empty."""
        path = self.path_for(vault, entity_id)
        if text is None or not text.strip():
            path.unlink(missing_ok=True)
            return None
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text.strip() + "\n", encoding="utf-8")
        return path

    def remove(self, vault, entity_id: str) -> None:
        try:
            self.path_for(vault, entity_id).unlink(missing_ok=True)
        except (OSError, ValueError):
            pass

    def attach(self, vault, d: dict) -> dict:
        """Fill a parsed entity's field from its sidecar, unless it already
        carries one (a note does, in its own file)."""
        if not d.get(self.field) and d.get("id"):
            text = self.read(vault, d["id"])
            if text:
                d[self.field] = text
        return d


REVIEW = Sidecar("reviews", "review")
BODY = Sidecar("details", "body")
