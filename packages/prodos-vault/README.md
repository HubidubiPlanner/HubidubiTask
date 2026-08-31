# prodos-vault

Phase 1 of the prodos plan (see repo-root `PLAN.md`): tasks, calendar, notes,
and the GTD inbox as hardcoded core features. Files are the source of truth;
`index.db` is a disposable SQLite/FTS5 index, rebuildable at any time.

## Setup

From the repo root (uv workspace):

```sh
uv sync
```

The `prodos` entry point lands in `.venv/bin/prodos`; put it on your PATH
(e.g. `ln -s "$PWD/.venv/bin/prodos" ~/.local/bin/prodos`) or use `uv run prodos`.

The vault lives at `~/vault` (override with `$PRODOS_VAULT`).

## Commands

```
prodos capture <text>          append to today's inbox (fast path, no index)
prodos task add|list|done      manage tasks (one markdown file per project)
prodos note new <title>        create a note under notes/YYYY/MM/
prodos agenda [--days N]       events + due tasks
prodos search <query>          full-text search
prodos inbox                   list unprocessed inbox items
prodos index rebuild           reconstruct index.db from the vault
prodos watch                   keep the index fresh (500ms debounce)
```

Read commands run a staleness sweep first, so edits made in any editor are
visible immediately even with no watcher running.

## Tests

From the repo root:

```sh
uv run pytest tests/unit          # fast unit tests
uv run pytest tests/acceptance    # Phase 1 acceptance criteria from PLAN.md
```

## Format notes

- Every entity carries `id` (random, stable, e.g. `t_a1b2c3d4`), `updated`
  (RFC 3339), and `type` — load-bearing for later sync and plugin phases.
- Task/inbox metadata lives in HTML comments: `<!-- id:t_x due:2026-09-05 ctx:@computer -->`.
- CLI-written entities carry `updated:` inline; hand-edited ones without it
  fall back to file mtime at index time (the indexer never rewrites your files).
- Event identity is the iCal `UID`; the entity id is derived from it.
