---
name: inbox
version: 1.0.0
description: GTD capture inbox, append-only, one file per day.
api: 1
entities:
  - type: inbox_item
    schema: entities/inbox_item.json
    path: inbox/
    suffix: .md
skills:
  - name: capture
    sandbox:
      read: []
      write: [vault:inbox]
      network: none
      timeout: 5s
---

# inbox

Extracted from the Phase 1 core (PLAN.md Phase 3). Append-only daily files
under `inbox/`, newest at the bottom, no structure beyond a timestamp and an
id. The inbox is a queue, not an archive: processed items are moved out and
their line deleted. Capture must never fail — no validation, no
classification at capture time.
