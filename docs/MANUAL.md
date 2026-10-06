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

- **+** on Projects: a project, a task, a note, a photo, a picture from the
  gallery, a voice memo or a file. On the computer you can also drop a file
  on the window.
- **Next:** today, this week or this month. A long press marks a task done.
- **Feeds:** **+ → A web page**, the address, **Day / Week / Month**, **Claude**
  or **Harness**, and a project (or none, for the inbox). The first look only
  remembers the page; after that, once per period, a change becomes one task.
- **Review:** long-press any item → **Review with the agent**.

## AI

**Settings → Agent** chooses one of four:

| Agent | What to enter |
|---|---|
| Claude | An API key from the Anthropic Console (kept in the system's keychain) |
| SelfHostedAI | The server's address |
| Model | The address of an OpenAI-compatible server |
| Harness | Nothing. On the computer it is Claude Code installed there; on the phone, Claude Code on the paired computer. |

**Harness** runs Claude Code in its restricted mode: it can read a copy of
your items (Read, Glob, Grep), use your items as tools (search, upcoming,
projects, inbox, add a task, capture, mark done), fetch web pages and use
subagents. It can never run commands or edit files. Feeds give it no tools.

To run the Harness on a local model instead of your Claude plan, put the
model's settings in `harness.env` in the app's data folder (`~/.local/share/
hubidubitask/` on Linux, `~/Library/Application Support/HubidubiTask/` on a Mac),
for example:

```
ANTHROPIC_BASE_URL=http://127.0.0.1:8080
ANTHROPIC_AUTH_TOKEN=local
ANTHROPIC_MODEL=glm-5.3-flash
```

## Where your data lives

- **Phone:** in the app's own encrypted database.
- **Computer:** `~/.local/share/hubidubitask/` (Linux) or
  `~/Library/Application Support/HubidubiTask/` (Mac): an SQLite database,
  attachments by hash, and the TLS identity phones pin.
