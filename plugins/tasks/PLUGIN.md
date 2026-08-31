---
name: tasks
version: 1.0.0
description: Project-scoped task lists with contexts and due dates.
api: 1
entities:
  - type: task
    schema: entities/task.json
    path: tasks/
    suffix: .md
skills:
  - name: task-add
    sandbox:
      read: []
      write: [vault:tasks]
      network: none
      timeout: 10s
views:
  - id: task-list
    title: Tasks
    icon: check-square
    permissions:
      read: [task]
      write: [task]
---

# tasks

Extracted from the Phase 1 core (PLAN.md Phase 3). One markdown file per
project under `tasks/`, checkbox lines with HTML-comment metadata:

    - [ ] Design plugin manifest schema  <!-- id:t_a1b2 due:2026-09-05 ctx:@computer -->

The vault format is owned by this plugin but readable by anything that
understands markdown; uninstalling the plugin leaves every file in place.
