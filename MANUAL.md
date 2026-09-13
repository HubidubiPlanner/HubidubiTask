# HubidubiOS — Quick Manual (what to do on which device)

Two devices work together: the **workstation** (your desktop — the hub
that holds the vault, runs the agent, and serves sync) and the **phone** (the
Android app — capture on the go, review, media notes). It runs on a full
**Omarchy** base (HubidubiOS is a fork — Omarchy stays 100% intact, our layer is
an addition). Your vault and media stay on your machines and sync over your LAN;
inference goes to your own selfhostedai box; the only optional internet touch is the
one-way Google Calendar pull.

---

## One-time setup

### Workstation (the HubidubiOS box)
The ISO is a **single installer** — it installs Omarchy *and* the HubidubiOS
layer in one pass (no separate add-on step).
1. Flash the ISO to a USB stick, e.g. from macOS:
   `sudo dd if=hubidubios-<date>-x86_64.iso of=/dev/rdiskN bs=4m status=progress`.
2. Boot the stick in **UEFI** mode. At the GRUB menu pick **"HubidubiOS"**
   (or **"HubidubiOS (safe graphics — nomodeset)"** if you get a black screen on
   a brand-new GPU).
3. Run the installer through to the end and reboot into HubidubiOS.
4. **First login — run once:** `hubidubios-firstboot`. This seeds `~/vault`,
   enables your per-user services (sync, watcher, autocommit, calendar), wires
   the `Super+Shift+Space` capture keybind into Hyprland, and prints the
   Quickshell status-widget import line. It's idempotent — safe to re-run.
5. Point inference at your selfhostedai box in `~/vault/.hubidubios/config.toml`:
   ```toml
   [inference]
   protocol = "selfhostedai"
   endpoint = "https://<selfhostedai-host>:8443"
   ```
   Check it with `hubidubios-agent status`.

### Phone
1. Install the HubidubiOS app (the `app-debug.apk`).
2. On the workstation: `hubidubios-sync pair` → it prints a **QR code**.
3. In the app: **Settings → Pair workstation → Scan QR** → point the camera at
   the terminal. It pairs as soon as it reads the code — nothing to type.
   (Both devices must be on the same LAN.)

No camera, or scanning won't focus? `pair` also prints the payload line below
the QR — paste that whole `{"host":…}` line into the field and tap **Pair**
(`hubidubios-sync pair --no-qr` prints just the line). Pasting only the short
one-time token needs the workstation to advertise over mDNS, which it does not
do yet — so scan, or paste the full line.

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

**Ask the agent** (powered by your selfhostedai box — see setup step 5)
- `hubidubios-agent status` — inference connection status.
- `hubidubios-agent ask "what's on my plate this week?"` — one-shot question
  answered over your vault.
- `hubidubios-agent chat` — interactive session.

**Search & agenda**
- `hubidubios search <text>` — full-text across everything.
- `hubidubios agenda [--days N]` — events + due items.

**Calendar (optional, one-way from Google)**
1. Put your **private** Google iCal URL in `~/vault/.hubidubios/config.toml`
   under `[calendar] google_ics_urls = ["…basic.ics"]`.
2. `hubidubios calendar pull` (or let `hubidubios-gcal.timer` do it every 15 min)
   — events appear in `hubidubios agenda` and sync to the phone.
   (There's no egress firewall to open — HubidubiOS doesn't restrict the host's
   networking.)

**Sync**
- The sync server (`hubidubios-sync`) runs as a user service and binds your LAN
  IP automatically. Mint a new pairing token any time with
  `hubidubios-sync pair`.
- Omarchy's `ufw` denies incoming by default, which would silently drop the
  phone's connection. `hubidubios-firewall.service` opens **only** port 5233/tcp
  to the private LAN ranges at boot — every other Omarchy rule is left alone and
  the firewall stays on. Check it with
  `sudo ufw status | grep 5233`, or re-run `sudo hubidubios-sync-firewall`.
- Troubleshooting a phone that can't pair: a **timeout** means packets are being
  dropped (firewall, or the phone is on a guest network / different subnet);
  *connection refused* would instead mean nothing is listening.

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
