#!/bin/sh
# Step 1: the files of a release, built from this repository and named as
# they are on the GitHub release (release.sh publishes them).
#
#   sh deploy/website/1-downloads/build.sh            build what is missing or old
#   sh deploy/website/1-downloads/build.sh --rebuild  build everything again
#
# out/HubidubiTask-<v>.dmg                       the Mac app (Apple silicon), :desktop:packageDmg
# out/hubidubitask-<v>-1-x86_64.pkg.tar.zst      the Arch package, distribution/arch/build.sh (needs Docker)
# out/HubidubiTask-<v>-x86_64.AppImage           any Linux, distribution/appimage/build.sh (needs Docker)
# out/HubidubiTask-<v>.apk                       Android, signed with the APK key (SIGNING.md); F-Droid
#                                                fetches it under this name (deploy/f-droid/README.md)
# out/SHA256SUMS                                 checksums of the four
# out/SHA256SUMS.sig                             their signature (SIGNING.md)
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
# Files of other versions don't belong to this release.
find "$out" -maxdepth 1 -type f ! -name "*-$version*" ! -name '.gitignore' -delete

dmg="$apps/desktop/build/compose/binaries/main/dmg/HubidubiTask-$version.dmg"
if $rebuild || [ ! -f "$dmg" ]; then
  echo "==> Mac app $version"
  (cd "$apps" && ./gradlew -q :desktop:packageDmg)
fi
cp "$dmg" "$out/HubidubiTask-$version.dmg"

# Both Linux files wrap one Linux app image; the second reuses it when the
# first has just built it.
built=false
pkg="$repo/distribution/arch/out/hubidubitask-$version-1-x86_64.pkg.tar.zst"
if $rebuild || [ ! -f "$pkg" ]; then
  echo "==> Linux package $version"
  "$repo/distribution/arch/build.sh"
  built=true
fi
cp "$pkg" "$out/"

appimage="$repo/distribution/appimage/out/HubidubiTask-$version-x86_64.AppImage"
if $rebuild || [ ! -f "$appimage" ]; then
  echo "==> Linux AppImage $version"
  if $built; then REUSE_IMAGE=1 "$repo/distribution/appimage/build.sh"
  else "$repo/distribution/appimage/build.sh"; fi
fi
cp "$appimage" "$out/"

# Android: built from a fresh clone of the release tag, the way F-Droid builds
# it, so F-Droid's own build matches this one byte for byte and F-Droid ships
# this copy, signed with the key only the owner holds. The key and its
# Keychain item can be changed with HUBIDUBITASK_APK_KEYSTORE and
# HUBIDUBITASK_APK_KEYCHAIN.
apk="$out/HubidubiTask-$version.apk"
cert=$(sed -n 's/^AllowedAPKSigningKeys: *//p' "$repo/deploy/f-droid/com.saferendering.hubidubitask.yml")
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$apps/local.properties" 2>/dev/null)}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if $rebuild || [ ! -f "$apk" ]; then
  echo "==> Android APK $version, from tag v$version"
  git -C "$repo" rev-parse -q --verify "refs/tags/v$version" >/dev/null ||
    { echo "!! tag v$version is missing: tag the release first" >&2; exit 1; }
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  git clone -q --no-local --branch "v$version" "$repo" "$tmp/src" 2>/dev/null
  [ -f "$apps/local.properties" ] && cp "$apps/local.properties" "$tmp/src/src/apps/"
  (cd "$tmp/src/src/apps" && ./gradlew -q -Phubidubitask.androidOnly=true \
    -Phubidubitask.keystore="${HUBIDUBITASK_APK_KEYSTORE:-$HOME/.keystores/hubidubitask-release.jks}" \
    -Phubidubitask.keystoreAlias=release \
    -Phubidubitask.keystoreKeychain="${HUBIDUBITASK_APK_KEYCHAIN:-android-keystore-hubidubitask-release}" \
    :android:assembleRelease)
  cp "$tmp/src/src/apps/android/build/outputs/apk/release/android-release.apk" "$apk"
  rm -rf "$tmp"
fi
# Only the APK key may have signed it: the certificate F-Droid accepts.
"$apksigner" verify --print-certs "$apk" | grep -q "certificate SHA-256 digest: $cert" ||
  { echo "!! $apk is not signed with the APK key ($cert)" >&2; exit 1; }

(cd "$out" && shasum -a 256 "HubidubiTask-$version.dmg" "hubidubitask-$version-1-x86_64.pkg.tar.zst" \
  "HubidubiTask-$version-x86_64.AppImage" "HubidubiTask-$version.apk" > SHA256SUMS)
# Signed with the release key the repo signs its tags with (git config
# user.signingkey; see SIGNING.md), so a download can be checked against it.
key=$(git -C "$repo" config user.signingkey || true)
if [ -n "$key" ]; then
  rm -f "$out/SHA256SUMS.sig"
  case "$key" in "~/"*) key="$HOME/${key#\~/}" ;; esac
  ssh-keygen -Y sign -f "$key" -n file "$out/SHA256SUMS" >/dev/null 2>&1
  ssh-keygen -Y verify -f "$repo/.github/allowed_signers" -I hubidubiplanner@hubidubitask.invalid -n file \
    -s "$out/SHA256SUMS.sig" < "$out/SHA256SUMS" >/dev/null
  echo "    SHA256SUMS signed"
else
  echo "    !! no signing key set up (SIGNING.md): SHA256SUMS is not signed" >&2
fi
echo "==> $out"
ls -l "$out" | awk 'NR>1 {printf "    %-36s %s bytes\n", $9, $5}'
