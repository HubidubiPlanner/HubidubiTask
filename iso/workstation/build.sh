#!/usr/bin/env bash
# Build the prodos-workstation ISO (PLAN.md Phase 7).
#
# Must run on Arch with archiso installed, as root, in a privileged container
# or host (mkarchiso needs loop devices). This is CI/target work — it cannot
# run on the macOS dev machine.
#
#   sudo ./build.sh [output_dir]
#
# Strategy: overlay this profile onto a fresh copy of the upstream `releng`
# profile, so bootloader configs come from upstream and only our package list,
# pacman.conf, and airootfs are custom. Builds the prodos-* packages into a
# local repo first so pacman can resolve them.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${1:-$HERE/out}"
WORK="$(mktemp -d)"
PROFILE="$WORK/profile"
PRODOS_REPO="/build/prodos-repo"

command -v mkarchiso >/dev/null || { echo "install archiso"; exit 1; }

echo "==> Building prodos packages into $PRODOS_REPO"
mkdir -p "$PRODOS_REPO"
for pkgdir in \
  "$REPO_ROOT"/packages/prodos-vault \
  "$REPO_ROOT"/packages/prodos-plugin-host \
  "$REPO_ROOT"/packages/prodos-sync \
  "$REPO_ROOT"/packages/prodos-agent \
  "$REPO_ROOT"/plugins/gtd \
  "$REPO_ROOT"/plugins/calendar \
  "$REPO_ROOT"/plugins/tasks \
  "$REPO_ROOT"/plugins/inbox \
  "$REPO_ROOT"/packages/metapackages
do
  ( cd "$pkgdir" && makepkg -f --nodeps --skipinteg ) || { echo "makepkg failed in $pkgdir"; exit 1; }
  cp "$pkgdir"/*.pkg.tar.* "$PRODOS_REPO"/
done
repo-add "$PRODOS_REPO/prodos.db.tar.gz" "$PRODOS_REPO"/*.pkg.tar.*

echo "==> Overlaying profile on releng"
cp -r /usr/share/archiso/configs/releng "$PROFILE"
cp "$HERE/profiledef.sh" "$PROFILE/profiledef.sh"
cp "$HERE/packages.x86_64" "$PROFILE/packages.x86_64"
cp "$HERE/pacman.conf" "$PROFILE/pacman.conf"
cp -r "$HERE/airootfs/." "$PROFILE/airootfs/"

# Ship the target config files from config/ into the image.
install -Dm644 "$REPO_ROOT/config/nftables.conf" "$PROFILE/airootfs/etc/nftables.conf"
install -Dm644 "$REPO_ROOT/config/avahi/prodos.service" "$PROFILE/airootfs/etc/avahi/services/prodos.service"
install -Dm644 "$REPO_ROOT/config/radicale/config" "$PROFILE/airootfs/etc/radicale/config"
for unit in "$REPO_ROOT"/config/systemd/*.service "$REPO_ROOT"/config/systemd/*.timer; do
  install -Dm644 "$unit" "$PROFILE/airootfs/etc/systemd/user/$(basename "$unit")"
done

echo "==> mkarchiso"
mkdir -p "$OUT"
mkarchiso -v -w "$WORK/build" -o "$OUT" "$PROFILE"
echo "==> ISO in $OUT"
rm -rf "$WORK"
