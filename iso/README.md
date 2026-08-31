# Distribution (Phase 7)

Bootable media and the thin-client image. **Start only after two months of
daily use** (PLAN.md). None of this builds on the macOS dev machine — ISO
builds need a privileged Linux host (loop devices), QEMU needs KVM. It all runs
in CI on the self-hosted workstation runner; the files here are authored and
syntax-checked, not built here.

## workstation/ — the x86_64 ISO

`archiso` profile derived from `releng`. `build.sh`:

1. Builds every prodos package (`packages/*` + `plugins/{gtd,calendar,…}` +
   metapackages) into a local signed repo.
2. Copies a fresh `releng` profile and overlays our `profiledef.sh`,
   `packages.x86_64`, `pacman.conf`, and `airootfs/` (which enables sshd,
   nftables, avahi, and llama via `.wants` symlinks and ships the `config/`
   files and a `prodos-firstboot` setup script).
3. Runs `mkarchiso`.

Not based on Manjaro — its package delay and its own tooling layer are both
things this project would strip anyway.

```sh
sudo iso/workstation/build.sh            # on Arch, privileged
```

## thin/ — the aarch64 thin client

No Arch ARM ISO (community-maintained, often stale; Manjaro ARM is wound down).
`provision.sh` turns a fresh **Raspberry Pi OS Lite** or **Alpine aarch64**
install into a thin client: SSH, mosh, tmux, Avahi, and a login that drops
straight into a `tmux` session on the workstation over `mosh`. Optionally
Moonlight for desktop streaming.

```sh
sudo iso/thin/provision.sh workstation.lan
```

## CI and smoke tests

`.gitlab-ci.yml` runs the full pipeline: lint → unit → both sandboxes →
android build + instrumented → packages → ISO → QEMU smoke → publish (on tags).

- `tests/smoke/qemu_smoke.sh` boots the ISO headless, SSHes in over a user-mode
  forward, and asserts `prodos index rebuild`, `prodos capture`, and
  `prodos-agent ask` all succeed.
- `tests/smoke/emulator_pairing.sh` boots the Android emulator, installs the
  APK, starts `prodos-sync`, and drives the `PairingInstrumentedTest` to assert
  pairing completes end to end.

Publishing (`tools/publish-release.sh`) signs the APK and updates the LAN
F-Droid repo and pacman repo — no external service. Signing keys stay offline.
