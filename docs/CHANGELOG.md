# Changelog

## 3.1.2 — 25 October 2026

- **Pairing finds the computer where Bonjour doesn't get through.** The phone
  or tablet also knocks on the sync port at each address of its Wi-Fi, so
  mesh and guest networks and tablets that drop Bonjour pair too. While it
  looks it says when it has found the computer and is waiting for the code
  there, and an address typed during the search is used at once, with or
  without `http://` or the port.
- **A computer with a VPN is found again.** The Mac and Linux apps announced
  themselves on one network only, sometimes the VPN's; now on every home
  network, and again whenever it changes. The address shown to type is the
  Wi-Fi's or Ethernet's, not the VPN's.
- **Sync finds a computer whose address changed**, also where Bonjour doesn't
  get through.

## 3.1.1 — 23 October 2026

- **Next week as the due date for new tasks.** **Settings → Due date for new
  tasks** now offers Tomorrow (still the default), **Next week** and No date.
  Next week is seven days on, the first day under "Next week" in the Month
  view.

## 3.1.0 — 22 October 2026

- **Free and open source**, under the GNU GPL, version 3 or later. The
  source is on GitHub: <https://github.com/HubidubiPlanner/HubidubiTask>.
- **The default theme is now called Ivory.** Same colours as before.
- **The Pro sign-up button is gone.** Everything is free.
- **The self-hosted AI server choice is gone.** A phone or computer that used
  it switches to Claude.
- **The Agent finds Ollama.** Type the IP of the computer it runs on in
  **Settings → AI Agent → Agent** and leave the port empty: the app finds it
  on 11434 and reviews are answered by the first model it lists. Start Ollama
  with `OLLAMA_HOST=0.0.0.0` so other devices can reach it.
- **Import from todo.txt** (Simpletask, Markor): **Settings › Import ›
  todo.txt**, then pick the file. Open tasks come in with their project and
  due date; the file never leaves the device.
- **Feeds can watch an RSS or Atom feed**, not only a web page: **Feeds → +
  → An RSS or Atom feed** and its address. When new entries appear, the
  period's one task says what's new; new dates alone are not a change.
- **Just after midnight, Incoming shows the new day.** For an hour or two it
  showed the day before, by UTC's clock.
- **A smaller Android app**: about 37 MB instead of 53 MB, as unused code is
  left out of the build.
- **The Mac app opens again.** 3.0.0 and 3.0.1 stopped at start with "Failed
  to load font": the Java the app brings since 3.0.0 starts it in a way that
  hid the app's own files from it.

## 3.0.1 — 20 October 2026

- **A new default theme**: ivory paper, near-black words, terracotta links
  and buttons, light or dark as the system is. A theme chosen before stays;
  Sand is still in **Settings → Theme**.
- Everything from 3.0.0 below; coming from 2.4.1, pair the phone and the
  computer once more after both are updated.

## 3.0.0 — 20 October 2026

- **Pair once more.** After updating both the phone and the computer, pair
  them again (**Settings → Devices → New device**): pairing and sync are new
  underneath and older pairings no longer work. Nothing is lost; the next
  sync picks up where the last one stopped.
- **The pairing code can't be stolen.** The same 8-character code now works
  as the password of a PAKE (J-PAKE): someone on the Wi-Fi learns nothing
  from watching or from posing as the computer, and five wrong tries close
  the code on either device.
- **Sync is sealed, post-quantum.** Every sync round gets fresh keys from
  the pairing key and an ML-KEM-768 exchange, so a recording of today's
  traffic stays closed even to a future quantum computer, and a key stolen
  later can't open earlier rounds. Photos and files travel the same way, in
  chunks. The computer answers only addresses on the home network and turns
  away an unknown phone before reading what it sends.
- **Post-quantum TLS.** The phone (bundled Conscrypt) and the computer (now
  on Java 27) offer X25519MLKEM768 on every connection: Claude, feeds,
  imports and the sync link.
- **Keys kept where the system keeps secrets.** On the phone each
  computer's sync key is sealed by the Android Keystore; on the computer the
  keys of paired phones are in the Keychain (Mac) or the Secret Service
  (Linux), and saving a key no longer puts it on a command line. The
  computer's data folder is readable by your user only.
- **Feeds and calendar links over HTTPS only.** An `http://` address is
  asked for over HTTPS, redirects never go back to plain HTTP, and nothing
  is fetched from your home network.
- **Backup passwords: at least 12 characters.** Older backups still open.

## 2.4.1 — 19 October 2026

- **Sign up for Pro is counted.** The press now reaches the developer's
  "HubidubiTask Pro" form (platform, app version, language; nothing else),
  and says what really happened: thanks once counted, "try again in a
  moment" when it didn't go through, never a false "you're offline".

## 2.4.0 — 19 October 2026

- **Settings as a table.** Due date for new tasks, Language, Theme and
  Devices are one table without frames: each name on the left, what it is
  set to on the right, a thin line between rows; a row opens its list. The
  AI Agent stays under the table as before, with its heading, list and
  fields. Then Export and Import side by side, and the Pro button. On the
  computer the column keeps to a readable width.
- **Unpair from the list.** In Devices each entry has its name, when it last
  synced, a tick once it has, and an unpair button on its right, in columns
  that line up. Nothing is left under the table: on the phone **Sync now**
  is an entry of the list too.

## 2.3.0 — 19 October 2026

- **Paired devices last.** Settings runs Due date for new tasks and notes,
  AI Agent, Language, Color theme, then Paired devices; after them only
  Export and Import, and the Pro button at the very end.
- **Pro is one button.** The Pro heading, card and I want Pro button are
  one button at the end of Settings whose words say what Pro is: "Sign up
  for Pro: sync with your Mac and Linux computers" on the phone, "…sync
  this computer with your phone" on the computer. Pressing it still sends
  only the one count; then a line of thanks takes its place.

## 2.2.0 — 18 October 2026

- **Color themes.** **Settings › Color theme** offers five, on the phone and
  the computer: Sand, the app's own, following the system's light or dark
  and with more contrast again; Paper, black on white; Night, white on black;
  Forest, deep green on pale green; Ocean, white on deep navy. In each, body
  text is 7:1 or better against the page and cards, and borders 3:1. The
  phone's status bar icons follow the theme.
- **More than one device.** A phone can now be paired with several computers,
  as a computer already could with several phones; each is synced in turn, so
  what is written on one reaches them all. A phone paired before keeps its
  computer. **Settings › Paired devices** lists them, each with when it last
  synced on its right, and **New device** among them to pair another; the
  one chosen has Sync now (on the phone) and Unpair under the list.
- Settings opens with **Due date for new tasks and notes**, then AI Agent,
  Paired devices and Language, evenly spaced (each heading had an extra
  margin). The AI Agent's fields stand without titles, their placeholders
  saying what goes in: the Claude key, the server address, "Model: fast"
  (the AI server's model field stays empty unless another is chosen), and the
  agent's IP address, port, and token or password.
- **Claude is the default AI agent**, and first in the list.
- **No Save in Settings › AI Agent**: a change is kept a moment after it is
  made, then checked, and the line under the fields says what answered. The
  line under them no longer shows a server address before anything is
  saved; with Claude chosen it showed the AI server's, and with the AI
  server chosen it repeated the field.

## 2.1.0 — 17 October 2026

- **Settings, made clearer**, on the phone and the computer: each setting is a
  dropdown under its heading, showing what it is set to. **AI Agent** (was
  AI) comes first, asking only for what the chosen one needs; the Model field
  is for the self-hosted AI server alone (Claude uses the app's model, an
  OpenAI-shaped server answers with the first model it lists). Then **Pair device**: on the phone
  the computer it is paired with, Sync now and Unpair, or Pair with a
  computer; on the computer the phones, Pair a phone, and Unpair for each
  (the computer could not unpair a phone before). Then the due date for new
  items, the language, Export and Import, and Pro last.
- **More contrast**: the same sand and moss, with darker words and greys,
  borders that show, cards clearly lighter than the page, and deeper buttons;
  in the dark theme a deeper page and brighter words. Body text, grey text and
  buttons now pass WCAG AA in both themes.

## 2.0.0 — 16 October 2026

- **Settings › Export**, above Import, on the phone and the computer:
  everything in the app as one file, every project, task done or not, note,
  event and feed with its review, and the photos, voice memos and files they
  hold, sealed with a password you choose (AES-256-GCM, the key drawn from the
  password with PBKDF2-HMAC-SHA256, 600 000 rounds). Without the password the
  file can't be read or opened, and a file changed or cut short is refused
  whole. Nothing of the device goes in: no keys, tokens, pairing or settings.
- **Settings › Import** (was "Import from another app") now also reads such a
  file back, **HubidubiTask backup**, on any device: it asks for the password,
  shows what it would add or replace, and takes only what is newer than what
  is there, so nothing newer is lost and deleted items stay deleted.

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
  are, in this order, a self-hosted AI server (the default), **OpenAI** (was
  Model), Claude and **Agent** (was Harness). Reviews are **Review with AI**. Saved settings carry over unchanged.
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
- **Four agents:** Claude (your API key), a self-hosted AI server, Model (any
  OpenAI-compatible server) and Harness (Claude Code on your computer, on
  your own Claude plan; a paired phone uses it through the computer). The
  Harness can read your items, use them as tools, fetch pages and use
  subagents; it never runs commands. Any item can be reviewed by the agent.
- **One typeface,** IBM Plex Sans, everywhere; the phone is portrait only.
- Photos go through the phone's camera app without a camera permission.

Builds: Android `versionCode 2`; macOS `HubidubiOS-1.7.0.dmg`; Linux
`hubidubios-1.7.0-1-x86_64.pkg.tar.zst`.
