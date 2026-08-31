#!/usr/bin/env bash
# QEMU smoke test (PLAN.md Phase 7).
#
# Boot the workstation ISO headless, wait for SSH over a user-mode forward, and
# assert the core prodos commands work end to end. Any non-zero exit fails the
# pipeline. Runs in CI (needs KVM + qemu); not on the macOS dev machine.
#
#   ./qemu_smoke.sh path/to/prodos-workstation.iso
set -euo pipefail

ISO="${1:?usage: qemu_smoke.sh <iso>}"
SSH_PORT=2222
SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
          -o ConnectTimeout=5 -p "$SSH_PORT")
LOG="$(mktemp)"

cleanup() { [[ -n "${QEMU_PID:-}" ]] && kill "$QEMU_PID" 2>/dev/null || true; }
trap cleanup EXIT

echo "==> Booting ISO headless"
qemu-system-x86_64 \
  -enable-kvm -m 4096 -smp 2 \
  -cdrom "$ISO" \
  -nic user,hostfwd=tcp::"$SSH_PORT"-:22 \
  -nographic -serial "file:$LOG" \
  -append "prodos.smoke" &
QEMU_PID=$!

echo "==> Waiting for SSH (up to 5 min)"
for _ in $(seq 1 60); do
  if ssh "${SSH_OPTS[@]}" root@127.0.0.1 true 2>/dev/null; then
    break
  fi
  sleep 5
done

run() { ssh "${SSH_OPTS[@]}" root@127.0.0.1 "$@"; }

echo "==> Asserting prodos commands"
run "prodos index rebuild"
run "prodos capture 'smoke test item'"
run "prodos inbox | grep -q 'smoke test item'"

echo "==> Asserting the agent answers (model preloaded)"
# `prodos ask` must return non-empty; the model is resident via llama.service.
run "prodos-agent ask 'what is in my inbox?' | grep -q ."

echo "==> QEMU smoke PASSED"
