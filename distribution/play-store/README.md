# Publishing HubidubiOS on Google Play

Everything needed to fill in Play Console for `com.saferendering.hubidubios.projects`, in the
order Play Console asks for it. The upload-ready texts and images live in the
fastlane layout, so they can be pasted by hand or pushed with `fastlane supply`:

```
distribution/play-store/fastlane/metadata/android/
├── en-US/                      default listing language
│   ├── title.txt               28 / 30 chars
│   ├── short_description.txt   79 / 80 chars
│   ├── full_description.txt    ~3050 / 4000 chars
│   ├── changelogs/1.txt        release notes for versionCode 1 (≤500)
│   └── images/
│       ├── icon.png            512×512, 32-bit PNG
│       ├── featureGraphic.jpg  1024×500, no alpha
│       └── phoneScreenshots/   7 × 1080×2160 JPEG (2:1, no alpha), light theme
└── pl-PL/                      Polish listing (texts; images fall back to en-US)

distribution/play-store/
├── README.md                   this file
└── graphics/                   SVG sources + render.sh for icon and feature graphic

site/                           static website, incl. privacy.html (the policy URL)
```

Re-render the icon and feature graphic after editing their SVGs:
`distribution/play-store/graphics/render.sh` (needs `brew install librsvg`).

---

## 0. Before you start — blockers

These stop an upload or a review; none of them are listing work.

- [x] **Target SDK.** `compileSdk`/`targetSdk` 36 (Play's level for new apps
      from 31 Aug 2026). Checked on a tablet running Android 16.
- [x] **16 KB pages.** Play rejects apps targeting Android 15+ whose native
      libraries assume 4 KB pages. CameraX moved to 1.4.2 for this; every
      64-bit `.so` in the bundle is 16 KB-aligned.
- [x] **Signed App Bundle.** `cd src/apps && ./gradlew :android:bundleRelease` signs
      with the PRIVATE key (`~/.keystores/hubidubitask-upload.jks`,
      alias `private`, password read from the Keychain entry
      `android-keystore-hubidubitask-upload`; CI sets `HUBIDUBIOS_KEYSTORE` and
      `HUBIDUBIOS_KEYSTORE_PASSWORD`). Still enrol in **Play App Signing** when
      creating the app: this key then acts as the upload key.
- [ ] **Version.** `versionCode = 1`, `versionName = "0.1.0"`. Every upload
      needs a higher `versionCode`; add `changelogs/<versionCode>.txt` each time.
- [ ] **The package name is permanent.** `com.saferendering.hubidubios.projects` can never be
      changed or reused once uploaded.
- [ ] **New personal developer account?** Production access requires a closed
      test with at least **12 testers opted in for 14 consecutive days** first.
      Plan that time in; the listing below can be completed during it.
- [ ] **Privacy policy URL must be live.** Host `site/` first (any static host:
      GitHub/GitLab Pages, Netlify, Cloudflare Pages, your own server) and use
      `https://<your-domain>/privacy.html`.
- [ ] **Replace placeholders in `site/`:**
      `hubidubios.example` (canonical/OG URLs), `hello@hubidubios.example`
      (contact), and `downloads/HubidubiOS.dmg` (the Mac download — build it
      with `./gradlew :macos:packageDmg`, or remove the Mac card).

---

## 1. Create app

| Field | Value |
|---|---|
| App name | `HubidubiOS: Tasks & Calendar` (listing title; the launcher label stays `HubidubiOS`) |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free (cannot be changed to paid later) |

## 2. Main store listing

| Field | Where |
|---|---|
| App name | `en-US/title.txt` |
| Short description | `en-US/short_description.txt` |
| Full description | `en-US/full_description.txt` |
| App icon | `en-US/images/icon.png` |
| Feature graphic | `en-US/images/featureGraphic.jpg` |
| Phone screenshots | `en-US/images/phoneScreenshots/*` (Play needs 2–8; order = file order) |
| Tablet screenshots | optional — skip; the app is phone-first |

Add **Polish (pl-PL)** under *Manage translations → Add your own translations*
and paste the `pl-PL/` texts. Its description ends by saying the app's
interface is English for now; drop that line once the UI is translated.

### Store settings

| Field | Value |
|---|---|
| App category | **Productivity** |
| Tags (pick up to 5) | To-do list · Task management · Calendar · Note taking · Productivity (choose the closest names Play offers) |
| Email | required, shown publicly — use the same address as the site's contact |
| Website | `https://<your-domain>/` |
| Phone | optional — leave empty |

Play forbids ranking claims and repeated keywords in title and descriptions
("best", "#1", "free", emoji runs). The texts here avoid them; keep it that way
when editing.

---

## 3. App content (Policy → App content)

### Privacy policy
`https://<your-domain>/privacy.html`

### Ads
**No**, the app contains no ads.

### App access
Choose **All functionality is available without special access**, and add
this note so the reviewer does not flag sync or the assistant as broken:

> No account or login exists. Projects, the + menu, Next and Mark done work
> entirely on the device. Two optional features need the user's own
> equipment on the same local network and cannot be reached by a reviewer:
> (1) sync with the user's HubidubiOS Mac app, paired by a typed code;
> (2) the assistant, which calls a model server whose address the user enters
> in Settings. Without them the app shows their status and every other
> feature works normally.

### Content rating (IARC questionnaire)
- Category: **Utility, Productivity, Communication, or Other**
- Violence, fear, sexuality, gambling, drugs, crude humour, language: **No** to all
- Do users interact or exchange content with other users? **No** — sync is
  only between the same user's own devices; there are no other users.
- Does the app share the user's current physical location? **No**
- Does the app allow purchase of digital goods? **No**
- Unrestricted internet / web browsing? **No**

Expected result: Everyone / PEGI 3 / USK 0.

### Target audience and content
**18 and over** (keeps the app out of the Families programme and its extra
requirements; the privacy policy already says it is not directed at children).
Does the app unintentionally appeal to children? **No.**

### News app / COVID-19 / Government / Financial features / Health
**No / not applicable** to each.

### Data safety

Play's definitions are the arbiter; skim the current *Data safety* help page
before submitting. The facts, from the code:

- The developer runs **no server**. The app has no analytics, ads, crash
  reporting or SDKs that phone home (dependencies: AndroidX, Compose, Room,
  SQLCipher, OkHttp, WorkManager, CameraX, ZXing — none of them transmit).
- Data leaves the device only (a) to the user's **own paired devices** over the
  LAN, and (b) when the user **explicitly triggers** it: an assistant question
  to a server address they entered, or a one-time import from Todoist/iCal.
- Local database is encrypted (SQLCipher, Keystore-sealed key);
  `allowBackup="false"`, so nothing goes to Android cloud backup.

**Recommended answers**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | (only asked if Yes above) Yes — pinned TLS to paired devices, HTTPS elsewhere |
| Do you provide a way for users to request that their data is deleted? | (only asked if Yes above) Yes — delete in app, or uninstall |

Reasoning for **No**: nothing is sent to the developer or to a third party the
developer chose. Sync goes to devices the user owns, and the assistant and
import calls go to endpoints the user typed in and started — user-initiated
transfers, which Play does not count as sharing.

If you prefer the conservative route, declare instead: *collected* —
**Personal info › Other (tasks and notes text)**, **Photos and videos**,
**Audio (voice recordings)**, **Files and docs**, **Calendar events**; purpose
**App functionality**; **not shared**; **processing is optional** (except
tasks/notes); **encrypted in transit: yes**; **deletion: yes**. The listing then
shows a longer data list, but it cannot be faulted.

### Permissions declarations
None needed. CAMERA and RECORD_AUDIO are ordinary runtime permissions, not in
Play's restricted list (SMS, call log, background location, all-files access,
accessibility, etc.). The foreground-service declaration does not apply — the
app uses WorkManager, not a foreground service.

---

## 4. Release

1. **Testing → Internal testing**: upload the `.aab`, add yourself, install
   from the opt-in link, sanity-check capture, Next, pairing, import.
2. **Testing → Closed testing**: the 12-tester / 14-day run if your account
   needs it. Release notes: `en-US/changelogs/1.txt`.
3. Read the **Pre-launch report** (Play's robot taps through the app on real
   devices — look for crashes on the pairing and import screens especially).
4. **Production → Create release** → roll out. First review usually takes a
   few days.

Automating later: `fastlane supply --aab android-release.aab --track internal
--metadata_path fastlane/metadata/android` uploads this whole folder
(needs a Play service-account JSON key).

---

## 5. Screenshots — what they show

Captured on an emulator with demo data (no personal content), light theme for
the store; light and dark copies are also in `site/assets/screens/`.

| File | Shows |
|---|---|
| `1_next-day.jpg` | Next, Day view: Overdue on its own, today's tasks, "3 more this week" |
| `2_next-week.jpg` | Next, Week view with the seven-day strip |
| `3_capture.jpg` | Capture: Text, Image, Audio, Document |
| `4_task-or-note.jpg` | "Task or note?" with a date set |
| `5_projects.jpg` | Projects with tasks and a note filed under them |
| `6_item-detail.jpg` | An opened task: due date, its note, Mark done |
| `7_next-month.jpg` | Next, Month view grouped by rolling week |

The demo data has no calendar events, and the Capture screen shows "Not
paired". To show events beside tasks and a "Synced" chip, pair the emulator
with the Mac app and import an .ics before re-shooting.

Optional polish that tends to lift installs: a one-line caption above each
screenshot on a sand or moss band ("Today first. Overdue on its own." ·
"Task or note? You decide." · "Synced with your Mac. No cloud."). Play allows
this as long as the screenshot itself is the real app.
