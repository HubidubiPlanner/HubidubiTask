#!/usr/bin/env bash
# The Linux app image (x86_64) every Linux package is made from: the Compose
# app with its own Java runtime, as a tarball.
#
#   distribution/linux/app-image.sh   # -> ~/.cache/hubidubitask-build/apps/hubidubitask-linux-x64.tar.gz
#
# jpackage builds only for the system it runs on, so this can't happen on the
# Mac: a Temurin 17 container does it, x86_64 (emulated on Apple silicon, so
# it is slow). DOCKER_CONTEXT picks the Docker host (default: colima).
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
docker --context "$ctx" run --rm --platform linux/amd64 -v "$work/apps:/apps" \
  -v "$cache:/root/.gradle" -w /apps eclipse-temurin:17-jdk bash -c '
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq binutils fakeroot >/dev/null
    ./gradlew --no-daemon -q -Phubidubitask.desktopOnly=true :desktop:createDistributable
    tar -C desktop/build/compose/binaries/main/app -czf /apps/hubidubitask-linux-x64.tar.gz hubidubitask'
echo "    $image"
