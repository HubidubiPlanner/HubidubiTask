#!/usr/bin/env bash
# Build the hubidubios-workstation ISO on the Omarchy base (PLAN.md Phase 7 +
# Phase 8). Must run on Arch with the Omarchy ISO toolchain, privileged
# (loop devices) — CI/target work, not the macOS dev machine.
#
#   sudo ./build.sh [output_dir]
#
# Strategy (Phase 8): the workstation base is Omarchy Quattro. Its ISO builder
# `omacom-io/omarchy-iso` is itself an archiso profile (archiso submodule +
# configs/{airootfs,efiboot,grub,syslinux}, Docker-orchestrated via
# bin/omarchy-iso-make, with a self-contained offline package mirror). We
# overlay hubidubios onto a PINNED checkout of it: build the hubidubios packages into
# the offline mirror, add them to the package list, drop in our airootfs
# (native Quickshell/Hyprland integration, hardened nftables, systemd units,
# hubidubios-firstboot), and build.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${1:-$HERE/out}"
HUBIDUBIOS_REPO="/build/hubidubios-repo"

# Pin the Omarchy ISO builder. Bump deliberately; record the tag in iso/README.
OMARCHY_ISO_REPO="${OMARCHY_ISO_REPO:-https://github.com/omacom-io/omarchy-iso}"
OMARCHY_ISO_REF="${OMARCHY_ISO_REF:-}"   # REQUIRED: set to a release tag/commit
VENDOR="$HERE/omarchy-iso"               # vendored/submodule checkout

if [ -z "$OMARCHY_ISO_REF" ] && [ ! -d "$VENDOR/.git" ]; then
  echo "Set OMARCHY_ISO_REF to a pinned omarchy-iso tag/commit, or vendor it at $VENDOR" >&2
  exit 1
fi

echo "==> Building hubidubios packages into $HUBIDUBIOS_REPO"
mkdir -p "$HUBIDUBIOS_REPO"
for pkgdir in \
  "$REPO_ROOT"/packages/hubidubios-vault \
  "$REPO_ROOT"/packages/hubidubios-plugin-host \
  "$REPO_ROOT"/packages/hubidubios-sync \
  "$REPO_ROOT"/packages/hubidubios-agent \
  "$REPO_ROOT"/plugins/gtd \
  "$REPO_ROOT"/plugins/calendar \
  "$REPO_ROOT"/packages/metapackages
do
  ( cd "$pkgdir" && makepkg -f --nodeps --skipinteg ) || { echo "makepkg failed in $pkgdir"; exit 1; }
  cp "$pkgdir"/*.pkg.tar.* "$HUBIDUBIOS_REPO"/
done
repo-add "$HUBIDUBIOS_REPO/hubidubios.db.tar.gz" "$HUBIDUBIOS_REPO"/*.pkg.tar.*

echo "==> Preparing the Omarchy ISO profile (pinned)"
if [ ! -d "$VENDOR/.git" ]; then
  git clone "$OMARCHY_ISO_REPO" "$VENDOR"
  git -C "$VENDOR" checkout "$OMARCHY_ISO_REF"
  git -C "$VENDOR" submodule update --init --recursive
fi

# Add hubidubios to the offline mirror + package list. The exact paths depend on the
# omarchy-iso layout (builder/ + configs/); these are the overlay points:
PROFILE="$VENDOR/configs"
echo "hubidubios-workstation" >> "$PROFILE/packages.x86_64"
echo "hubidubios-plugins-default" >> "$PROFILE/packages.x86_64"
# Merge the hubidubios repo into the ISO's offline mirror + pacman.conf.
cp "$HUBIDUBIOS_REPO"/*.pkg.tar.* "$PROFILE"/../builder/offline-mirror/ 2>/dev/null || \
  cp "$HUBIDUBIOS_REPO"/*.pkg.tar.* "$PROFILE/airootfs/var/cache/hubidubios-repo/" 2>/dev/null || true

echo "==> Overlaying hubidubios airootfs + configs"
cp -r "$HERE/airootfs/." "$PROFILE/airootfs/"
install -Dm644 "$REPO_ROOT/config/nftables.conf" "$PROFILE/airootfs/etc/nftables.conf"
install -Dm644 "$REPO_ROOT/config/avahi/hubidubios.service" "$PROFILE/airootfs/etc/avahi/services/hubidubios.service"
install -Dm644 "$REPO_ROOT/config/radicale/config" "$PROFILE/airootfs/etc/radicale/config"
for unit in "$REPO_ROOT"/config/systemd/*.service "$REPO_ROOT"/config/systemd/*.timer; do
  install -Dm644 "$unit" "$PROFILE/airootfs/etc/systemd/user/$(basename "$unit")"
done

echo "==> Building the ISO via omarchy-iso-make"
mkdir -p "$OUT"
( cd "$VENDOR" && ./bin/omarchy-iso-make )   # Docker-orchestrated, reproducible
find "$VENDOR" -name '*.iso' -newer "$PROFILE/packages.x86_64" -exec cp {} "$OUT/" \;
echo "==> ISO in $OUT"
