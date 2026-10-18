# HubidubiTask

One app for tasks, notes and feeds, on **Android, macOS and Linux**. A phone
and a computer pair once and then keep each other current over your own
Wi-Fi, with no account and no cloud. Any item can be reviewed by AI, and a
feed turns a changed web page into one task a day, week or month.

The whole picture: [`docs/ecosystem.html`](docs/ecosystem.html).

## Where things are

| Folder | What is in it |
|---|---|
| [`src/apps/`](src/apps) | The app: one Gradle build. `android/` the phone app, `desktop/` the macOS and Linux app, `shared/` what both use (items, sync, feeds, agents). |
| [`deploy/`](deploy) | Getting it out: Google Play (`google-play/`), the website with its downloads (`website/`). Numbered steps inside each. |
| [`distribution/arch/`](distribution/arch) | The Linux package for Arch and Omarchy, built in containers by `build.sh`. |
| [`distribution/appimage/`](distribution/appimage) | The AppImage for any x86_64 Linux, from the same Linux build (`distribution/linux/app-image.sh`). |
| [`site/`](site) | The public website. |
| [`docs/`](docs) | The ecosystem page and the manual. |

## AI

Settings, under AI, offers four choices, the same on every system:

| Choice | What it is |
|---|---|
| SelfHostedAI | A SelfHostedAI server, over its encrypted connection. The default. |
| OpenAI | Any server that speaks OpenAI's API, with a model you run yourself |
| Claude | Anthropic's Claude, with your API key |
| Agent | The AI agent you already run at home (OpenClaw, Hermes Agent, opencode, Kilo, or any OpenAI-shaped server), at the IP and token you type. The app finds which one it is. Nothing of ours is installed there. |

## Everyday commands

```sh
cd src/apps
./gradlew :shared:test :desktop:test          # tests
./gradlew :android:assembleDebug              # phone app
./gradlew :desktop:createDistributable        # Mac app (on a Mac)
distribution/arch/build.sh                    # Linux: the Arch package, from any Docker host
distribution/appimage/build.sh                # Linux: the AppImage, from any Docker host
sh deploy/google-play/1-app-bundle/build.sh   # the signed Play bundle, checked
sh deploy/website/1-downloads/build.sh        # website: Mac and Linux downloads
sh deploy/website/2-site/assemble.sh          # website: ready to publish
```

The last commit with the old workstation (Python services, plugins, OS
layering) is tagged `workstation-final`.
