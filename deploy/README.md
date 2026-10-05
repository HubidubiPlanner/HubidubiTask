# Distribution (Phase 7)

Bootable media and the thin-client image. **Start only after two months of
daily use** (PLAN.md). None of this builds on the macOS dev machine — ISO
builds need a privileged Linux host (loop devices), QEMU needs KVM. It all runs
in CI on the self-hosted workstation runner; the files here are authored and
syntax-checked, not built here.

## Delivery model — an ADD-ON to stock Omarchy (current)

HubidubiOS is **Omarchy, unchanged, with our layer added on top** — not a forked
or rebuilt ISO. The user installs **official Omarchy** (its own ISO, pristine),
then runs our one-command add-on. Omarchy is never limited or reconfigured; our
layer is purely additive and reversible.

1. On any Arch box: `./distribution/pacman/build-repo.sh` builds every hubidubios package
   (`src/workstation/*` + `src/plugins/{gtd,calendar}`, `hubidubios-desktop` and the metapackages included)
   into a **private local repo** (`./repo/`, `repo-add` db). Never published —
   consumed by pacman over `file://` only.
2. On the live Omarchy system: `./deploy/workstation/addon/hubidubios-install [repo-dir]` registers
   that repo, `pacman -S hubidubios-workstation hubidubios-plugins-default`, runs
   `hubidubios-firstboot` (seeds `~/vault`, enables our per-user services, APPENDS
   the Hyprland keybind `source` line, prints the Quickshell import), and copies
   our MOTD banner. Nothing Omarchy ships is edited or removed.
3. Remove everything: `pacman -R hubidubios-workstation hubidubios-plugins-default
   hubidubios-desktop` → pristine Omarchy.

Our desktop integration ships **inside the `hubidubios-desktop` package**
(`/etc/hubidubios/{hypr,quickshell}`, the helper bins, per-user systemd units),
so it reaches the installed system via pacman — the correct seam for an
Omarchy install (which pacstraps a clean target).

**No network restrictions.** The old `nftables` egress lockdown and cloud-widget
stripping have been removed — HubidubiOS does not limit Omarchy's networking or
apps. Inference points at the user's selfhostedai OpenAI endpoint via `[inference]` in
`~/vault/.hubidubios/config.toml` (`tls_fingerprint` pins a self-signed https
cert); `hubidubios-agent status` shows the connection.

## DEPRECATED build paths (kept for reference)

`workstation/build.sh` (fork the omarchy-iso toolchain) and
`workstation/build-plain.sh` (own-the-whole-surface releng console image) are
**no longer the delivery model** — the add-on above is. `build.sh` is
architecturally unsound for its goal (it overlays the *installer*, not the
installed system). `build-plain.sh` still produces a standalone console ISO but
is out of date after the `hubidubios-desktop` package refactor. Use `deploy/workstation/addon/`.

## thin/ — the aarch64 thin client

No Arch ARM ISO (community-maintained, often stale; Manjaro ARM is wound down).
`provision.sh` turns a fresh **Raspberry Pi OS Lite** or **Alpine aarch64**
install into a thin client: SSH, mosh, tmux, Avahi, and a login that drops
straight into a `tmux` session on the workstation over `mosh`. Optionally
Moonlight for desktop streaming.

```sh
sudo deploy/thin-client/provision.sh workstation.lan
```

## CI and smoke tests

`.gitlab-ci.yml` runs the full pipeline: lint → unit → both sandboxes →
android build + instrumented → packages → ISO → QEMU smoke → publish (on tags).

- `tests/smoke/qemu_smoke.sh` boots the ISO headless, SSHes in over a user-mode
  forward, and asserts `hubidubios index rebuild`, `hubidubios capture`, and
  `hubidubios-agent ask` all succeed.
- `tests/smoke/emulator_pairing.sh` boots the Android emulator, installs the
  APK, starts `hubidubios-sync`, and drives the `PairingInstrumentedTest` to assert
  pairing completes end to end.

Publishing (`tools/publish-release.sh`) signs the APK and updates the LAN
F-Droid repo and pacman repo — no external service. Signing keys stay offline.
