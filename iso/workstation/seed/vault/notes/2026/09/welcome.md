---
id: n_welcome1
title: Welcome to prodos
created: 2026-09-03T00:00:00Z
updated: 2026-09-03T00:00:00Z
tags: [prodos]
---

Your vault lives here at `~/vault` — plain markdown and ics files, the source of
truth. Everything works from the terminal:

- `prodos capture "…"`   capture to today's inbox (offline, instant)
- `prodos next add "…" --project X --ctx @computer`   add a next action
- `prodos next list` / `prodos agenda` / `prodos search "…"`
- `prodos ask "what's on my plate this week?"`   the agent (needs the inference server)

Inference: the agent talks to the local selfhostedai-agent OpenAI server (see
`.prodos/config.toml`). Check it with `prodos-agent status`.

Sync a phone: `prodos-sync pair` shows a QR; pair the prodos Android app.

The index (`.prodos/index.db`) is disposable — `prodos index rebuild` reconstructs
it from these files at any time. See [[getting-started]] once you write one.
