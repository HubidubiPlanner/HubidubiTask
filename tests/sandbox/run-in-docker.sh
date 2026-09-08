#!/usr/bin/env bash
# Run the bwrap sandbox-escape suite in a Linux container, since bwrap is
# Linux-only and cannot run on the macOS dev machine. CI runs the same image.
set -euo pipefail
cd "$(dirname "$0")/../.."

docker run --rm --privileged -v "$PWD:/repo" -w /repo archlinux:latest bash -euc '
  pacman -Sy --noconfirm --needed python python-pip bubblewrap >/dev/null
  pip install --quiet --break-system-packages \
    ./packages/hubidubios-vault ./packages/hubidubios-plugin-host pytest pyyaml jsonschema icalendar
  python -m pytest tests/sandbox -v
'
