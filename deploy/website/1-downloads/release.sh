#!/bin/sh
# Step 1, last part: publish the files build.sh made as the GitHub release of
# the version in src/apps/gradle.properties. The website's download buttons
# and F-Droid (the APK) take them from there.
#
#   sh deploy/website/1-downloads/release.sh
#
# Needs: the signed tag v<version> pushed to GitHub, out/ built for that
# version, and gh logged in to the project's account. The notes are the
# short plain-text ones Google Play and F-Droid show:
# fastlane/metadata/android/en-US/changelogs/<versionCode>.txt.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$here/out"
version=$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")
tag="v$version"
github=${HUBIDUBITASK_GITHUB:-HubidubiPlanner/HubidubiTask}

git -C "$repo" -c gpg.ssh.allowedSignersFile="$repo/.github/allowed_signers" verify-tag "$tag" >/dev/null 2>&1 ||
  { echo "!! $tag is missing or not signed with the release key (SIGNING.md)" >&2; exit 1; }
gh api "repos/$github/git/ref/tags/$tag" >/dev/null 2>&1 ||
  { echo "!! $tag is not on GitHub yet: push it first" >&2; exit 1; }

files="HubidubiTask-$version.dmg hubidubitask-$version-1-x86_64.pkg.tar.zst HubidubiTask-$version-x86_64.AppImage HubidubiTask-$version.apk"
cd "$out"
for f in $files SHA256SUMS SHA256SUMS.sig; do
  [ -f "$f" ] || { echo "!! out/$f is missing: run build.sh" >&2; exit 1; }
done
shasum -a 256 -c SHA256SUMS >/dev/null || { echo "!! out/ doesn't match SHA256SUMS" >&2; exit 1; }
ssh-keygen -Y verify -f "$repo/.github/allowed_signers" -I hubidubiplanner@hubidubitask.invalid -n file \
  -s SHA256SUMS.sig < SHA256SUMS >/dev/null || { echo "!! SHA256SUMS.sig doesn't verify" >&2; exit 1; }

code=$(sed -n 's/^hubidubitask.versionCode=//p' "$repo/src/apps/gradle.properties")
notes="$repo/fastlane/metadata/android/en-US/changelogs/$code.txt"
[ -s "$notes" ] || { echo "!! no release notes: fastlane/metadata/android/en-US/changelogs/$code.txt" >&2; exit 1; }

# shellcheck disable=SC2086
gh release create "$tag" -R "$github" --verify-tag --title "HubidubiTask $version" --notes-file "$notes" \
  $files SHA256SUMS SHA256SUMS.sig
