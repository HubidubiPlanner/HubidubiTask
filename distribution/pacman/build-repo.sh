#!/usr/bin/env bash
# build-repo.sh — build all HubidubiOS packages into a PRIVATE local pacman repo.
#
# Run on an Arch/Omarchy box (needs base-devel/makepkg). Output: ./repo/ with the
# built .pkg.tar.zst files + a hubidubios.db (repo-add). This repo is never
# published anywhere — hubidubios-install serves it to pacman over file://.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$REPO_ROOT/repo}"

command -v makepkg >/dev/null || { echo "Install base-devel (makepkg) first." >&2; exit 1; }
mkdir -p "$OUT"

# Build order: libraries/plugins first, then the desktop + metapackages that
# depend on them. --nodeps because inter-package deps resolve at install time.
PKGDIRS=(
  src/workstation/hubidubios-vault
  src/workstation/hubidubios-plugin-host
  src/workstation/hubidubios-sync
  src/workstation/hubidubios-agent
  src/plugins/gtd
  src/plugins/calendar
  src/workstation/hubidubios-desktop
  src/workstation/metapackages
)

for p in "${PKGDIRS[@]}"; do
  echo "==> makepkg $p"
  ( cd "$REPO_ROOT/$p" && makepkg -f --nodeps --skipinteg )
  cp "$REPO_ROOT/$p"/*.pkg.tar.* "$OUT"/
done

repo-add "$OUT/hubidubios.db.tar.gz" "$OUT"/*.pkg.tar.*
echo "==> Private HubidubiOS repo ready at: $OUT"
echo "    Next, on your Omarchy box:  ./deploy/workstation/addon/hubidubios-install $OUT"
