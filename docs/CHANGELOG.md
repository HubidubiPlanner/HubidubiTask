# Changelog

## Unreleased

- **Settings › Export to a file**, above Import, on the phone and the
  computer: everything in the app as one file (a ZIP), every project, task
  done or not, note, event and feed with its review, and the photos, voice
  memos and files they hold. Nothing of the device goes in: no keys, tokens,
  pairing or settings. **Import › HubidubiTask backup** reads it back on any
  device, taking only what is newer than what is there, so nothing newer is
  lost and deleted items stay deleted.

## 1.2.0 — 16 October 2026

- **Settings → Pro.** Pro isn't sold: the section says what it would be
  (syncing with your Mac and Linux computers) and has one button, **I want
  Pro**. Pressing it sends one answer to the app's own form (platform,
  version, language, nothing else) so the people who want it can be counted,
  then thanks you; it asks only once. On the phone and on the computer.
- **Linux: an AppImage.** One file, `HubidubiTask-x86_64.AppImage`, runs on
  most x86_64 distributions without installing, with its own Java runtime;
  the Arch package stays. Both are made from one Linux build
  (`distribution/linux/app-image.sh`), the AppImage by
  `distribution/appimage/build.sh`.
- On Linux, an API key or agent token is no longer lost when the keyring is
  locked or not running: it goes to a file only you can read, and moves into
  the keyring on the next save once there is one.
- **The tick sits right above the H**, and New note's + too, everywhere the
  logo is: the app icon and its themed version, New note's shortcut and
  widget, the Mac and Linux icons, the website's favicon, icon and headline,
  the Play icon and feature graphic (and the site's link preview). The H and
  its badge are drawn a little smaller together, so they stay inside every
  launcher's mask; on themed icons the bare tick or + is drawn larger.
- **New note on the home screen.** The app's H with a + beside it, as a
  shortcut of the app icon (long-press it, drag New note out) and as a 1×1
  widget drawn like a themed icon: a black circle with the H and + in white,
  the size of the icons around it, no name under it. A tap opens the note
  sheet over the home screen with the cursor in it; Done saves it with the
  date Settings gives new notes, and the app never opens. The shortcut is
  named in the app's language.
- The home-screen Capture widget and capture from another app's share menu
  saved on the main thread, which the database refuses: they crashed instead
  of saving. All three now save in the background and say so once saved.

## 1.1.0 — 14 October 2026

- **A review is a summary of what happened, kept under the title.** Review
  with AI now asks for what happened in the item: a project's from all its
  tasks (done ones too) and notes, a task's from its text and notes, a note's
  from its words. The answer stands under the title in the long-press sheet
  and in the item's sheet, four lines until tapped, and nothing is shown for an
  item never reviewed (no more "Nothing yet."). It carries a fingerprint of what
  it read: a task done, added, edited, moved or deleted under it marks it **Not
  up to date**, and the next review asks again instead of showing it. Opening
  a review no longer sends the question twice.
- **New tasks and notes are due tomorrow, and nothing asks for a date.**
  Whatever you make (a task, a note, a photo, memo or file, a capture from
  the widget or the share menu) is due the next day, so it comes back on
  Incoming. Settings › Due date for new tasks and notes chooses Tomorrow,
  the default, or No date, on each device. The date row is gone from the
  task sheet and from "Task or note?"; a long press still dates anything.
- Settings lines everything up: the cards, fields, buttons and choices now
  start and end on the same edge as the headings, on the phone and the
  computer.
- **Hold the + for a note.** A long press on the + (Incoming and Projects,
  phone and computer) skips the menu and opens a note with the cursor in it;
  Done, or Return on the computer, saves it. A note is now two taps, not
  three. A note started from the menu has the cursor in it too.
- **The Agent plugs into the agent you already run at home.** Settings › AI ›
  Agent takes the computer's IP, a token or password, and a port only if it
  isn't the usual one. The app finds which agent answers: OpenClaw's gateway,
  Hermes Agent's API server, opencode's or Kilo's server, or any server that
  speaks OpenAI's chat API. Nothing of ours is installed on that computer
  any more: the Harness, its installer and the Agent download are gone, and
  with them the tie to Claude Code.
- Reviews send a text copy of your projects, open tasks, notes and calendar
  with the question; the agent uses its own model and tools. Changes it was
  asked for (add a task, capture, mark done) come back in a fixed shape and
  are checked against your items before the app makes them. A reply that
  changed something is no longer asked again, which could apply its changes
  twice.
- HTTPS is tried first and its certificate pinned; without it, the app talks
  only to addresses in your home network. On Android that needed plain HTTP
  allowed, which also lets an OpenAI-compatible server or a feed on the home
  network use it.
- **Feeds always write their task with Claude.** A feed's text is a stranger's
  web page, and your own agent can run commands. Feeds set to the Agent before
  now use Claude, and need a Claude key in Settings.

## 1.0.0 — 9 October 2026

HubidubiTask starts on Google Play as a new app, so the version starts again
at 1.0.0 (the one before it was 1.8.0); versionCode carries on at 4.

- **The Play package is `com.saferendering.hubidubitask`**, after the app's
  name (was `com.saferendering.hubidubios.projects`). On Android, 1.0.0 is
  therefore a new app beside 1.8.0, not an upgrade of it: pair it with the
  computer and the items come over; then 1.8.0 can be removed.
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
