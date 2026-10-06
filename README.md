# HubidubiOS

A personal productivity system that lives on hardware you own: a Linux
workstation that holds everything, an Android phone app and a Mac app that
carry a full copy, and a thin client for the desk. The whole ecosystem, how
the parts talk and what runs where, is described in
[`docs/ecosystem.html`](docs/ecosystem.html).

## Where things are

| Folder | What is in it |
|---|---|
| [`src/`](src) | **All source code.** |
| `src/workstation/` | The workstation's Python services. Each folder is one Arch package, its PKGBUILD beside its code: `hubidubios-vault` (the files, the index, the `hubidubios` CLI), `hubidubios-sync` (the phone's change feed, port 5233), `hubidubios-agent` (the model loop), `hubidubios-plugin-host` (plugin loader and skill sandbox), `hubidubios-desktop` (the Omarchy layer: keybinds, panel, user services), `hubidubios-harness` (Claude Code served to the apps, port 5240) and `metapackages`. |
| `src/plugins/` | Plugins, also Arch packages: `gtd` and `calendar` are the default set; `tasks` and `inbox` are the older set `gtd` replaces. |
| `src/apps/` | One Gradle build for the Android app (`android/`), the Mac app (`macos/`) and the code they share (`shared/`). |
| [`deploy/`](deploy) | **Putting it on machines,** one folder per machine. |
| `deploy/workstation/iso/` | The installer ISO: Omarchy forked with HubidubiOS added (`build-fork-in-colima.sh`, see `FORK.md`), plus the seed vault it ships with. |
| `deploy/workstation/addon/` | The other route: install HubidubiOS onto a stock Omarchy. |
| `deploy/workstation/config/` | Host config the workstation needs: systemd units, Avahi, Radicale, nftables. |
| `deploy/workstation/hubidubios-update` | Rebuild and reinstall the packages on a running workstation. |
| `deploy/thin-client/` | Turn a Raspberry Pi into a terminal for the workstation. |
| [`distribution/`](distribution) | **How builds reach people.** |
| `distribution/play-store/` | The Google Play listing: texts, graphics, screenshots, fastlane metadata. |
| `distribution/pacman/` | Builds every Arch package into a private pacman repo. |
| [`site/`](site) | The public website for the phone and Mac apps. |
| [`tests/`](tests) | Unit, integration and acceptance tests for the Python side; sandbox escape tests; QEMU and emulator smoke tests; container runners. The apps' tests live with them in `src/apps/`. |
| [`tools/`](tools) | Release tooling. |
| [`docs/`](docs) | The ecosystem page, the user manual and the original build plan. |

`pyproject.toml` and `uv.lock` at the top make `src/workstation/` one uv
workspace, and `.gitlab-ci.yml` is the (currently disabled) pipeline.

## Everyday commands

```sh
uv sync && uv run pytest tests/unit tests/integration   # workstation code
cd src/apps && ./gradlew :shared:test :android:assembleDebug :macos:createDistributable
distribution/pacman/build-repo.sh                         # on Arch: all packages -> ./repo/
deploy/workstation/iso/build-fork-in-colima.sh            # the installer ISO, on a Mac via Colima
```
