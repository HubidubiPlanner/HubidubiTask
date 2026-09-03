# Local heavy-stage runner

Runs the "heavy" CI stages **on this machine, with no GitLab runner**.

```sh
tests/local/run-heavy.sh android   # instrumented pinned-TLS pairing on a device/emulator
tests/local/run-heavy.sh iso       # build the x86_64 Omarchy ISO
tests/local/run-heavy.sh qemu      # boot that ISO and smoke it
tests/local/run-heavy.sh all
```

## Why some stages "can't" run locally on Apple Silicon — and the real answer

They *can*, but with very different practicality:

| Stage | Local feasibility |
|---|---|
| **android** | ✅ Practical. Runs on a USB device or an arm64 emulator (Hypervisor.framework). Verified: `OK (1 test)`, device paired. |
| **iso** | ⚠ The ISO is **x86_64** and the build needs **privileged x86_64 Linux** (archiso + Docker-in-Docker + the Omarchy base). This host is arm64, so locally it's an *emulated* amd64 VM: ~10× slower and it hits the same emulation gaps that broke bwrap (unshare/mount syscalls). Force it with `colima start --arch x86_64` + `FORCE_ISO=1`, but a native x86_64 box (or the self-hosted runner) is the right place. |
| **qemu** | ⚠ Boots the x86_64 ISO. On arm64 that's TCG-emulated x86_64 — correct but very slow. Needs the ISO first. |

So it isn't policy — it's that the ISO/QEMU stages are a *different architecture* than this laptop, and cross-arch emulation of privileged kernel features is slow and fragile. `android` has no such barrier and runs here directly.

The complementary fast loop for everything else (packages, CLI, plugins,
sync, agent, and the **bwrap sandbox-escape suite**) is
`tests/container/run.sh` — arm64-native, ~90s.
