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

if ! "$DOCKER" info >/dev/null 2>&1; then
  echo "No container runtime. On macOS:  brew install colima docker && colima start" >&2
  exit 1
fi

if [ "${1:-}" = "--rebuild" ] || ! "$DOCKER" image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "==> Building base image $IMAGE (cached after first build)"
  "$DOCKER" build -f "$REPO/tests/container/Dockerfile.arch" -t "$IMAGE" "$REPO/tests/container"
fi

echo "==> Running install-and-test in a container"
exec "$DOCKER" run --rm -i --privileged \
  -v "$REPO":/src:ro \
  "$IMAGE" \
  bash /src/tests/container/test-inside.sh
