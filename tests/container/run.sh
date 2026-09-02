#!/usr/bin/env bash
# One-command containerized install-and-test of prodos (Phase 8 harness).
#
# Builds a cached Arch base image (only when its deps change), then runs the
# full build → pacman install → test → live-smoke sequence in a throwaway
# container. This is the FREQUENT loop: ~90s after the base image exists, and
# it runs the bwrap sandbox-escape tests that cannot run on macOS at all.
#
#   tests/container/run.sh            # full run
#   tests/container/run.sh --rebuild  # force-rebuild the base image first
#
# Needs a Linux container runtime. On macOS: `brew install colima docker &&
# colima start` (Colima gives the Docker CLI a Linux VM). --privileged is
# required for bubblewrap's user namespaces.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="prodos-test:base"
DOCKER="${DOCKER:-docker}"

# The sandbox tests use bwrap, whose user-namespace + mount syscalls are NOT
# emulated by QEMU or Rosetta — they only work on a native-arch container. So
# match the container arch to the host: Apple Silicon runs Arch Linux ARM
# natively; x86_64 (and CI) runs the official archlinux, the real target.
case "$(uname -m)" in
  arm64|aarch64)
    BASE="${PRODOS_BASE:-menci/archlinuxarm:latest}"
    PLATFORM="${PRODOS_PLATFORM:-linux/arm64}" ;;
  *)
    BASE="${PRODOS_BASE:-archlinux:latest}"
    PLATFORM="${PRODOS_PLATFORM:-linux/amd64}" ;;
esac

if ! "$DOCKER" info >/dev/null 2>&1; then
  echo "No container runtime. On macOS:  brew install colima docker && colima start" >&2
  exit 1
fi

if [ "${1:-}" = "--rebuild" ] || ! "$DOCKER" image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "==> Building base image $IMAGE ($PLATFORM, $BASE; cached after first build)"
  "$DOCKER" build --platform "$PLATFORM" --build-arg "BASE=$BASE" \
    -f "$REPO/tests/container/Dockerfile.arch" -t "$IMAGE" "$REPO/tests/container"
fi

echo "==> Running install-and-test in a container ($PLATFORM)"
exec "$DOCKER" run --rm -i --privileged --platform "$PLATFORM" \
  -v "$REPO":/src:ro \
  "$IMAGE" \
  bash /src/tests/container/test-inside.sh
