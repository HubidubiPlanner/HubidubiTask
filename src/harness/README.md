# HubidubiTask Harness

Claude Code as an agent the HubidubiTask apps reach by IP and port. It runs on
any computer at home that has Claude Code: Linux or macOS, Python 3.9 or later,
and `openssl` once for its certificate. Nothing else to install.

To set it up as a service, see [`deploy/harness/`](../../deploy/harness). To
run it by hand:

```sh
python3 hubidubitask_harness.py              # https on port 8765
python3 hubidubitask_harness.py --port 9000 --config ~/my-harness
```

## What the apps send

```
GET  /v1/models            {"data": [{"id": "claude-code"}]}, or 503 without Claude Code
POST /v1/chat/completions  {"messages": [...],
                            "hubidubitask_tools": true,
                            "hubidubitask_items": [{"id", "type", "fields"}, ...]}
                        -> the OpenAI answer, plus
                           "hubidubitask_actions": [{"op": "add_task", "text", "project", "due"},
                                                    {"op": "capture", "text"},
                                                    {"op": "mark_done", "id"}]
```

Each question is one `claude -p` run in Claude Code's restricted mode, in a
fresh folder that is deleted afterwards.

- **With tools** (chat and reviews): Read, Glob and Grep over a Markdown copy
  of the items, WebFetch, subagents (Task), the items as MCP tools (search,
  upcoming, projects, project, inbox, add_task, capture, mark_done), and the
  servers in `mcp.json`. The item tools change nothing here; changes come back
  as actions and the app applies them.
- **Without tools** (feeds): every tool off, and no items.

Only private, link-local, loopback and 100.64/10 addresses are answered. The
certificate is self-signed; the apps pin it at the first answer after Save.

## Settings

`~/.config/hubidubitask-harness/` (or `--config`):

| File | What it does |
|---|---|
| `harness.env` | `KEY=VALUE` lines added to Claude Code's environment, e.g. `ANTHROPIC_BASE_URL` for a local model |
| `mcp.json` | `{"mcpServers": {...}}`: more MCP servers, in Claude Code's format |
| `tls/` | The certificate and key, made on first start |

## Tests

```sh
python3 -m unittest -v
```

They run the server against a stand-in for `claude` that starts the item tools
over MCP the way Claude Code does.
