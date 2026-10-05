#!/usr/bin/env bash
# Build the HubidubiOS FORK ISO (Omarchy fork + our packages, one installer) on
# an Apple-Silicon Mac via the x86_64 Colima VM. Two stages, both on the VM:
#   1) build our hubidubios-* packages into omarchy-iso/hubidubios-pkgs/
#   2) run the vendored omarchy-iso-make --local-source — builds Omarchy entirely
#      from our vendored checkouts (no Omarchy mirror) and injects our packages
#      into the offline mirror + target install list -> a HubidubiOS installer.
#
# omarchy-iso-make needs a Linux host (GNU coreutils + docker), so stage 2 runs
# INSIDE the Colima VM via `colima ssh`, using the VM's own dockerd.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
PROFILE="${PRODOS_ISO_PROFILE:-isobuild}"
CTX="colima-$PROFILE"
OMI_REL="deploy/workstation/iso/omarchy-iso"
OMI="$REPO/$OMI_REL"

[ -d "$OMI/.git" ] || { echo "Vendored omarchy-iso missing at $OMI" >&2; exit 1; }
[ -d "$REPO/deploy/workstation/iso/omarchy/.git" ] || { echo "Vendored omarchy missing" >&2; exit 1; }
[ -d "$REPO/deploy/workstation/iso/omarchy-pkgs/.git" ] || { echo "Vendored omarchy-pkgs missing" >&2; exit 1; }

if ! colima status -p "$PROFILE" >/dev/null 2>&1; then
  echo "==> Starting x86_64 Colima VM ($PROFILE) — slow (emulated)"
  colima start -p "$PROFILE" --arch x86_64 --cpu 6 --memory 12 --disk 100
fi

echo "==> Stage 1: build hubidubios-* packages into $OMI_REL/hubidubios-pkgs/"
docker --context "$CTX" run --rm --privileged -v "$REPO":/repo -w /repo \
  archlinux:latest bash -euo pipefail -c '
  grep -q "^DisableSandbox" /etc/pacman.conf || sed -i "/^\[options\]/a DisableSandbox" /etc/pacman.conf
  pacman -Sy --noconfirm --needed base-devel git python-build python-installer python-hatchling python-wheel
  id build &>/dev/null || useradd -m build
  echo "build ALL=(ALL) NOPASSWD: ALL" >/etc/sudoers.d/build
  OUT=/repo/'"$OMI_REL"'/hubidubios-pkgs
  # $OUT is on the virtiofs mount (no chown across it); build in /tmp, cp results out.
  rm -f "$OUT"/*.pkg.tar.zst 2>/dev/null || true; mkdir -p "$OUT"
  for p in src/workstation/hubidubios-vault src/workstation/hubidubios-plugin-host \
           src/workstation/hubidubios-sync src/workstation/hubidubios-agent \
           src/plugins/gtd src/plugins/calendar src/workstation/hubidubios-desktop \
           src/workstation/metapackages; do
    cp -a "/repo/$p" "/tmp/$(basename $p)"
    chown -R build "/tmp/$(basename $p)"
    ( cd "/tmp/$(basename $p)" && sudo -u build makepkg -f --nodeps --skipinteg )
    cp "/tmp/$(basename $p)"/*.pkg.tar.zst "$OUT"/ 2>/dev/null || true
  done
  echo "-- built packages --"; ls -1 "$OUT"
'

echo "==> Stage 2: build the fork ISO inside the VM (omarchy-iso-make --local-source)"
colima ssh -p "$PROFILE" -- bash -lc "
  set -euo pipefail
  command -v git >/dev/null 2>&1 || { sudo apt-get update -q && sudo apt-get install -y git; }
  cd '$OMI'
  git config --global --add safe.directory '$OMI' || true
  ./bin/omarchy-iso-make --no-boot-offer --local-source ../omarchy ../omarchy-pkgs
"

echo "==> Done. ISO(s):"
ls -la "$OMI"/release/*.iso 2>/dev/null || echo "  (no ISO — see output above)"
