#!/usr/bin/env bash
# Build the plain hubidubios ISO on an Apple-Silicon Mac by spinning up a real
# x86_64 Colima VM (QEMU system emulation -> a genuine x86_64 kernel, so
# mkarchiso's loop/mount/squashfs work; slow but correct). Uses a NAMED Colima
# profile so your default arm64 Colima (the fast container harness) is untouched.
#
# On a native x86_64 Linux host, skip this and run build-plain.sh directly.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
PROFILE="${HUBIDUBIOS_ISO_PROFILE:-isobuild}"

if ! colima status -p "$PROFILE" >/dev/null 2>&1; then
  echo "==> Starting x86_64 Colima VM (profile: $PROFILE) — this is slow"
  colima start -p "$PROFILE" --arch x86_64 --cpu 6 --memory 12 --disk 60
fi

CTX="colima-$PROFILE"
echo "==> Building the ISO in a privileged x86_64 container"
docker --context "$CTX" run --rm --privileged \
  -v "$REPO":/repo -w /repo \
  archlinux:latest \
  bash deploy/workstation/iso/build-plain.sh /repo/deploy/workstation/iso/out

echo "==> Done. ISO(s):"
ls -la "$REPO"/deploy/workstation/iso/out/*.iso 2>/dev/null || echo "  (build did not produce an ISO — see output above)"
echo "Flash with balenaEtcher; boot with Secure Boot off; free-space install alongside another system."
