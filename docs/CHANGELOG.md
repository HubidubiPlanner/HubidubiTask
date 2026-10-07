# Changelog

## Unreleased

- **Five languages.** Settings → Language: English, Polski, Deutsch, Français
  and Español, on the phone and the computer, including dates, counts and the
  home-screen widget. The screens change at once. Until one is chosen the app
  follows the system's language, or English. Reviews with AI answer in the
  chosen language.
- **Next is Incoming**, with its title at the top again (Nadchodzące, Anstehend,
  À venir, Próximos), the day or the range's dates under it, and the + in the
  same place as on Projects.

## 1.8.0 — 7 October 2026

HubidubiOS is now HubidubiTask, with the Agent on any computer at home, Next
first, and no chat.

- **The + is on Next too**, in exactly the same place as on Projects, so
  anything new starts from where the app opens. On the computer, a file
  dropped on Next is captured too.
- **New names and order in Settings.** The section is **AI**, and its choices
  are, in this order, SelfHostedAI (the default), **OpenAI** (was Model), Claude and
  **Agent** (was Harness). Reviews are **Review with AI**. Saved settings carry over unchanged.
- **Next comes first.** It is the first tab and where the apps open, and it has
  no title: the day, or the range's dates, then the tasks.
- **No Agent tab.** General questions to an agent are not what the app is for, so the
  chat tab is gone on the phone and the computer. The agent chosen in Settings
  reviews items (long-press, Review with AI), and feeds still write
  their task with Claude or the Agent.
- **The Harness is its own server, on any computer at home.** Settings asks for
  its IP and port; the desktop app no longer runs Claude Code, and the phone no
  longer goes through the paired computer for it. `sh deploy/harness/install.sh`
  sets it up as a service on Linux or macOS (port 8765). With reviews the app
  sends its items along; Claude Code reads a Markdown copy, uses them as
  MCP tools, fetches pages, uses subagents and the MCP servers in that
  computer's `mcp.json`. Tasks it adds or marks done come back and the app
  applies them. Feeds send no items and get no tools. Its certificate is pinned
  at the first answer after Save, and it answers only home-network addresses.
- **HubidubiOS is now HubidubiTask.** The app's name changes on every platform:
  the Android label, `HubidubiTask.app` on the Mac, the `hubidubitask` Arch
  package (which replaces `hubidubios` on upgrade), the site and the listing.
- The computer's data folder moves to its new name on first start
  (`~/.local/share/hubidubitask`, `~/Library/Application Support/HubidubiTask`),
  and keys saved under the old keychain name are carried over. Pairings,
  items and the certificate phones pin come along.
- Unchanged on purpose: the Play package `com.saferendering.hubidubios.projects`,
  the phone's database and keystore names, and what 1.7.0 devices use to find
  and talk to each other.

## 1.7.0 — 6 October 2026

The first release of HubidubiOS as one app on Android, macOS and Linux.

- **Linux.** The desktop app runs on Linux, packaged for Arch and Omarchy.
  An Omarchy installer image can carry it preinstalled.
- **Phone and computer.** A phone pairs with a Mac or a Linux computer by a
  typed code and keeps it current over your own Wi-Fi. No workstation, no
  account, no cloud.
- **One + for everything new:** a project, a task (into a project or the
  inbox), a note, a photo, a picture from the gallery, a voice memo or a
  file. On the computer, drop a file on the window.
- **Feeds.** Watch a web page; once a day, week or month, what changed on it
  becomes one task.
- **Four agents:** Claude (your API key), SelfHostedAI, Model (any
  OpenAI-compatible server) and Harness (Claude Code on your computer, on
  your own Claude plan; a paired phone uses it through the computer). The
  Harness can read your items, use them as tools, fetch pages and use
  subagents; it never runs commands. Any item can be reviewed by the agent.
- **One typeface,** IBM Plex Sans, everywhere; the phone is portrait only.
- Photos go through the phone's camera app without a camera permission.

Builds: Android `versionCode 2`; macOS `HubidubiOS-1.7.0.dmg`; Linux
`hubidubios-1.7.0-1-x86_64.pkg.tar.zst`.
