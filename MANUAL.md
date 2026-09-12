# HubidubiOS — Quick Manual (what to do on which device)

Two devices work together: the **workstation** (your desktop — the hub
that holds the vault, runs the agent, and serves sync) and the **phone** (the
Android app — capture on the go, review, media notes). Everything is local; the
only outbound is the optional one-way Google Calendar pull.

---

## One-time setup

### Workstation (the HubidubiOS box)
1. Flash `hubidubios-*.iso` (balenaEtcher), boot the UEFI **"HubidubiOS live
   (NVIDIA driver)"** entry (just press Enter).
2. `archinstall` → install into free space next to Windows → create your user →
   finish, **do not reboot**.
3. `hubidubios-add` → installs HubidubiOS + the NVIDIA driver + seeds your vault
   + enables the services.
4. Reboot into HubidubiOS.

### Phone
1. Install the HubidubiOS app (the `app-debug.apk`).
2. On the workstation: `hubidubios-sync pair` → it prints a payload line.
3. In the app: **Settings → Pair workstation** → paste that line → **Pair**.
   (Both devices must be on the same LAN.)

---

## Everyday use

### On the PHONE — capture, media notes, review
- **Home = 3 round buttons** for notes, each uploaded to the workstation:
  - **Text** — type a text note.
  - **Image** — pick a photo from the gallery, or snap a new one.
  - **Audio** — record a voice memo.
- **Settings → Sync now** to push/pull immediately (it also syncs in the
  background). Media files upload over the encrypted LAN link — never the cloud.
- **Next / Review** tabs show your next actions and the weekly review.

### On the WORKSTATION — the hub (terminal)
Your vault is `~/vault`. Everything is plain markdown + media files.

**Capture & clarify (GTD)**
- `hubidubios capture "buy milk"` — drop something into the inbox.
- `hubidubios inbox` — see unprocessed items.
- Clarify/file an item into a state (via the agent skills `gtd-clarify` →
  `gtd-file`, or directly): it lands in one of the state folders below.

**Work your lists** (folders match your own words)
| Command | Folder | Meaning |
|---|---|---|
| `hubidubios next list` | `current/` | active next actions |
| `hubidubios next add "x" --project p` | `current/` | add one directly |
| `hubidubios next done <id>` | — | mark complete |
| — | `waiting/` | delegated / waiting on someone |
| — | `maybe/` | someday / maybe |
| `hubidubios archive <id>` | `archive/` | done or dropped, out of active views |

**Projects**
- `hubidubios project show <slug>` — a project and the current/waiting items
  that reference it.

**Notes (text / image / audio)**
- `hubidubios note new "title"` — a text note.
- `hubidubios note new "title" --media path/to/file.jpg` — an image or audio
  note (kind inferred from the file).
- `hubidubios note list [--kind image|audio|text]` — list notes.
- `hubidubios note show <id>` — metadata + the media file's path (open it with
  your image viewer / audio player).

**Search & agenda**
- `hubidubios search <text>` — full-text across everything.
- `hubidubios agenda [--days N]` — events + due items.

**Calendar (optional, one-way from Google)**
1. Put your **private** Google iCal URL in `~/vault/.hubidubios/config.toml`
   under `[calendar] google_ics_urls = ["…basic.ics"]`.
2. Open the `gcal_hosts` firewall exception in `/etc/nftables.conf`.
3. `hubidubios calendar pull` (or let `hubidubios-gcal.timer` do it every 15 min)
   — events appear in `hubidubios agenda` and sync to the phone.

**Sync**
- The sync server (`hubidubios-sync`) runs as a user service and binds your LAN
  IP automatically. Mint a new pairing token any time with
  `hubidubios-sync pair`.

---

## Where things live (the vault)
```
~/vault/
├── inbox/       unprocessed captures
├── current/     next actions
├── waiting/     delegated / waiting-for
├── maybe/       someday / maybe
├── archive/     done or dropped (kept, searchable)
├── projects/    project files (reference their items)
├── notes/       text/image/audio notes (the media file sits beside its .md)
├── calendars/   .ics events (incl. calendars/google/)
└── .hubidubios/ index, config.toml, sync + media-blob store (not for editing)
```
It's all plain files: usable with `vim`, `grep`, and `git` even if every other
part of HubidubiOS were deleted.

---

## Rule of thumb
- **Phone** = capture (especially photos/voice) and quick review, anywhere.
- **Workstation** = the source of truth, the agent, search, projects, calendar,
  and anything you'd rather do with a keyboard.
- They reconcile automatically over your LAN whenever both are on and paired.
