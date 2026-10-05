# HubidubiOS apps: Android and macOS

One Gradle build for the two apps people hold: the Android phone app and the
macOS desktop app. They share their model, sync and agent code through
`:shared`, which is why the Mac app lives here and not on its own.

All UI is **Jetpack Compose** (Compose Multiplatform on the Mac). No WebView,
no browser.

## Modules

- **`shared/`** (`:shared`) — pure-Kotlin JVM library: the model, conflict resolution, the
  durable outbox, and the sync engine. No Android dependency, so its JUnit
  tests run on the JVM without an emulator — this is where the app's most
  important correctness is proven.
- **`android/`** (`:android`) — the Android application. UI in `ui/` (Compose): `HubidubiOsApp`
  (nav scaffold), `Screens` (Home/capture, Next, Review, Chat, Settings),
  `HubidubiOsViewModel`. `net/AgentClient` polls `/v1/agent/health` for the native
  inference-status chip and streams `/v1/agent/ask` for online-only chat.
- **`macos/`** (`:macos`) — the Mac app. Its own SQLite store, an HTTPS peer
  server on port 5244 announced over Bonjour as `_hubidubios-peer._tcp`, and
  the same screens as the phone. Package it with
  `./gradlew :macos:createDistributable` (or `:macos:packageDmg`).

The Play Store listing and fastlane metadata live in
`distribution/play-store/`, not here.

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

## Conflict resolution (PLAN.md Phase 5)

- **Structured entities** (task, event, inbox_item): three-way field-level
  merge against the last-synced base; a field both sides changed is
  last-write-wins by `updated`. Two devices editing different fields both win.
- **Note bodies**: never auto-merged. Server body stays canonical; the local
  body is preserved as a conflict copy and surfaced in the UI.
- **Deletion beats edit**: a tombstone always wins.

All three, plus outbox durability across app-kill and the full pull→merge→push
cycle, are covered in `shared/src/test`.

## Verified vs. on-device

Built and tested here: `:shared` JVM suite (20 tests) green; debug and release
APKs assemble and lint clean. Device-level acceptance from the plan — airplane
mode UX, `mitmproxy` unreadability, cert-mismatch refusal end to end, and
home-screen widget capture with the app never opened — needs an emulator/device
and is exercised as instrumented tests in CI (Phase 7).
