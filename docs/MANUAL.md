# HubidubiTask — Manual

HubidubiTask runs on your phone (Android) and on your computer (macOS or Linux).
Each keeps a full copy of your items and works on its own; paired, they keep
each other current over your own Wi-Fi.

## Install

- **Phone:** from Google Play, or `./gradlew :android:installDebug` while developing.
- **Mac:** `HubidubiTask.app` (build it with `./gradlew :desktop:createDistributable`).
- **Linux, any distribution (x86_64):** `HubidubiTask-x86_64.AppImage`. Make it
  runnable (`chmod +x HubidubiTask-x86_64.AppImage`) and start it; nothing is
  installed. If a firewall is on, let your home network reach the app: TCP
  5244 (sync) and UDP 5353 (finding it), for example
  `sudo ufw allow from 192.168.0.0/16 to any port 5244 proto tcp`.
- **Linux (Arch, Omarchy):** `sudo pacman -U hubidubitask-*.pkg.tar.zst`, or use
  the Omarchy image from `deploy/omarchy/`, which has the app installed. The
  package opens the sync port (5244) to private networks in Omarchy's firewall.

## Pair the phone with the computer

Both on the same Wi-Fi. On the phone: **Settings → Pair with a computer**; on
the computer: **Settings → Pair a phone**. One of them shows a code, you type
it on the other. From then on they sync every minute while the app is open,
and the phone also hourly in the background.

## Pro

Pro isn't sold yet. It would be syncing with your Mac and Linux computers.
**Settings → Pro → I want Pro** says you would want it: the app sends one
answer (the platform, the app's version and its language, nothing else) so
the people who want Pro can be counted. Once sent, the button goes and the
card says thanks. Everything else, syncing included, stays free.

## Everyday use

- **Incoming**, the first tab and where the app opens: today, this week or this
  month. A long press marks a task done.
- **+** on Incoming and on Projects, in the same place: a project, a task, a note,
  a photo, a picture from the gallery, a voice memo or a file. On the computer
  you can also drop a file on the window.
- **Hold the +** for a note straight away: the cursor is already in it, and
  Done (Return on the computer) saves it.
- **New note on the home screen** (phone): the app's H with a + beside it. Long-press
  the app's icon and drag **New note** out, or add the **New note** widget. A tap
  opens the note over the home screen, the cursor in it; Done saves it and the
  app never opens.
- **Nothing asks for a date.** Every new task and note is due tomorrow, so it
  comes back on Incoming the next day; **Settings → Due date for new tasks and
  notes** switches that to **No date**, kept on that device. The widget and
  the share menu follow it too. To date one differently, long-press it and
  choose **Date**.
- **Feeds:** **+ → A web page**, the address, **Day / Week / Month**, and a
  project (or none, for the inbox). The first look only remembers the page;
  after that, once per period, a change becomes one task, written by Claude.
- **Review:** long-press any item, then **Review with AI**. The AI sums up what
  happened in it: in a project, from all its tasks (done ones too) and notes; in
  a task, from its text and the notes under it; in a note, from its words. The
  summary is kept under the item's title, in the long-press sheet and when you
  open it, cut to a few lines until you tap it. Once anything it read changes,
  it is marked **Not up to date**, and the next review asks again. An item
  never reviewed shows nothing there.

## A copy of everything

**Settings › Export to a file** saves everything in the app as one file: your
projects, tasks (done ones too), notes, events and feeds, with their reviews,
and the photos, voice memos and files in them. Keys, tokens, pairing and
settings stay on the device. **Settings › Import from another app ›
HubidubiTask backup** reads such a file back, on the phone or the computer:
it shows what it would add or replace, and takes only what is newer than what
is there.

## Language

**Settings → Language:** English, Polski, Deutsch, Français or Español. The
screens change at once, and the choice is kept on that device: the phone and
the computer each have their own. Until you choose, the app speaks the
system's language if it is one of these, and English otherwise. Reviews with
AI answer in the chosen language; a feed's task is written in the language of
its page.

## AI

**Settings**, under **AI**, chooses one of four:

| Choice | What to enter |
|---|---|
| SelfHostedAI | The server's address. Chosen until you pick another. |
| OpenAI | The address of a server that speaks OpenAI's API, and the model's name |
| Claude | An API key from the Anthropic Console (kept in the system's keychain) |
| Agent | The IP of the computer where your own agent runs, its token or password, and its port if it isn't the usual one |

**Agent** is an AI agent you already run on a computer at home. Nothing of
ours is installed there. The app recognises, from the address alone:

| Agent | Turn on its network mode | Port |
|---|---|---|
| OpenClaw | Gateway bound to the LAN, chat completions endpoint on, a gateway token | 18789 |
| Hermes Agent | `API_SERVER_ENABLED=true API_SERVER_HOST=0.0.0.0 API_SERVER_KEY=… hermes gateway` | 8642 |
| opencode | `OPENCODE_SERVER_PASSWORD=… opencode serve --hostname 0.0.0.0` | 4096 |
| Kilo | `KILO_SERVER_PASSWORD=… kilo serve --hostname 0.0.0.0 --port …` | the one you give |

Any other server that speaks OpenAI's chat API works too, with the first model
it lists. Save in **Settings** finds which agent answers and says so; with an
empty port it tries the usual ones. HTTPS is tried first and its certificate is
pinned; without HTTPS the app talks only to addresses in your home network.
The token opens that computer (the agent can run commands there), so keep it
long and keep it at home.

With a review, the app sends the question with a text copy of your projects,
open tasks, notes and calendar. The agent answers with whatever model and
tools it has. If you ask it to add a task, capture something or mark a task
done, it says so in its answer and the app makes those changes itself, after
checking each one fits your items. opencode and Kilo answer in a fixed JSON
shape, and the app deletes each question's session afterwards.

Feeds never go to your agent: a feed's text is a stranger's web page. They
write their task with Claude.

## Where your data lives

- **Phone:** in the app's own encrypted database.
- **Computer:** `~/.local/share/hubidubitask/` (Linux, the AppImage too) or
  `~/Library/Application Support/HubidubiTask/` (Mac): an SQLite database,
  attachments by hash, and the TLS identity phones pin.
