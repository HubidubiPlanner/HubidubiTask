#!/usr/bin/env bash
# Generate the prodos ISO image. One command, picks the right path:
#   - native x86_64 Linux  -> build the ISO directly (fast, ~10-15 min)
#   - Apple Silicon macOS   -> build inside an emulated x86_64 Colima VM (slow)
#
# To bake in YOUR stuff, edit iso/workstation/seed/vault/ first (notes, tasks,
# and .prodos/config.toml for the inference endpoint), then run this.
#
#   iso/make-image.sh
#
# Output: iso/workstation/out/*.iso  (gitignored). Flash with balenaEtcher.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

os="$(uname -s)"; arch="$(uname -m)"
if [ "$os" = "Linux" ] && [ "$arch" = "x86_64" ]; then
  echo "==> native x86_64 Linux build"
  exec sudo "$HERE/workstation/build-plain.sh" "$@"
elif [ "$os" = "Darwin" ]; then
  echo "==> macOS: building in an x86_64 Colima VM (emulated, slow but correct)"
  exec "$HERE/workstation/build-plain-in-colima.sh" "$@"
else
  echo "Build the ISO on x86_64 Linux: sudo iso/workstation/build-plain.sh" >&2
  echo "(this host is $os/$arch)" >&2
  exit 1
fi
