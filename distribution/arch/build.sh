#!/usr/bin/env bash
# Build the HubidubiTask Arch package (x86_64) from a Mac or any Docker host.
#
#   distribution/arch/build.sh            # -> distribution/arch/out/hubidubitask-*.pkg.tar.zst
#
# Two containers, both x86_64 (emulated on Apple silicon, so it is slow):
#   1. the Linux app image, shared with the AppImage (../linux/app-image.sh);
#   2. Arch runs makepkg on it.
# DOCKER_CONTEXT picks the Docker host (default: colima); REUSE_IMAGE=1
# repackages the last app image instead of rebuilding it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
ctx="${DOCKER_CONTEXT:-colima}"
work="$HOME/.cache/hubidubitask-build"
image="$work/apps/hubidubitask-linux-x64.tar.gz"
out="$here/out"; mkdir -p "$out"

echo "==> 1/2 Linux app image"
"$repo/distribution/linux/app-image.sh"

echo "==> 2/2 Arch package"
rm -rf "$work/pkg"; mkdir -p "$work/pkg"
cp "$here"/hubidubitask/* "$work/pkg/"
# The package's version is the app's: one number for every platform.
version="$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")"
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
