# Containerized install-and-test

The **frequent** end-to-end loop: build the prodos packages, install them the
real way (`pacman -U`), run the full test suite, and drive a live sync + agent
smoke — all in a throwaway Arch container in ~90s.

```sh
tests/container/run.sh
```

## What it covers (and what it can't)

A container shares the host kernel, so it covers the install/service/CLI layers
but **not** a bootable ISO or a Wayland desktop. Test layers:

| Layer | Tool | Here |
|---|---|---|
| packages + CLI + plugins + sync + agent + **bwrap escapes** | this container | ✅ frequent |
| systemd services wired together | container/podman | (services run directly here) |
| bootable ISO + Omarchy + Hyprland/Quickshell | **QEMU** VM | `tests/smoke/qemu_smoke.sh` |
| mobile pairing | device / emulator | `tests/smoke/emulator_pairing.sh` |

The headline win: the **bwrap sandbox-escape suite** (`tests/sandbox/`) is
Linux-only and is *skipped* on macOS. Here bubblewrap is present, so those tests
(a skill reading `~/.ssh`, opening an outbound socket, forking a survivor — all
must fail) actually run.

## Runtime setup (macOS)

You have the `docker` CLI but need a Linux backend:

```sh
brew install colima docker
colima start                 # lightweight Linux VM; supports --privileged
tests/container/run.sh
```

Podman also works: `DOCKER=podman tests/container/run.sh`.

## Files

- `Dockerfile.arch` — cached base image (build deps, python, bubblewrap).
  Rebuild only when deps change: `run.sh --rebuild`.
- `test-inside.sh` — the sequence that runs inside the container.
- `run.sh` — builds the image if needed and runs the container `--privileged`
  with the repo mounted read-only.

The container copies the repo to a writable workdir, so package builds never
dirty your host tree.
