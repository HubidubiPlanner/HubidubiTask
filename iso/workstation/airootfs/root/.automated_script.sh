#!/usr/bin/env bash
# Runs automatically on the live medium's root autologin (archiso convention).
# On the live ISO this just prints guidance; the real setup runs post-install
# via hubidubios-firstboot. Kept tiny so the QEMU smoke test reaches a shell fast.
if [[ "$(cat /proc/cmdline)" == *"hubidubios.smoke"* ]]; then
  # Non-interactive smoke mode: make the CLI available and exit to a shell.
  echo "hubidubios live medium (smoke mode)"
fi
