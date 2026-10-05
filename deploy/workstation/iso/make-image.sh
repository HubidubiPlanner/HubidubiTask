#!/usr/bin/env bash
# Generate the hubidubios ISO image. One command, picks the right path:
#   - native x86_64 Linux  -> build the ISO directly (fast, ~10-15 min)
#   - Apple Silicon macOS   -> build inside an emulated x86_64 Colima VM (slow)
#
# To bake in YOUR stuff, edit deploy/workstation/iso/seed/vault/ first (notes, tasks,
# and .hubidubios/config.toml for the inference endpoint), then run this.
#
#   deploy/workstation/iso/make-image.sh
#
# Output: deploy/workstation/iso/out/*.iso  (gitignored). Flash with balenaEtcher.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

os="$(uname -s)"; arch="$(uname -m)"
if [ "$os" = "Linux" ] && [ "$arch" = "x86_64" ]; then
  echo "==> native x86_64 Linux build"
  exec sudo "$HERE/build-plain.sh" "$@"
elif [ "$os" = "Darwin" ]; then
  echo "==> macOS: building in an x86_64 Colima VM (emulated, slow but correct)"
  exec "$HERE/build-plain-in-colima.sh" "$@"
else
  echo "Build the ISO on x86_64 Linux: sudo deploy/workstation/iso/build-plain.sh" >&2
  echo "(this host is $os/$arch)" >&2
  exit 1
fi
