#!/usr/bin/env bash
# Build the Omarchy image with the HubidubiOS app installed, on an Apple
# silicon Mac, in the x86_64 Colima VM. Two stages:
#   1) build the hubidubios app package (distribution/arch/build.sh) into
#      omarchy-iso/hubidubios-pkgs/
#   2) run the vendored omarchy-iso-make --local-source: Omarchy built from
#      our vendored checkouts, with the app in the offline mirror and the
#      install list (see hubidubios-omarchy-iso.patch).
#
# omarchy-iso-make needs a Linux host (GNU coreutils + docker), so stage 2
# runs INSIDE the Colima VM via `colima ssh`, using the VM's own dockerd.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PROFILE="${PRODOS_ISO_PROFILE:-isobuild}"
CTX="colima-$PROFILE"
OMI_REL="deploy/omarchy/omarchy-iso"
OMI="$REPO/$OMI_REL"

[ -d "$OMI/.git" ] || { echo "Vendored omarchy-iso missing at $OMI (see FORK.md)" >&2; exit 1; }
[ -d "$REPO/deploy/omarchy/omarchy/.git" ] || { echo "Vendored omarchy missing (see FORK.md)" >&2; exit 1; }
[ -d "$REPO/deploy/omarchy/omarchy-pkgs/.git" ] || { echo "Vendored omarchy-pkgs missing (see FORK.md)" >&2; exit 1; }

if ! colima status -p "$PROFILE" >/dev/null 2>&1; then
  echo "==> Starting x86_64 Colima VM ($PROFILE) — slow (emulated)"
  colima start -p "$PROFILE" --arch x86_64 --cpu 6 --memory 12 --disk 100
fi

echo "==> Stage 1: the hubidubios app package"
# The x86_64 VM builds it natively; REUSE_IMAGE=1 skips rebuilding the app.
DOCKER_CONTEXT="$CTX" "$REPO/distribution/arch/build.sh"
mkdir -p "$OMI/hubidubios-pkgs"
rm -f "$OMI"/hubidubios-pkgs/*.pkg.tar.zst
cp "$REPO"/distribution/arch/out/hubidubios-*.pkg.tar.zst "$OMI/hubidubios-pkgs/"
ls -1 "$OMI/hubidubios-pkgs"

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
