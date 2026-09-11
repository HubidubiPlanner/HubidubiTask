# gtd-archive

Move a done or dropped action into the archive.

Given an item id living in `current/`, `waiting/`, or `maybe/`, this removes it
from that active view and appends it (marked done, tagged with the state it came
from) to `archive/YYYY-MM.md`. Nothing is lost — archived items stay searchable —
they just leave the active lists. Deterministic; no model.
