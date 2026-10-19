# Publishing HubidubiTask on Google Play

Everything Play Console asks for, one folder per step, in the order it asks.
Package: `com.saferendering.hubidubitask` (permanent once uploaded).
Version: 2.4.0, versionCode 11 (from `src/apps/gradle.properties`).

| Step | Folder | What you do there | State |
|---|---|---|---|
| 1 | [`1-app-bundle/`](1-app-bundle) | Build and check the signed `.aab` | Ready: `out/hubidubitask-2.4.0-11.aab` |
| 2 | [`2-store-listing/`](2-store-listing) | Paste the name, descriptions, category, contact | Ready, English and Polish |
| 3 | [`3-graphics/`](3-graphics) | Upload the icon, feature graphic, 8 screenshots | Ready |
| 4 | [`4-privacy-policy/`](4-privacy-policy) | Put the privacy policy online, paste its URL | Needs the website live (`deploy/website`) |
| 5 | [`5-app-content/`](5-app-content) | Answer the App content forms | Answers written out |
| 6 | [`6-release/`](6-release) | Internal test, closed test, production | Release notes ready |

## Before you start

- **A Google Play developer account** (one-time fee) in the name you want
  shown as the developer.
- **A contact email and a website address.** Both are public on the listing.
  Set them once in `deploy/website/2-site/site.env`; the website, the privacy
  policy and step 2 use the same values.
- **The website live** (`deploy/website`), because step 4 needs the privacy
  policy at a public address before Play lets you submit.
- **A new personal developer account** must run a closed test with at least
  12 testers for 14 days before it can publish to production. Plan for it:
  steps 2 to 5 can be finished during that time.

## What was checked

- Target SDK 36, which Play requires of new apps from 31 August 2026.
- Every 64-bit native library is 16 KB-aligned (Play rejects apps targeting
  Android 15+ that are not).
- Signed with the upload key (`CN=HubidubiPlanner`).
- No advertising ID, no foreground-service type, no restricted permissions.
