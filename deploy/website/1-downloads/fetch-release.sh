#!/bin/sh
# Step 1 without building: the three files the website offers, taken from the
# current version's GitHub release (tag v<version>) instead of built here.
#
#   sh deploy/website/1-downloads/fetch-release.sh
#
# Only for a host that serves the downloads itself: on GitHub Pages the Mac
# and Linux buttons link to the release page instead (see ../3-publish/).
# Needs curl. The release must be published first.
#
# out/ ends up as build.sh leaves it: the same three names and their SHA256SUMS.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$here/out"
version=$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")
release="https://github.com/hubidubitask/HubidubiTask/releases/download/v$version"
dmg="HubidubiTask-$version.dmg"
pkg="hubidubitask-$version-1-x86_64.pkg.tar.zst"
appimage="HubidubiTask-$version-x86_64.AppImage"

# sha256sum on Linux, shasum on a Mac.
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

rm -rf "$out"
mkdir -p "$out/release"
cd "$out/release"
for f in SHA256SUMS "$dmg" "$pkg" "$appimage"; do
  echo "==> $f"
  curl -fsSL -o "$f" "$release/$f" ||
    { echo "!! $f is not in release v$version; publish the release, then try again" >&2; exit 1; }
  [ "$f" = SHA256SUMS ] && continue
  sum=$(grep -F "  $f" SHA256SUMS) || { echo "!! $f is not in the release's SHA256SUMS" >&2; exit 1; }
  echo "$sum" | sha256 -c -
done

cd "$out"
mv "release/$dmg" HubidubiTask.dmg
mv "release/$pkg" hubidubitask-x86_64.pkg.tar.zst
mv "release/$appimage" HubidubiTask-x86_64.AppImage
rm -rf release
sha256 HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst HubidubiTask-x86_64.AppImage > SHA256SUMS
echo "==> $out"
