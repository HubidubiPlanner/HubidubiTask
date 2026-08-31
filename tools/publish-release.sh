#!/usr/bin/env bash
# Publish a tagged release to the LAN, no external service (PLAN.md Phase 7).
#
# - Signs the APK and copies it to the F-Droid repo served from the workstation.
# - Adds the built pacman packages to the LAN package repo, signed with the
#   project key.
# The signing keys stay offline and out of the repo; this script expects them
# at $PRODOS_APK_KEYSTORE and in the pacman keyring.
set -euo pipefail

TAG="${1:?usage: publish-release.sh <tag>}"
FDROID_REPO="${PRODOS_FDROID_REPO:-/srv/fdroid/repo}"
PKG_REPO="${PRODOS_PKG_REPO:-/srv/pacman/prodos}"
APK="mobile/app/build/outputs/apk/release/app-release-unsigned.apk"

echo "==> Signing APK for $TAG"
: "${PRODOS_APK_KEYSTORE:?set PRODOS_APK_KEYSTORE to the offline signing keystore}"
apksigner sign --ks "$PRODOS_APK_KEYSTORE" \
  --out "prodos-$TAG.apk" "$APK"

echo "==> Updating LAN F-Droid repo"
mkdir -p "$FDROID_REPO"
cp "prodos-$TAG.apk" "$FDROID_REPO/"
# fdroid update regenerates index-v2 from mobile/fastlane metadata.
( cd "$(dirname "$FDROID_REPO")" && fdroid update --create-metadata )

echo "==> Updating LAN package repo"
mkdir -p "$PKG_REPO"
cp ./**/*.pkg.tar.* "$PKG_REPO"/ 2>/dev/null || true
repo-add --sign "$PKG_REPO/prodos.db.tar.gz" "$PKG_REPO"/*.pkg.tar.*

echo "==> Published $TAG to the LAN (F-Droid: $FDROID_REPO, packages: $PKG_REPO)"
