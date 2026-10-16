#!/bin/sh
# Step 1: the three files the website offers, built from this repository.
#
#   sh deploy/website/1-downloads/build.sh            build what is missing or old
#   sh deploy/website/1-downloads/build.sh --rebuild  build everything again
#
# out/HubidubiTask.dmg                   the Mac app (Apple silicon), :desktop:packageDmg
# out/hubidubitask-x86_64.pkg.tar.zst    the Arch package, distribution/arch/build.sh (needs Docker)
# out/HubidubiTask-x86_64.AppImage       any Linux, distribution/appimage/build.sh (needs Docker)
# out/SHA256SUMS                         checksums of the three
#
# The Mac app must be built on a Mac with Apple silicon.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
apps="$repo/src/apps"
out="$here/out"
version=$(sed -n 's/^hubidubitask.version=//p' "$apps/gradle.properties")
rebuild=false; [ "${1:-}" = "--rebuild" ] && rebuild=true
mkdir -p "$out"

dmg="$apps/desktop/build/compose/binaries/main/dmg/HubidubiTask-$version.dmg"
if $rebuild || [ ! -f "$dmg" ]; then
  echo "==> Mac app $version"
  (cd "$apps" && ./gradlew -q :desktop:packageDmg)
fi
cp "$dmg" "$out/HubidubiTask.dmg"

# Both Linux files wrap one Linux app image; the second reuses it when the
# first has just built it.
built=false
pkg="$repo/distribution/arch/out/hubidubitask-$version-1-x86_64.pkg.tar.zst"
if $rebuild || [ ! -f "$pkg" ]; then
  echo "==> Linux package $version"
  "$repo/distribution/arch/build.sh"
  built=true
fi
cp "$pkg" "$out/hubidubitask-x86_64.pkg.tar.zst"

appimage="$repo/distribution/appimage/out/HubidubiTask-$version-x86_64.AppImage"
if $rebuild || [ ! -f "$appimage" ]; then
  echo "==> Linux AppImage $version"
  if $built; then REUSE_IMAGE=1 "$repo/distribution/appimage/build.sh"
  else "$repo/distribution/appimage/build.sh"; fi
fi
cp "$appimage" "$out/HubidubiTask-x86_64.AppImage"

rm -f "$out/hubidubitask-agent.tar.gz"
(cd "$out" && shasum -a 256 HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst HubidubiTask-x86_64.AppImage > SHA256SUMS)
echo "==> $out"
ls -l "$out" | awk 'NR>1 {printf "    %-36s %s bytes\n", $9, $5}'
