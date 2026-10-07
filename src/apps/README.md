# HubidubiTask: Android, macOS and Linux

One Gradle build for the app on every system: the Android phone app, and the
desktop app for macOS and Linux. They share their model, sync, feeds and agent
code through `:shared`.

All UI is **Jetpack Compose** (Compose Multiplatform on the Mac). No WebView,
no browser.

## Modules

- **`shared/`** (`:shared`) — pure-Kotlin JVM library: the model, conflict resolution, the
  durable outbox, and the sync engine. No Android dependency, so its JUnit
  tests run on the JVM without an emulator — this is where the app's most
  important correctness is proven.
- **`android/`** (`:android`) — the Android application. UI in `ui/` (Compose): `HubidubiTaskApp`
  (nav scaffold), `Screens` (Home/capture, Next, Review, Chat, Settings),
  `HubidubiTaskViewModel`. `net/AgentClient` polls `/v1/agent/health` for the native
  inference-status chip and streams `/v1/agent/ask` for online-only chat.
- **`desktop/`** (`:desktop`) — the desktop app, for macOS and Linux. Its own
  SQLite store, an HTTPS peer server on port 5244 announced over Bonjour as
  `_hubidubios-peer._tcp`, and the same screens as the phone. Mac:
  `./gradlew :desktop:createDistributable` (or `:desktop:packageDmg`). Linux:
  `distribution/arch/build.sh` builds the Arch package in containers.

Publishing on Google Play, step by step, lives in `deploy/google-play/`.

## What the native shell owns (and why it can't be a pure web app)

| Concern | Implementation | Why native |
|---|---|---|
| Discovery | `net/Discovery.kt` (NsdManager) | browsers have no mDNS |
| Transport | `net/PinnedTransport.kt` (OkHttp) | pin a self-signed LAN cert by fingerprint |
| Storage | `data/*` (Room + SQLCipher, Keystore-sealed key) | mirror must survive with no WebView alive; IndexedDB is evictable |
| Background sync | `sync/SyncWorker.kt` (WorkManager) | reliable background execution |
| Secrets | `security/Keystore.kt` | hardware-backed key storage |
| Pairing QR | `pairing/*` (ZXing) | camera + no Play Services |
| Quick capture | `capture/*` (widget + share intent) | capture must never fail, no WebView wait |

## Building

```sh
cd src/apps
./gradlew :shared:test          # JVM unit tests — conflict/sync/outbox correctness
./gradlew :android:assembleDebug  # debug APK
```

Requires an Android SDK (path in `local.properties`, gitignored). The wrapper
pins Gradle 8.13; the build uses AGP 8.7.3 / Kotlin 2.0.21, compileSdk 34,
minSdk 26.

## Sync between devices

A phone and a computer (Mac or Linux) pair once with a typed code and then
keep each other current over the LAN: each side keeps a numbered log of its
changes and sends what the other has not seen yet (core `peer`). When both
changed the same item, the later edit wins, and a deletion beats an edit.
Attachments travel by their SHA-256 after the records.
