# HubidubiTask — Manual

HubidubiTask runs on your phone (Android) and on your computer (macOS or Linux).
Each keeps a full copy of your items and works on its own; paired, they keep
each other current over your own Wi-Fi.

## Install

- **Phone:** from Google Play, or `./gradlew :android:installDebug` while developing.
- **Mac:** `HubidubiTask.app`, from the
  [GitHub release](https://github.com/HubidubiPlanner/HubidubiTask/releases/latest),
  or build it with `./gradlew :desktop:createDistributable`.
- **Linux, any distribution (x86_64):** `HubidubiTask-x86_64.AppImage`. Make it
  runnable (`chmod +x HubidubiTask-x86_64.AppImage`) and start it; nothing is
  installed. If a firewall is on, let your home network reach the app: TCP
  5244 (sync) and UDP 5353 (finding it), for example
  `sudo ufw allow from 192.168.0.0/16 to any port 5244 proto tcp`.
- **Linux (Arch, Omarchy):** `sudo pacman -U hubidubitask-*.pkg.tar.zst`. The
  package opens the sync port (5244) to private networks in Omarchy's firewall.

## Pair the phone with the computer

Both on the same Wi-Fi. On either one: **Settings → Devices → New device**,
then the same on the other. One of them shows a code, you type it on
the other. From then on they sync every minute while the app is open, and the
phone also hourly in the background. Pair as many as you like: a phone with
several computers, a computer with several phones. Each is synced in turn, so
what is written on one reaches all of them. **Devices** lists them, each
with when it last synced, a tick once it has, and a button on its right that
unpairs it; on the phone, **Sync now** is in the list too.

The phone (or tablet) finds the computer by itself: by Bonjour, and where
Bonjour doesn't get through (some mesh and guest Wi-Fi, some tablets) by
knocking on the sync port at each address of the Wi-Fi. A VPN on the
computer doesn't hide it. Once the phone has found the computer it says so
and waits for the code there. If it never finds it, type the address the
computer shows under its code in **Computer's address**, even while it is
looking. If the computer's address changes later, the phone finds it again by
its certificate.

The code works once, for ten minutes, and five wrong tries close it on either
device. It is never sent: both devices prove they hold it (a PAKE), so someone
on the Wi-Fi learns nothing from watching or from posing as the computer.
Everything synced afterwards is sealed with keys that change every round and
come partly from a post-quantum exchange (ML-KEM-768), inside TLS. The
computer answers only addresses on the home network.

## Everyday use

- **Incoming**, the first tab and where the app opens: today, this week or this
  month. A long press on an item, late ones too, opens what Projects offers:
  **Mark done**, **Move to a project**, **Date**, **Review with AI**, **Delete**.
- **+** on Incoming and on Projects, in the same place: a project, a task, a note,
  a photo, a picture from the gallery, a voice memo or a file. On the computer
  you can also drop a file on the window.
- **Hold the +** for a note straight away: the cursor is already in it, and
  Done (Return on the computer) saves it.
- **New note on the home screen** (phone): the app's H with a + beside it. Long-press
  the app's icon and drag **New note** out, or add the **New note** widget. A tap
  opens the note over the home screen, the cursor in it; Done saves it and the
  app never opens.
- **Voice note and Photo note on the home screen** (phone): two more widgets,
  an H with a note of music and an H with a picture. **Voice note**: tap,
  speak, **Stop**, and a text note is saved. It is written down on the phone,
  and the sound goes nowhere: by Android's own recognizer, the words showing
  as you speak, or by Whisper, built into the app, after Stop (**Settings →
  Speech to text**). Where Android can't (no recognizer on the phone, or none
  yet for the app's language), Whisper does. The AI chosen in Settings then
  tidies the words: punctuation, a title, words that were misheard; without
  one they are kept as heard. **Photo note**: tap, take a photo, and the
  chosen AI writes out the text in it, or describes it.
- **Nothing asks for a date.** Every new task and note is due tomorrow, so it
  comes back on Incoming the next day; **Settings → Due date for new
  tasks** switches that to **Next week** (seven days on) or **No date**, kept
  on that device. The widget and
  the share menu follow it too. To date one differently, long-press it and
  choose **Date**.
- **Feeds:** **+ → A web page**, the address, **Day / Week / Month**, and a
  project (or none, for the inbox). The first look only remembers the page;
  after that, once per period, a change becomes one task, written by the AI
  chosen in Settings (Claude when that is an agent that can run commands).
  Pages (and calendar links) are fetched over HTTPS only and never from your
  home network.
  **+ → An RSS or Atom feed** watches a feed's address instead: each entry is
  one line, its title and link, so new entries become the period's task and a
  feed that only changed its dates is no change.
- **Review:** long-press any item, then **Review with AI**. The AI sums up what
  happened in it: in a project, from all its tasks (done ones too) and notes; in
  a task, from its text and the notes under it; in a note, from its words. The
  summary is kept under the item's title, in the long-press sheet and when you
  open it, cut to a few lines until you tap it. Once anything it read changes,
  it is marked **Not up to date**, and the next review asks again. An item
  never reviewed shows nothing there.

## A copy of everything

**Settings › Export** saves everything in the app as one file: your projects,
tasks (done ones too), notes, events and feeds, with their reviews, and the
photos, voice memos and files in them. It asks for a password first (at least
12 characters), twice, and seals the file with it (AES-256): without the password the file can't be opened, and
no one can get it back for you. Keys, tokens, pairing and settings stay on the
device. **Settings › Import › HubidubiTask backup** reads such a file back, on
the phone or the computer: it asks for the password, shows what it would add or
replace, and takes only what is newer than what is there.

**Settings › Import › todo.txt** reads the file Simpletask, Markor and other
todo.txt apps keep. Each open line becomes a task, in its first `+project`
and due on its `due:` date; done lines (`x` first) are left out, and so is a
task already in the app. Priorities, `@contexts`, `rec:` repeats, further
projects and creation dates have no place here: the summary counts them
before you import.

## Language

**Settings → Language:** English, Polski, Deutsch, Français or Español. The
screens change at once, and the choice is kept on that device: the phone and
the computer each have their own. Until you choose, the app speaks the
system's language if it is one of these, and English otherwise. Reviews with
AI answer in the chosen language; a feed's task is written in the language of
its page.

## Theme

**Settings → Theme:** Ivory, the default (ivory paper, near-black words,
terracotta), and Sand, the app's earlier earth tones; both follow the system's
light or dark mode. Paper and Forest are always light, Night and Ocean always
dark. A theme chosen before keeps being used. Each
keeps text and borders in high contrast. The choice is kept on that device. On
the Mac, the title bar takes a new theme's light or dark the next time the app
starts.

## AI

**Settings → AI Agent** chooses one of three. There is no Save: a change is kept
a moment after you make it, then checked, and the line under the fields says
what answered.

| Choice | What to enter |
|---|---|
| Claude | An API key from the Anthropic Console (kept in the system's keychain). Chosen until you pick another. |
| OpenAI | The address of a server that speaks OpenAI's API; it answers with the first model it lists |
| Agent | The IP of the computer where your own agent runs, its token or password, and its port if it isn't the usual one |

**Agent** is an AI agent you already run on a computer at home. Nothing of
ours is installed there. The app recognises, from the address alone:

| Agent | Turn on its network mode | Port |
|---|---|---|
| OpenClaw | Gateway bound to the LAN, chat completions endpoint on, a gateway token | 18789 |
| Hermes Agent | `API_SERVER_ENABLED=true API_SERVER_HOST=0.0.0.0 API_SERVER_KEY=… hermes gateway` | 8642 |
| opencode | `OPENCODE_SERVER_PASSWORD=… opencode serve --hostname 0.0.0.0` | 4096 |
| Kilo | `KILO_SERVER_PASSWORD=… kilo serve --hostname 0.0.0.0 --port …` | the one you give |
| Ollama | `OLLAMA_HOST=0.0.0.0 ollama serve`; no token (anyone on your network can use it), and it answers with the first model it lists | 11434 |

Any other server that speaks OpenAI's chat API works too, with the first model
it lists. Once the address is typed, **Settings** finds which agent answers and says so; with an
empty port it tries the usual ones. It finds the agent without sending the
token, and sends the token only to the certificate it saw first. HTTPS is tried
first and its certificate is pinned; without HTTPS the app talks only to
addresses in your home network, and the token then crosses that network
unencrypted, so prefer an agent with HTTPS. The token opens that computer (the
agent can run commands there), so keep it long and keep it at home.

The same rule holds for every AI choice: a server on the internet must have a
certificate a known authority vouches for; a server in your home network may
have its own, which is remembered the first time and must stay the same.

With a review, the app sends the question with a text copy of your projects,
open tasks, notes and calendar, marked as data, never instructions; lines whose
words came from a web page, another app or a calendar say so. The agent answers with whatever model and
tools it has. If you ask it to add a task, capture something or mark a task
done, it says so in its answer and the app makes those changes itself, after
checking each one fits your items. opencode and Kilo answer in a fixed JSON
shape, and the app deletes each question's session afterwards.

Feeds never go to an agent that can run commands: a feed's text is a
stranger's web page. With OpenClaw, Hermes, opencode, Kilo or a server the app
can't tell chosen, feeds write their task with Claude, with your Claude key;
Ollama only answers, so it writes them itself.

## Where your data lives

- **Phone:** in the app's own encrypted database. API keys, the agent's token
  and each computer's sync key are sealed by the Android Keystore.
- **Computer:** `~/.local/share/hubidubitask/` (Linux, the AppImage too) or
  `~/Library/Application Support/HubidubiTask/` (Mac), readable by your user
  only: an SQLite database, attachments by hash, and the TLS identity phones
  pin. API keys, the agent's token and the sync keys of paired phones are in
  the Keychain (Mac) or the Secret Service (Linux; with no keyring running,
  in files only your user can read, inside that folder). Turn on FileVault (Mac) or
  disk encryption (Linux) so the folder is encrypted when the computer is off.
