---
name: calendar
version: 1.0.0
description: iCalendar events under calendars/, shared with Radicale.
api: 1
entities:
  - type: event
    schema: entities/event.json
    path: calendars/
    suffix: .ics
skills:
  - name: agenda
    sandbox:
      read: [vault:calendars, vault:tasks]
      write: []
      network: none
      timeout: 10s
---

# calendar

Extracted from the Phase 1 core (PLAN.md Phase 3). Plain `.ics` files under
`calendars/<name>/`; the same directory Radicale serves to CalDAV clients.
Event identity is the iCal UID. Uninstalling the plugin leaves every file
in place.
