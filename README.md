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
| [`src/harness/`](src/harness) | The Harness: Claude Code as an agent the apps reach by IP and port. One Python file. |
| [`deploy/`](deploy) | Getting it out: Google Play (`google-play/`), the website with its downloads (`website/`), the Agent's installer (`harness/`), the Omarchy image (`omarchy/`). Numbered steps inside each. |
| [`distribution/arch/`](distribution/arch) | The Linux package for Arch and Omarchy, built in containers by `build.sh`. |
| [`site/`](site) | The public website. |
| [`docs/`](docs) | The ecosystem page and the manual. |

## AI

Settings, under AI, offers four choices, the same on every system:

| Choice | What it is |
|---|---|
| SelfHostedAI | A SelfHostedAI server, over its encrypted connection. The default. |
| OpenAI | Any server that speaks OpenAI's API, with a model you run yourself |
| Claude | Anthropic's Claude, with your API key |
| Agent | The Harness: Claude Code on any computer at home, at the IP and port you type. Install it there with `sh deploy/harness/install.sh`. |

## Everyday commands

```sh
cd src/apps
./gradlew :shared:test :desktop:test          # tests
(cd ../harness && python3 -m unittest)        # the Harness's tests
./gradlew :android:assembleDebug              # phone app
./gradlew :desktop:createDistributable        # Mac app (on a Mac)
distribution/arch/build.sh                    # Linux: the Arch package, from any Docker host
deploy/omarchy/build-fork-in-colima.sh        # Omarchy image with the app
sh deploy/google-play/1-app-bundle/build.sh   # the signed Play bundle, checked
sh deploy/website/1-downloads/build.sh        # website: Mac, Linux and Agent downloads
sh deploy/website/2-site/assemble.sh          # website: ready to publish
```

The last commit with the old workstation (Python services, plugins, OS
layering) is tagged `workstation-final`.
