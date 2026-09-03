#!/usr/bin/env bash
# Run the "heavy" CI stages LOCALLY — no GitLab runner involved (per the
# no-runner directive, everything here executes on this machine).
#
#   tests/local/run-heavy.sh [android|iso|qemu|all]
#
# Feasibility on this host:
#   android  — runs on a connected device or an emulator. Works on Apple
#              Silicon (Hypervisor.framework or a USB device). ✅ practical.
#   iso      — builds the x86_64 Omarchy ISO. Needs PRIVILEGED x86_64 Linux
#              (archiso + Docker-in-Docker + the Omarchy base). On arm64 this
#              only runs under slow, fragile emulation — see run_iso(). ⚠
#   qemu     — boots that ISO in QEMU. Needs the ISO first + hardware accel;
#              on arm64 it is TCG-emulated x86_64 (very slow). ⚠
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
VENV="$REPO/.venv/bin"
SCRATCH="${TMPDIR:-/tmp}/prodos-heavy"
mkdir -p "$SCRATCH"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    ok: %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m    ! %s\033[0m\n' "$*"; }

# --------------------------------------------------------------- android

run_android() {
  step "android-instrumented: build APKs"
  ( cd "$REPO/mobile" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest )
  local apk="$REPO/mobile/app/build/outputs/apk"

  local dev; dev="$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  [ -n "$dev" ] || { warn "no adb device/emulator; connect a phone or start an AVD"; return 1; }
  ok "device $dev"
  "$ADB" install -r "$apk/debug/app-debug.apk" >/dev/null
  "$ADB" install -r "$apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null

  step "android-instrumented: start prodos-sync (TLS) + adb reverse"
  export PRODOS_VAULT="$SCRATCH/android-vault"; rm -rf "$PRODOS_VAULT"
  "$VENV/python" -c "from prodos_vault.vault import Vault; import os; Vault(os.environ['PRODOS_VAULT']).ensure_layout()"
  "$VENV/prodos-sync" serve --bind 127.0.0.1 --port 5233 >"$SCRATCH/sync.log" 2>&1 &
  local sync=$!; trap "kill $sync 2>/dev/null; $ADB reverse --remove-all 2>/dev/null || true" RETURN
  sleep 2
  "$ADB" reverse tcp:5233 tcp:5233 >/dev/null
  local pair fp token
  pair="$("$VENV/prodos-sync" pair)"
  token="$(printf '%s\n' "$pair" | awk '/pair token/{print $NF}')"
  fp="$(printf '%s\n' "$pair" | grep -o 'sha256:[0-9a-f]*')"
  ok "server $fp"

  step "android-instrumented: drive the pinned-TLS pairing test on device"
  "$ADB" shell am instrument -w \
    -e class com.prodos.mobile.PairingInstrumentedTest \
    -e host 127.0.0.1 -e port 5233 -e fingerprint "$fp" -e token "$token" \
    com.prodos.mobile.test/androidx.test.runner.AndroidJUnitRunner | tee "$SCRATCH/instr.log"
  grep -q "OK (1 test)" "$SCRATCH/instr.log" || { warn "instrumented test failed"; return 1; }
  "$VENV/prodos-sync" devices | grep -q . && ok "device paired on the workstation"
}

# --------------------------------------------------------------- iso

run_iso() {
  step "iso: preconditions"
  if [ "$(uname -m)" != "x86_64" ] && [ "${FORCE_ISO:-}" != "1" ]; then
    warn "the Omarchy ISO is x86_64 and needs privileged x86_64 Linux."
    warn "this host is $(uname -m). Options:"
    warn "  - a native x86_64 Linux box or the self-hosted runner (fast), or"
    warn "  - an emulated amd64 VM here (slow):  colima start --arch x86_64 --cpu 6 --memory 12 && FORCE_ISO=1 $0 iso"
    warn "skipping — re-run with FORCE_ISO=1 to attempt under emulation."
    return 0
  fi
  command -v docker >/dev/null || { warn "docker (Colima) required"; return 1; }
  : "${OMARCHY_ISO_REF:?set OMARCHY_ISO_REF to a pinned omarchy-iso tag/commit}"
  step "iso: build (privileged x86_64 arch container)"
  docker run --rm --privileged --platform linux/amd64 -v "$REPO":/repo -w /repo \
    archlinux:latest bash -c "
      sed -i '/^\[options\]/a DisableSandbox' /etc/pacman.conf
      pacman -Sy --noconfirm --needed archiso git docker
      OMARCHY_ISO_REF='$OMARCHY_ISO_REF' iso/workstation/build.sh /repo/iso/workstation/out
    "
  ls -la "$REPO"/iso/workstation/out/*.iso 2>/dev/null && ok "ISO built"
}

# --------------------------------------------------------------- qemu

run_qemu() {
  step "qemu-smoke: preconditions"
  local iso; iso="$(ls "$REPO"/iso/workstation/out/*.iso 2>/dev/null | head -1 || true)"
  [ -n "$iso" ] || { warn "no ISO in iso/workstation/out — run '$0 iso' first"; return 0; }
  command -v qemu-system-x86_64 >/dev/null || { warn "install qemu (brew install qemu); note: x86_64 guest on arm64 is TCG-emulated and slow"; return 0; }
  step "qemu-smoke: boot + assert"
  "$REPO/tests/smoke/qemu_smoke.sh" "$iso"
}

case "${1:-all}" in
  android) run_android ;;
  iso)     run_iso ;;
  qemu)    run_qemu ;;
  all)     run_android; run_iso; run_qemu ;;
  *) echo "usage: $0 [android|iso|qemu|all]" >&2; exit 2 ;;
esac
