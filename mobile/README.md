# prodos-mobile

Phase 5 of the prodos plan: a full offline mirror in the pocket, hosting plugin
views, syncing only on the LAN. A **hybrid** — Kotlin native shell + WebView UI
— because the native side owns capabilities a WebView cannot reach.

## Modules

- **`core/`** — pure-Kotlin JVM library: the model, conflict resolution, the
  durable outbox, and the sync engine. No Android dependency, so its JUnit
  tests run on the JVM without an emulator — this is where the app's most
  important correctness is proven.
- **`app/`** — the Android application: everything that needs the framework.
- **`web/`** — the core WebView UI (authored under `app/src/main/assets/web`).
- **`fastlane/`** — F-Droid metadata (Phase 7 distribution).

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

The WebView owns all rendering — core UI and plugin views alike — set up via
`WebViewAssetLoader` (https origin) and bridged with `WebMessagePort`, never
`addJavascriptInterface`.

## Building

```sh
cd mobile
./gradlew :core:test          # JVM unit tests — conflict/sync/outbox correctness
./gradlew :app:assembleDebug  # debug APK
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
cycle, are covered in `core/src/test`.

## Verified vs. on-device

Built and tested here: `:core` JVM suite (20 tests) green; debug and release
APKs assemble and lint clean. Device-level acceptance from the plan — airplane
mode UX, `mitmproxy` unreadability, cert-mismatch refusal end to end, and
home-screen widget capture with the app never opened — needs an emulator/device
and is exercised as instrumented tests in CI (Phase 7).
