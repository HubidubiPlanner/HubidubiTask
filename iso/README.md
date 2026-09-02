# Distribution (Phase 7)

Bootable media and the thin-client image. **Start only after two months of
daily use** (PLAN.md). None of this builds on the macOS dev machine — ISO
builds need a privileged Linux host (loop devices), QEMU needs KVM. It all runs
in CI on the self-hosted workstation runner; the files here are authored and
syntax-checked, not built here.

## workstation/ — the x86_64 ISO (Omarchy base, Phase 8)

The workstation base is **Omarchy Quattro** (MIT). Its ISO builder
`omacom-io/omarchy-iso` is itself an archiso profile, so prodos overlays onto a
**pinned** checkout of it. `build.sh`:

1. Builds every prodos package (`packages/*` + `plugins/{gtd,calendar}` +
   metapackages) into a local signed repo.
2. Clones/uses a pinned `omarchy-iso` (`OMARCHY_ISO_REF`), injects the prodos
   packages into its offline mirror + package list.
3. Overlays our `airootfs/` — the **native Quickshell/Hyprland integration**
   (no browser): `prodos-capture-gui` (Super+Shift+Space), `prodos-next-panel`,
   the `ProdosStatus.qml` bar widget, `hypr/prodos-keybinds.conf` — plus the
   hardened `nftables.conf`, the `config/` files, and `prodos-firstboot` (which
   enables the per-user services, prefers **selfhostedai-agent** for inference,
   strips Omarchy's cloud coding-agent widget, and wires the keybinds).
4. Builds via `omarchy-iso-make` (Docker, reproducible).

```sh
OMARCHY_ISO_REF=<tag/commit> sudo iso/workstation/build.sh   # on Arch, privileged
```

`profiledef.sh` / `packages.x86_64` / `pacman.conf` here remain as the
alternative **releng-base** path (integration model B) if you ever choose to
own the whole surface instead of basing on Omarchy.

### No-cloud enforcement
Omarchy ships cloud-capable apps; `config/nftables.conf` now sets the **OUTPUT
policy to drop** with only LAN + DNS + an (opt-in) mirror allowlist, so no app
can phone home. Inference is the local selfhostedai-agent OpenAI server (loopback
:8081); `prodos-agent status` shows the connection. See `packages/prodos-agent`.

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
