# HubidubiOS

One app for tasks, notes and feeds, on **Android, macOS and Linux**. A phone
and a computer pair once and then keep each other current over your own
Wi-Fi, with no account and no cloud. Any item can be reviewed by AI, and a
feed turns a changed web page into one task a day, week or month.

The whole picture: [`docs/ecosystem.html`](docs/ecosystem.html).

## Where things are

| Folder | What is in it |
|---|---|
| [`src/apps/`](src/apps) | The app: one Gradle build. `android/` the phone app, `desktop/` the macOS and Linux app, `shared/` what both use (items, sync, feeds, agents). |
| [`distribution/arch/`](distribution/arch) | The Linux package for Arch and Omarchy, built in containers by `build.sh`. |
| [`distribution/play-store/`](distribution/play-store) | The Google Play listing: texts, graphics, screenshots, fastlane metadata. |
| [`deploy/omarchy/`](deploy/omarchy) | An Omarchy installer image with the app installed. |
| [`site/`](site) | The public website. |
| [`docs/`](docs) | The ecosystem page and the manual. |

## AI

Settings offers four agents, the same on every system:

| Agent | What it is |
|---|---|
| Claude | Anthropic's Claude, with your API key |
| SelfHostedAI | A SelfHostedAI server, over its encrypted connection |
| Model | Any OpenAI-compatible model you run yourself |
| Harness | Claude Code on your computer, on your own Claude plan. The desktop app runs it; a paired phone uses it through that computer. |

## Everyday commands

```sh
cd src/apps
./gradlew :shared:test :desktop:test          # tests
./gradlew :android:assembleDebug              # phone app
./gradlew :desktop:createDistributable        # Mac app (on a Mac)
distribution/arch/build.sh                    # Linux: the Arch package, from any Docker host
deploy/omarchy/build-fork-in-colima.sh        # Omarchy image with the app
```

The last commit with the old workstation (Python services, plugins, OS
layering) is tagged `workstation-final`.
