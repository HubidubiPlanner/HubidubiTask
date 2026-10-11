#!/bin/sh
# Step 1: the two files the website offers, built from this repository.
#
#   sh deploy/website/1-downloads/build.sh            build what is missing or old
#   sh deploy/website/1-downloads/build.sh --rebuild  build everything again
#
# out/HubidubiTask.dmg                   the Mac app (Apple silicon), :desktop:packageDmg
# out/hubidubitask-x86_64.pkg.tar.zst    the Linux package, distribution/arch/build.sh (needs Docker)
# out/SHA256SUMS                         checksums of the two
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

pkg="$repo/distribution/arch/out/hubidubitask-$version-1-x86_64.pkg.tar.zst"
if $rebuild || [ ! -f "$pkg" ]; then
  echo "==> Linux package $version"
  "$repo/distribution/arch/build.sh"
fi
cp "$pkg" "$out/hubidubitask-x86_64.pkg.tar.zst"

rm -f "$out/hubidubitask-agent.tar.gz"
(cd "$out" && shasum -a 256 HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst > SHA256SUMS)
echo "==> $out"
ls -l "$out" | awk 'NR>1 {printf "    %-36s %s bytes\n", $9, $5}'
