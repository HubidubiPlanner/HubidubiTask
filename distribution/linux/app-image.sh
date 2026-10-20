#!/usr/bin/env bash
# The Linux app image (x86_64) every Linux package is made from: the Compose
# app with its own Java runtime, as a tarball.
#
#   distribution/linux/app-image.sh   # -> ~/.cache/hubidubitask-build/apps/hubidubitask-linux-x64.tar.gz
#
# jpackage builds only for the system it runs on, so this can't happen on the
# Mac: a Temurin 17 container does it, x86_64 (emulated on Apple silicon, so
# it is slow). The runtime bundled into the app is JDK 27 (post-quantum TLS,
# see desktop/build.gradle.kts), copied once out of the official Temurin 27
# image into the cache. DOCKER_CONTEXT picks the Docker host (default: colima).
# REUSE_IMAGE=1 keeps the last image instead of building it again.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
ctx="${DOCKER_CONTEXT:-colima}"
# Under $HOME: Colima shares only the home folder with its VM.
work="$HOME/.cache/hubidubitask-build"
image="$work/apps/hubidubitask-linux-x64.tar.gz"
cache="${HUBIDUBITASK_GRADLE_CACHE:-$HOME/.cache/hubidubitask-linux-gradle}"

if [ "${REUSE_IMAGE:-0}" = 1 ] && [ -f "$image" ]; then
  echo "    reusing $image"
  exit 0
fi
# Only the sources' copy is replaced: the packages' own work folders and the
# downloaded tools beside it stay.
rm -rf "$work/apps"; mkdir -p "$work/apps" "$cache"
rsync -a --exclude build --exclude .gradle --exclude .kotlin --exclude android \
  --exclude local.properties "$repo/src/apps/" "$work/apps/"
# JDK 27 for the bundled runtime, copied out of the official Temurin image once.
if [ ! -f "$cache/jdk27/release" ] || ! grep -q 'JAVA_VERSION="27' "$cache/jdk27/release"; then
  rm -rf "$cache/jdk27"; mkdir -p "$cache/jdk27"
  docker --context "$ctx" run --rm --platform linux/amd64 -v "$cache:/out" eclipse-temurin:27-jdk \
    bash -c 'cp -a /opt/java/openjdk/. /out/jdk27/'
fi
docker --context "$ctx" run --rm --platform linux/amd64 -v "$work/apps:/apps" \
  -e HUBIDUBITASK_RUNTIME_JDK=/root/.gradle/jdk27 \
  -v "$cache:/root/.gradle" -w /apps eclipse-temurin:17-jdk bash -c '
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq binutils fakeroot >/dev/null
    ./gradlew --no-daemon -q -Phubidubitask.desktopOnly=true :desktop:createDistributable
    tar -C desktop/build/compose/binaries/main/app -czf /apps/hubidubitask-linux-x64.tar.gz hubidubitask'
echo "    $image"
