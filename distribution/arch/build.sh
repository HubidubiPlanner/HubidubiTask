#!/usr/bin/env bash
# Build the HubidubiOS Arch package (x86_64) from a Mac or any Docker host.
#
#   distribution/arch/build.sh            # -> distribution/arch/out/hubidubios-*.pkg.tar.zst
#
# Two containers, both x86_64 (emulated on Apple silicon, so it is slow):
#   1. Temurin 17 builds the Compose app image for Linux (jpackage builds
#      only for the system it runs on, so this can't happen on the Mac);
#   2. Arch runs makepkg on it.
# DOCKER_CONTEXT picks the Docker host (default: colima).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
ctx="${DOCKER_CONTEXT:-colima}"
# Under $HOME: Colima shares only the home folder with its VM.
work="$HOME/.cache/hubidubios-build"
image="$work/apps/hubidubios-linux-x64.tar.gz"
# REUSE_IMAGE=1 repackages the last app image instead of rebuilding it.
if [ "${REUSE_IMAGE:-0}" = 1 ] && [ -f "$image" ]; then
  keep="$(mktemp)"; cp "$image" "$keep"; rm -rf "$work"; mkdir -p "$work/apps"; mv "$keep" "$image"
else
  rm -rf "$work"; mkdir -p "$work"
fi
out="$here/out"; mkdir -p "$out"
cache="${HUBIDUBIOS_GRADLE_CACHE:-$HOME/.cache/hubidubios-linux-gradle}"; mkdir -p "$cache"

if [ ! -f "$image" ]; then
echo "==> 1/2 Linux app image"
rsync -a --exclude build --exclude .gradle --exclude .kotlin --exclude android \
  --exclude local.properties "$repo/src/apps/" "$work/apps/"
docker --context "$ctx" run --rm --platform linux/amd64 -v "$work/apps:/apps" \
  -v "$cache:/root/.gradle" -w /apps eclipse-temurin:17-jdk bash -c '
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq binutils fakeroot >/dev/null
    ./gradlew --no-daemon -q -Phubidubios.desktopOnly=true :desktop:createDistributable
    tar -C desktop/build/compose/binaries/main/app -czf /apps/hubidubios-linux-x64.tar.gz hubidubios'
fi

echo "==> 2/2 Arch package"
mkdir -p "$work/pkg"
cp "$here"/hubidubios/* "$work/pkg/"
# The package's version is the app's: one number for every platform.
version="$(sed -n 's/^hubidubios.version=//p' "$repo/src/apps/gradle.properties")"
sed -i.bak "s/^pkgver=.*/pkgver=$version/" "$work/pkg/PKGBUILD" && rm -f "$work/pkg/PKGBUILD.bak"
cp "$image" "$work/pkg/"
docker --context "$ctx" run --rm --platform linux/amd64 -v "$work/pkg:/pkg" archlinux:latest bash -c '
    set -e
    sed -i "/^\[options\]/a DisableSandbox" /etc/pacman.conf
    pacman -Sy --noconfirm --needed base-devel >/dev/null
    useradd -m build && cp -a /pkg /home/build/pkg && chown -R build /home/build/pkg
    cd /home/build/pkg && sudo -u build makepkg -f --nodeps >/dev/null
    cp /home/build/pkg/*.pkg.tar.zst /pkg/'
cp "$work"/pkg/*.pkg.tar.zst "$out/"
ls -1 "$out"
