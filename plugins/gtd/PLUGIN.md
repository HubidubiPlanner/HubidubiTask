---
name: gtd
version: 1.0.0
description: Getting Things Done workflow — capture, clarify, organize, reflect, engage.
api: 1
entities:
  - type: inbox_item
    schema: entities/inbox_item.json
    path: inbox/
    suffix: .md
    format: inbox
  - type: next_action
    schema: entities/next_action.json
    path: current/
    suffix: .md
    format: checkbox
  - type: waiting_for
    schema: entities/waiting_for.json
    path: waiting/
    suffix: .md
    format: checkbox
  - type: someday
    schema: entities/someday.json
    path: maybe/
    suffix: .md
    format: checkbox
  - type: archived
    schema: entities/archived.json
    path: archive/
    suffix: .md
    format: checkbox
  - type: project
    schema: entities/project.json
    path: projects/
    suffix: .md
    format: frontmatter
skills:
  - name: gtd-clarify
    sandbox:
      read:  [vault:inbox]
      write: []
      network: lan            # reaches the local llama.cpp endpoint; proposes only
      timeout: 60s
  - name: gtd-weekly-review
    sandbox:
      read:  [vault:inbox, vault:current, vault:waiting, vault:projects, vault:maybe, vault:calendars, vault:archive]
      write: []
      network: none
      timeout: 20s
  - name: gtd-next
    sandbox:
      read:  [vault:current]
      write: []
      network: none
      timeout: 15s
  - name: gtd-file
    sandbox:
      read:  [vault:inbox]
      write: [vault:inbox, vault:current, vault:waiting, vault:maybe, vault:projects]
      network: none
      timeout: 15s
  - name: gtd-archive
    sandbox:
      read:  [vault:current, vault:waiting, vault:maybe, vault:projects]
      write: [vault:current, vault:waiting, vault:maybe, vault:projects, vault:archive]
      network: none
      timeout: 15s
views:
  - id: capture
    title: Capture
    icon: plus
    permissions:
      read:  []
      write: [inbox_item]
  - id: clarify
    title: Clarify
    icon: filter
    permissions:
      read:  [inbox_item]
      write: [next_action, project, waiting_for, someday, inbox_item]
  - id: next-actions
    title: Next
    icon: list
    permissions:
      read:  [next_action]
      write: [next_action]
  - id: weekly-review
    title: Review
    icon: refresh
    permissions:
      read:  [inbox_item, next_action, waiting_for, someday, project]
      write: []
---

# gtd

The workflow layer (PLAN.md Phase 6). Built last because it depends on every
other phase, and because real Phase 1 use shapes its design. Supersedes the
`tasks` and `inbox` extraction plugins — it owns the richer GTD entity model.

Reference: David Allen, *Getting Things Done*. The five steps are capture,
clarify, organize, reflect, engage — implemented in that order, each useless
without the one before it.

## Entity types

Directories use the user's own state vocabulary (Phase 9), not GTD jargon.

| Type | Path | State | Meaning |
|---|---|---|---|
| `inbox_item` | `inbox/` | inbox | Unprocessed. Has no other property. |
| `next_action` | `current/` | current | Single physical action, has a context. |
| `waiting_for` | `waiting/` | waiting | Delegated, has a person and a date. |
| `someday` | `maybe/` | maybe | Not now, reviewed weekly. |
| `archived` | `archive/` | archive | Done or dropped. Kept for search, out of active views. |
| `project` | `projects/` | — | Outcome requiring more than one action; links its items. |

Contexts are tags: `@computer`, `@errands`, `@home`, `@calls`, `@agenda/<person>`.

## Skills

- **gtd-clarify** — the highest-value skill. Takes one inbox item and proposes
  a classification (actionable? single action or project? context, time/energy,
  delegate/defer). **Proposes; never commits** — the user confirms via the
  clarify view or `gtd-file`. A wrong auto-filed item is worse than an
  unprocessed one.
- **gtd-weekly-review** — the review checklist: empty inbox, stale projects
  with no next action, overdue waiting-fors, someday untouched 90+ days,
  calendar look-back and look-ahead.
- **gtd-next** — ranks next actions by context, available time, and energy.
- **gtd-file** — commits a confirmed clarification: creates the target entity
  and deletes the inbox line. When filed under a project, links the new item
  into that project's `items`. Deterministic; no model.
- **gtd-archive** — moves a done or dropped item into `archive/` (a monthly
  done-log), removing it from the active `current/waiting/maybe` views without
  losing it from search. Deterministic; no model.
