# Deploying HubidubiOS

One folder per machine: `workstation/` (the Linux box) and `thin-client/`
(a Raspberry Pi). The phone and Mac apps are built from `src/apps/` and
published through `distribution/`.

## Workstation — the installer ISO (main route)

HubidubiOS is a **fork of Omarchy**: one installer that lays down stock
Omarchy *and* the HubidubiOS layer, with Omarchy left intact. The build
vendors three upstream checkouts and applies `hubidubios-omarchy-iso.patch`;
restoring them is described in [`workstation/iso/FORK.md`](workstation/iso/FORK.md).

```sh
deploy/workstation/iso/build-fork-in-colima.sh   # on a Mac, in Colima's x86_64 VM
```

Stage 1 builds every hubidubios package; stage 2 runs Omarchy's own ISO
builder with them injected. The result lands in
`workstation/iso/omarchy-iso/release/`. Installing and first boot are in
[`docs/MANUAL.md`](../docs/MANUAL.md).

`workstation/iso/make-image.sh`, `build-plain.sh` and `build.sh` build the
older stand-alone console image and an earlier fork attempt. They are kept
for reference, not for installing.

## Workstation — onto a stock Omarchy (alternative)

For a machine that already runs official Omarchy, the same packages can be
added on top instead of reinstalling. The user installs **official Omarchy**,
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
