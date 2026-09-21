"""Agent reviews, kept beside the vault as plain files.

A review is prose: what the agent made of an item, written once and read
back every time the item is opened. A note can carry it in its frontmatter,
but a task or an inbox capture is a single line whose metadata is
`key:value` pairs with no whitespace allowed in a value — prose cannot go
there without turning the line into an unreadable blob.

So everything that is not a note keeps its review in `reviews/<id>.md`:
one file, readable, diffable, and deleted with the item it belongs to.
"""

from pathlib import Path


def reviews_dir(vault) -> Path:
    return vault.root / "reviews"


def path_for(vault, entity_id: str) -> Path:
    # Ids are generated (`i_`/`n_`/`t_` + base32), so nothing here can escape
    # the directory; refuse anything that tries rather than trust that.
    if "/" in entity_id or "\\" in entity_id or entity_id in ("", ".", ".."):
        raise ValueError(f"unusable entity id {entity_id!r}")
    return reviews_dir(vault) / f"{entity_id}.md"


def read(vault, entity_id: str) -> str | None:
    path = path_for(vault, entity_id)
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8").strip()
    return text or None


def write(vault, entity_id: str, text: str | None) -> Path | None:
    """Store a review, or remove it when the text is empty."""
    path = path_for(vault, entity_id)
    if text is None or not text.strip():
        path.unlink(missing_ok=True)
        return None
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n", encoding="utf-8")
    return path


def remove(vault, entity_id: str) -> None:
    try:
        path_for(vault, entity_id).unlink(missing_ok=True)
    except (OSError, ValueError):
        pass


def attach(vault, d: dict) -> dict:
    """Fill in a parsed entity's review from its sidecar.

    Notes carry their own in frontmatter; anything already carrying one keeps
    it, so this only speaks for the types whose file format cannot.
    """
    if not d.get("review") and d.get("id"):
        text = read(vault, d["id"])
        if text:
            d["review"] = text
    return d
