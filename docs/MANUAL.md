# HubidubiTask — Manual

HubidubiTask runs on your phone (Android) and on your computer (macOS or Linux).
Each keeps a full copy of your items and works on its own; paired, they keep
each other current over your own Wi-Fi.

## Install

- **Phone:** from Google Play, or `./gradlew :android:installDebug` while developing.
- **Mac:** `HubidubiTask.app` (build it with `./gradlew :desktop:createDistributable`).
- **Linux (Arch, Omarchy):** `sudo pacman -U hubidubitask-*.pkg.tar.zst`, or use
  the Omarchy image from `deploy/omarchy/`, which has the app installed. The
  package opens the sync port (5244) to private networks in Omarchy's firewall.

## Pair the phone with the computer

Both on the same Wi-Fi. On the phone: **Settings → Pair with a computer**; on
the computer: **Settings → Pair a phone**. One of them shows a code, you type
it on the other. From then on they sync every minute while the app is open,
and the phone also hourly in the background.

## Everyday use

- **Next**, the first tab and where the app opens: today, this week or this
  month. A long press marks a task done.
- **+** on Projects: a project, a task, a note, a photo, a picture from the
  gallery, a voice memo or a file. On the computer you can also drop a file
  on the window.
- **Feeds:** **+ → A web page**, the address, **Day / Week / Month**, **Claude**
  or **Agent**, and a project (or none, for the inbox). The first look only
  remembers the page; after that, once per period, a change becomes one task.
- **Review:** long-press any item, then **Review with AI**.

## AI

**Settings**, under **AI**, chooses one of four:

| Choice | What to enter |
|---|---|
| OpenAI | The address of a server that speaks OpenAI's API, and the model's name |
| Claude | An API key from the Anthropic Console (kept in the system's keychain) |
| SelfHostedAI | The server's address |
| Agent | The IP and port of the computer running the Harness (port 8765 unless you chose another) |

**Agent** is the Harness: Claude Code on a computer in your home network, any Linux or
macOS machine with Claude Code installed. Set it up there once:

```sh
sh deploy/harness/install.sh
```

It runs as a service, starts with the computer, and prints the IP and port to
type in **Settings**, under **AI**, after choosing **Agent**. Saving trusts the certificate the
Harness shows at that moment; after that only that certificate is accepted.
If you reinstall it on a new machine, save again.

With a review, the app sends your items along, and Claude Code, in its
restricted mode, can read a copy of them (Read, Glob, Grep), use them as tools
(search, upcoming, projects, inbox, add a task, capture, mark done), fetch web
pages and use subagents. The tasks it adds or marks done come back to the app,
which makes those changes itself. It can never run commands or edit files.
Feeds give it no tools and no items.

Its settings live on that computer in `~/.config/hubidubitask-harness/`:

- `harness.env` points Claude Code at a local model instead of its own login,
  for example:

  ```
  ANTHROPIC_BASE_URL=http://127.0.0.1:8080
  ANTHROPIC_AUTH_TOKEN=local
  ANTHROPIC_MODEL=glm-5.2
  ```

- `mcp.json` adds MCP servers of that computer, in Claude Code's own format
  (`{"mcpServers": {...}}`).

## Where your data lives

- **Phone:** in the app's own encrypted database.
- **Computer:** `~/.local/share/hubidubitask/` (Linux) or
  `~/Library/Application Support/HubidubiTask/` (Mac): an SQLite database,
  attachments by hash, and the TLS identity phones pin.
