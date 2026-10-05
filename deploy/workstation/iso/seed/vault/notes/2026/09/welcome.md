---
id: n_welcome1
title: Welcome to hubidubios
created: 2026-09-03T00:00:00Z
updated: 2026-09-03T00:00:00Z
tags: [hubidubios]
---

Your vault lives here at `~/vault` — plain markdown and ics files, the source of
truth. Everything works from the terminal:

- `hubidubios capture "…"`   capture to today's inbox (offline, instant)
- `hubidubios next add "…" --project X --ctx @computer`   add a next action
- `hubidubios next list` / `hubidubios agenda` / `hubidubios search "…"`
- `hubidubios ask "what's on my plate this week?"`   the agent (needs the inference server)

Inference: the agent talks to the local selfhostedai-agent OpenAI server (see
`.hubidubios/config.toml`). Check it with `hubidubios-agent status`.

Sync a phone: `hubidubios-sync pair` shows a QR; pair the hubidubios Android app.

The index (`.hubidubios/index.db`) is disposable — `hubidubios index rebuild` reconstructs
it from these files at any time. See [[getting-started]] once you write one.
