# prodos plugins

Phase 3 (PLAN.md): the three Phase 1 core features, extracted into plugins to
prove the plugin API against real consumers rather than a hypothetical one.

| Plugin | Entity type(s) | Skills | Views |
|---|---|---|---|
| `tasks` | `task` | `task-add` | `task-list` |
| `calendar` | `event` | `agenda` | — |
| `inbox` | `inbox_item` | `capture` | — |
| `gtd` | `inbox_item`, `next_action`, `waiting_for`, `someday`, `project` | `gtd-clarify`, `gtd-weekly-review`, `gtd-next`, `gtd-file` | `capture`, `clarify`, `next-actions`, `weekly-review` |

`note` stays a core (non-plugin) entity type.

**Install sets.** `gtd` (Phase 6) is the workflow layer and **supersedes**
`tasks` + `inbox` — it owns the richer GTD entity model, so its PKGBUILD
`conflicts` with them. Install either the GTD set (`gtd` + `calendar`, the
default) or the Phase 3 extraction set (`tasks` + `calendar` + `inbox`), never
both. The two share this source tree only for reference; `discover(names=…)`
picks one set.

A plugin type is parsed by the `format` its manifest declares — `checkbox`
(task-style lines), `frontmatter` (YAML+body docs), `inbox`, or `ics` — which
is how the indexer handles GTD's new types and its multiple types per
directory (`tasks/` holds `next_action`; `tasks/waiting.md` is `waiting_for`).

## Layout (per plugin)

```
PLUGIN.md              manifest frontmatter + prose
entities/<type>.json   JSON Schema per contributed entity type
skills/<skill>/
  SKILL.md             AI-facing description
  schema.json          parameter schema
  bin/run              JSON on stdin, JSON on stdout (runs under bwrap)
views/                 HTML/JS/CSS, no build step, no remote loads
PKGBUILD               pacman package (prodos-plugin-<name>)
prodos-plugin.install  refreshes the entity registry on install/remove
```

## Two independent permission models

- **`sandbox`** governs skill execution under bwrap (`read`/`write` vault
  scopes, `network`, `timeout`).
- **`permissions`** governs view access to entity types over the WebView
  bridge (`read`/`write` entity types).

Both are enforced independently — see `tests/sandbox/` (bwrap escapes) and
`tests/view-sandbox/` (bridge permission checks).

## Registry

The host merges every plugin's entity types into
`~/vault/.prodos/entity-types.json`, which the vault indexer consults.
Installing/removing a plugin and running `prodos-plugins refresh` adds/removes
its types: entities leave or rejoin the index while the vault files on disk
never change (verified byte-for-byte in `test_extraction_uninstall_reinstall_cycle`).

Two plugins claiming the same entity type is a fatal configuration error.

## Dev vs. target

Skills run sandboxed under bwrap (Linux). On a non-Linux dev machine the host
refuses to run a skill rather than silently skipping the sandbox; set
`PRODOS_SKILL_SANDBOX=dangerously-none` to run unsandboxed for development
only. The bwrap escape suite runs via `tests/sandbox/run-in-docker.sh`.
