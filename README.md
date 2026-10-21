# HubidubiTask

One app for tasks, notes and feeds, on **Android, macOS and Linux**. A phone
and a computer pair once and then keep each other current over your own
Wi-Fi. Any item can be reviewed by an AI you choose, and a feed turns a
changed web page or RSS feed into one task a day, week or month. Bring your
items in once from Todoist, Google Calendar or a todo.txt file.

No account, no cloud, no server of ours. Sync runs over your own Wi-Fi,
post-quantum encrypted. AI is used only when you ask, with your own key or
your own server.

## Get it

- **Android:** [Google Play](https://play.google.com/store/apps/details?id=com.saferendering.hubidubitask).
  F-Droid is coming.
- **Mac** (Apple silicon) **and Linux** (x86_64, an AppImage or an Arch
  package): [GitHub Releases](https://github.com/hubidubitask/HubidubiTask/releases/latest).

Website: <https://hubidubitask.github.io/HubidubiTask>. How to use it:
[`docs/MANUAL.md`](docs/MANUAL.md).

## AI

| Choice | What it is |
|---|---|
| Claude | Anthropic's Claude, with your API key. The default. |
| OpenAI | Any server that speaks OpenAI's API, with a model you run yourself |
| Agent | The AI agent you already run on a computer at home (OpenClaw, Hermes Agent, opencode, Kilo, Ollama, or any OpenAI-shaped server), at the IP and token you type. Nothing of ours is installed there. |

Feeds always write their task with Claude.

## Build from source

The Gradle build is in `src/apps`. It needs JDK 17 and an Android SDK.

```sh
cd src/apps
./gradlew :shared:test :desktop:test          # tests
./gradlew :android:assembleDebug              # phone app
./gradlew :desktop:createDistributable        # Mac app (on a Mac, with a JDK 27 to bundle)
```

The Linux packages are built in containers, from the repository root, on any
host with Docker:

```sh
distribution/arch/build.sh                    # the Arch package
distribution/appimage/build.sh                # the AppImage
```

More in [`src/apps/README.md`](src/apps/README.md) and
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Where things are

| Folder | What is in it |
|---|---|
| [`src/apps/`](src/apps) | The app: one Gradle build. `android/` the phone app, `desktop/` the macOS and Linux app, `shared/` what both use (items, sync, feeds, agents). |
| [`distribution/`](distribution) | The Linux packages: `arch/` for Arch and Omarchy, `appimage/` for any x86_64 Linux, both from the app image `linux/` builds. |
| [`deploy/`](deploy) | Getting it out: Google Play (`google-play/`), the website (`website/`), F-Droid (`f-droid/`). Numbered steps inside each. |
| [`fastlane/`](fastlane) | The store texts, graphics and release notes, in English and Polish, for F-Droid and Google Play. |
| [`site/`](site) | The website. |
| [`docs/`](docs) | The manual, the changelog, and how it all fits together (`ecosystem.html`). |

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Report security problems privately,
as [`SECURITY.md`](SECURITY.md) says.

## Licence

Copyright (C) 2026 HubidubiPlanner.

HubidubiTask is free software: you can redistribute it and modify it under
the terms of the GNU General Public License, version 3 or (at your option)
any later version. See [`LICENSE`](LICENSE). The IBM Plex Sans fonts it ships
are under the SIL Open Font License 1.1.
