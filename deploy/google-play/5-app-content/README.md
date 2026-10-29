# 5. App content

**Policy and programs › App content**, form by form.

## Ads
No, the app contains no ads.

## App access
**All functionality is available without special access**, with this note so
the reviewer does not report sync or AI review as broken:

> There is no account or login. Incoming, Projects, the + menu, Feeds and Mark
> done work entirely on the device. Two optional features need the user's
> own equipment and cannot be reached by a reviewer: (1) sync with the
> user's HubidubiTask app on their Mac or Linux computer, paired by a typed
> code over the same Wi-Fi; (2) AI review, which calls an AI the user
> configures in Settings (their own Claude API key, their own
> OpenAI-compatible server, or the AI agent they already run at home).
> Without them the app shows their status and every other feature works.

## Content rating (IARC questionnaire)
- Category: **Utility, Productivity, Communication, or Other**
- Violence, fear, sexuality, gambling, drugs, crude humour, bad language: **No** to each
- Users interact or exchange content with other users: **No**. Sync is only
  between the same person's own devices.
- Shares the user's location: **No**
- Purchases of digital goods: **No**
- Unrestricted web browsing: **No**. Feeds fetch pages the user entered, in the
  background, and show no web content.
- AI-generated content: Yes, if asked. Reviews and feed tasks are written by an
  AI the user chose and configured; the app adds no AI of its own.

Expected result: Everyone, PEGI 3, USK 0.

## AI-generated content
Play's AI-Generated Content policy asks apps that generate content with AI to
let users report offensive output. Here the AI is one the user brings and
configures, and its review is shown only to that user, so it may not apply.
If a reviewer raises it, the answer is a "Report" action on the review sheet;
the app has none yet.

## Target audience
**18 and over.** This keeps the app out of the Families programme; the privacy
policy says it is not directed at children. Unintentionally appeals to
children: **No**.

## Advertising ID
**No**, the app does not use it (no `AD_ID` permission in the bundle).

## News, health, financial features, government
**No** or not applicable, each.

## Data safety

The facts, from the code:

- The developer runs **no server**. No analytics, ads, crash reporting or SDKs
  that send anything (AndroidX, Compose, Room, SQLCipher, OkHttp,
  WorkManager, the Anthropic SDK used only with the user's own key).
- Data leaves the phone only (a) to the user's **own paired computer** on the
  local network, and (b) when the user **starts it**: an AI review to the AI
  they configured, a photo from the Photo note widget to that same AI, a
  feed's page check, a one-time import, and a voice note's words (written
  down on the phone) to that same AI to be tidied. A voice note is written
  down on the phone by the system's speech recognizer in its on-device mode
  or by Whisper inside the app; the audio goes nowhere.
- **Foreground service** (Play asks at upload, for apps targeting Android 14+):
  type **mediaProcessing** (Android 15 on; a short service on Android 14).
  Use: writing out a voice note the user just recorded with the Voice note
  widget (speech to text inside the app with Whisper) and saving it, which
  can take minutes on a slow phone and must not stop when the user leaves the
  screen. Started only by the user's tap on Stop; shows a notification while
  it runs and stops when the note is saved.
- The local database is encrypted (SQLCipher, key sealed by the Keystore), and
  `allowBackup="false"`, so nothing goes to Android's cloud backup.
**Recommended answers**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |

Nothing reaches the developer. Sync goes to the user's own devices; reviews,
photo notes and imports go to services the user picked and configured, and
only when they ask. Play does not count user-initiated transfers to a service the user
chose as sharing.

If you prefer the conservative route, declare instead: **collected** ›
Personal info › Other (task and note text), Photos and videos, Audio (voice
recordings), Files and docs, Calendar events; purpose **App functionality**;
**not shared**; collection **optional** except tasks and notes; **encrypted in
transit: yes**; **deletion: yes** (delete in the app, or uninstall). The
listing then shows a longer list, but it cannot be faulted.

## Permissions declarations
None. RECORD_AUDIO is an ordinary runtime permission. FOREGROUND_SERVICE,
WAKE_LOCK and RECEIVE_BOOT_COMPLETED come from WorkManager, which declares no
foreground-service type, so Play's foreground-service declaration does not
apply.
