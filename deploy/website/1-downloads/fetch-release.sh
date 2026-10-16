#!/bin/sh
# Step 1 in CI: the three files the website offers, taken from the release in
# the GitLab package registry (generic package hubidubitask/<version>) instead
# of built here. The `pages` job in .gitlab-ci.yml runs it before
# ../2-site/assemble.sh; GitLab sets CI_API_V4_URL, CI_PROJECT_ID and
# CI_JOB_TOKEN.
#
# out/ ends up as build.sh leaves it: the same three names and their SHA256SUMS.
# The release must be uploaded first, so a version bump on main publishes
# once its files are in the registry.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$here/out"
version=$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")
registry="$CI_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/hubidubitask/$version"
dmg="HubidubiTask-$version.dmg"
pkg="hubidubitask-$version-1-x86_64.pkg.tar.zst"
appimage="HubidubiTask-$version-x86_64.AppImage"

rm -rf "$out"
mkdir -p "$out/release"
cd "$out/release"
for f in SHA256SUMS "$dmg" "$pkg" "$appimage"; do
  echo "==> $f"
  curl -fsS --header "JOB-TOKEN: $CI_JOB_TOKEN" -o "$f" "$registry/$f" ||
    { echo "!! $f is not in release $version; upload the release, then retry this job" >&2; exit 1; }
  [ "$f" = SHA256SUMS ] && continue
  sum=$(grep -F "  $f" SHA256SUMS) || { echo "!! $f is not in the release's SHA256SUMS" >&2; exit 1; }
  echo "$sum" | sha256sum -c -
done

cd "$out"
mv "release/$dmg" HubidubiTask.dmg
mv "release/$pkg" hubidubitask-x86_64.pkg.tar.zst
mv "release/$appimage" HubidubiTask-x86_64.AppImage
rm -rf release
sha256sum HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst HubidubiTask-x86_64.AppImage > SHA256SUMS
echo "==> $out"
