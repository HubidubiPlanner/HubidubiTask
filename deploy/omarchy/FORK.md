# Omarchy with HubidubiTask

An Omarchy installer image with the HubidubiTask app installed. Omarchy stays
as it is; the image adds one package, `hubidubitask` (the app, built by
`distribution/arch/build.sh`), plus boot fixes for new NVIDIA cards.

The build needs three upstream checkouts. They are **not committed** here: they
are large pristine clones, and the repo stays private and small. Only our delta
is tracked — as `hubidubitask-omarchy-iso.patch`.

## Upstream sources (pinned)

| Directory | Upstream | Pinned commit |
|---|---|---|
| `omarchy-iso/` | `git@github.com:omacom-io/omarchy-iso.git` | `a23f8d464dcb0616a61bfaa8026e23d0533da209` |
| `omarchy/` | `git@github.com:basecamp/omarchy.git` | `93d9d0c8591605f9eab6f0e5b8735bed9d174f1b` |
| `omarchy-pkgs/` | `git@github.com:omacom-io/omarchy-pkgs.git` | `bce3b368e7cf711c41fc29eb5f8bfc1c658ab5b8` |

`omarchy` is **basecamp/omarchy** (DHH/Basecamp) — not omacom-io. Use SSH; the
HTTPS clone stalls.

## Restore the build tree

```sh
cd deploy/omarchy
git clone git@github.com:omacom-io/omarchy-iso.git  omarchy-iso
git clone git@github.com:basecamp/omarchy.git       omarchy
git clone git@github.com:omacom-io/omarchy-pkgs.git omarchy-pkgs
git -C omarchy-iso   checkout a23f8d464dcb0616a61bfaa8026e23d0533da209
git -C omarchy       checkout 93d9d0c8591605f9eab6f0e5b8735bed9d174f1b
git -C omarchy-pkgs  checkout bce3b368e7cf711c41fc29eb5f8bfc1c658ab5b8
git -C omarchy-iso apply ../hubidubitask-omarchy-iso.patch
```

`omarchy-iso` must keep its `.git` — the build calls `git ls-files --stage`
(permission lint) and `git submodule update`.

## What the patch does

1. **The app** (`builder/build-iso.sh`) — adds the app's Arch dependencies to
   the offline `pacman -Syw`; copies the prebuilt `hubidubitask` package into the
   offline mirror, keeps it through the prune, and appends `hubidubitask` to
   `omarchy-base.packages` (the list pacstrap installs). Both blocks are guarded
   by `[[ -d /hubidubitask-pkgs ]]`, so the builder still works as stock Omarchy
   without them.
2. **Container mount** (`bin/omarchy-iso-make`) — bind-mounts `hubidubitask-pkgs/`
   into the build container when present.
3. **Name** — none of its own: the image, its label and its boot menu are
   Omarchy's.
4. **Boot fixes** (`configs/grub/grub.cfg`, `configs/syslinux/*`) — a *visible*
   menu (`timeout=30`, `timeout_style=menu`) rendered on the firmware **text
   console** instead of `gfxterm`, plus `nouveau.modeset=0
   modprobe.blacklist=nouveau` on every entry and a dedicated
   "safe graphics — nomodeset" entry. Without these an NVIDIA Blackwell box
   (e.g. RTX 50-series card, no iGPU) shows no menu under UEFI and a black screen after
   the kernel starts.

## Build

```sh
bash deploy/omarchy/build-fork-in-colima.sh
```

Stage 1 builds the `hubidubitask` app package into `omarchy-iso/hubidubitask-pkgs/`;
stage 2 runs `omarchy-iso-make --no-boot-offer --local-source ../omarchy
../omarchy-pkgs` inside the x86_64 Colima VM. Output: `omarchy-iso/release/*.iso`.

Notes:
- The build container's final `chown /out` exits 1 across the virtiofs mount
  **after** the ISO is fully written — harmless; verify the ISO instead of
  trusting the exit code.
- The host-side driver process may be OOM-killed; the build continues inside the
  VM's dockerd. Watch the container, not the host log.
- A `monokai-pro.nvim` clone failure and `libfakeroot` warnings are non-fatal
  (`omarchy-nvim` runs `Lazy! sync … || true`).

## Rebasing onto a newer upstream

```sh
git -C omarchy-iso fetch && git -C omarchy-iso checkout <new-sha>
git -C omarchy-iso apply ../hubidubitask-omarchy-iso.patch   # resolve rejects if any
# then regenerate the patch and update the pinned SHAs above:
( cd omarchy-iso && git diff ) >> ../hubidubitask-omarchy-iso.patch   # keep the header
```
