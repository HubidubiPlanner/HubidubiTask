#!/usr/bin/env bash
# Build the HubidubiTask AppImage (x86_64): one file that runs on most Linux
# distributions without installing anything.
#
#   distribution/appimage/build.sh   # -> distribution/appimage/out/HubidubiTask-<version>-x86_64.AppImage
#
# The same Linux app image as the Arch package (../linux/app-image.sh),
# wrapped by appimagetool in an x86_64 Debian container. Unlike the Arch
# package it can't open the sync port in a firewall; the manual says how.
# DOCKER_CONTEXT picks the Docker host (default: colima); REUSE_IMAGE=1
# wraps the last app image instead of rebuilding it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
ctx="${DOCKER_CONTEXT:-colima}"
work="$HOME/.cache/hubidubitask-build"
image="$work/apps/hubidubitask-linux-x64.tar.gz"
out="$here/out"; mkdir -p "$out"
version="$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")"

# The tools, pinned by version and checksum, kept between builds.
tools="$work/tools"; mkdir -p "$tools"
fetch() {   # url file sha256
  [ -f "$tools/$2" ] || curl -fsSLo "$tools/$2" "$1"
  echo "$3  $tools/$2" | shasum -a 256 -c - >/dev/null ||
    { echo "!! $2 does not match its checksum" >&2; rm -f "$tools/$2"; exit 1; }
}
fetch https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage \
  appimagetool-1.9.1-x86_64.AppImage ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
fetch https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64 \
  runtime-20251108-x86_64 2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d

echo "==> 1/2 Linux app image"
"$repo/distribution/linux/app-image.sh"

echo "==> 2/2 AppImage"
rm -rf "$work/appimage"; mkdir -p "$work/appimage"
cp "$image" "$here/AppRun" "$repo/distribution/arch/hubidubitask/hubidubitask.desktop" "$work/appimage/"
docker --context "$ctx" run --rm --platform linux/amd64 -v "$work/appimage:/w" -v "$tools:/tools:ro" \
  -e VERSION="$version" -w /w debian:12-slim bash -c '
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq file desktop-file-utils >/dev/null
    mkdir -p AppDir/usr/lib
    tar -C AppDir/usr/lib -xzf hubidubitask-linux-x64.tar.gz
    install -m 755 AppRun AppDir/AppRun
    cp hubidubitask.desktop AppDir/
    cp AppDir/usr/lib/hubidubitask/lib/HubidubiTask.png AppDir/hubidubitask.png
    ln -s hubidubitask.png AppDir/.DirIcon
    cp /tools/appimagetool-1.9.1-x86_64.AppImage /tmp/appimagetool && chmod +x /tmp/appimagetool
    # An AppImage marks itself in its ELF header (bytes 8-10), which keeps an
    # emulated x86_64 (qemu on Apple silicon) from recognising it as a program.
    # The tool runs fine without the mark; this copy only drops it.
    printf "\0\0\0" | dd of=/tmp/appimagetool bs=1 seek=8 count=3 conv=notrunc 2>/dev/null
    ARCH=x86_64 /tmp/appimagetool --appimage-extract-and-run -n \
      --runtime-file /tools/runtime-20251108-x86_64 AppDir "HubidubiTask-$VERSION-x86_64.AppImage" >/dev/null'
cp "$work/appimage/HubidubiTask-$version-x86_64.AppImage" "$out/"
ls -1 "$out"
