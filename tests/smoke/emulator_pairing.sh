#!/usr/bin/env bash
# Android emulator pairing smoke test (PLAN.md Phase 7).
#
# Boots the Android emulator, installs the debug APK, starts prodos-sync on the
# host, and drives pairing end to end via an instrumented test. Runs in CI with
# a KVM-accelerated emulator; not on the macOS dev machine.
#
#   ./emulator_pairing.sh path/to/app-debug.apk
set -euo pipefail

APK="${1:?usage: emulator_pairing.sh <apk>}"
AVD="prodos-ci"
SYNC_PORT=5233

cleanup() {
  [[ -n "${SYNC_PID:-}" ]] && kill "$SYNC_PID" 2>/dev/null || true
  adb -s emulator-5554 emu kill 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Starting prodos-sync on the host"
export PRODOS_VAULT="$(mktemp -d)/vault"
prodos-sync serve --bind 10.0.2.2 --port "$SYNC_PORT" &
SYNC_PID=$!
sleep 2
# 10.0.2.2 is the host as seen from the emulator's user-mode net.
PAIR_TOKEN="$(prodos-sync pair | head -1 | awk '{print $NF}')"
FINGERPRINT="$(prodos-sync pair | grep fingerprint | awk '{print $NF}')"

echo "==> Booting emulator"
emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
# wait for full boot
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done

echo "==> Installing APK"
adb install -r "$APK"

echo "==> Driving pairing via instrumented test"
# The QR payload the workstation would display; passed to the instrumented
# test as arguments so it exercises the real Pairing.redeem path.
adb shell am instrument -w \
  -e host 10.0.2.2 -e port "$SYNC_PORT" \
  -e fingerprint "$FINGERPRINT" -e token "$PAIR_TOKEN" \
  com.prodos.mobile.test/androidx.test.runner.AndroidJUnitRunner

echo "==> Asserting the device is now paired on the host"
prodos-sync devices | grep -q . && echo "==> Emulator pairing PASSED"
