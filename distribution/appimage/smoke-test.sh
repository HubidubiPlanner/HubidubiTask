#!/usr/bin/env bash
# Try the AppImage in clean Linux containers (x86_64; emulated on a Mac): it
# starts, draws its window on a virtual screen, listens for phones on 5244 and
# keeps its data in ~/.local/share/hubidubitask.
#
#   distribution/appimage/smoke-test.sh                  # ubuntu:24.04 and fedora:42
#   distribution/appimage/smoke-test.sh debian:12        # or the ones you name
#
# Screenshots land in ~/.cache/hubidubitask-build/smoke/<image>.png.
# DOCKER_CONTEXT picks the Docker host (default: colima).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
ctx="${DOCKER_CONTEXT:-colima}"
version="$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")"
app="$here/out/HubidubiTask-$version-x86_64.AppImage"
[ -f "$app" ] || { echo "no $app: run build.sh first" >&2; exit 1; }
work="$HOME/.cache/hubidubitask-build/smoke"
rm -rf "$work"; mkdir -p "$work"
cp "$app" "$work/app.AppImage"
# The emulated x86_64 can't start a file marked as an AppImage (see build.sh);
# the copy tried here drops the mark and is otherwise the same file.
printf '\0\0\0' | dd of="$work/app.AppImage" bs=1 seek=8 count=3 conv=notrunc 2>/dev/null

images=("$@"); [ ${#images[@]} -gt 0 ] || images=(ubuntu:24.04 fedora:42)
failed=0
for img in "${images[@]}"; do
  name="$(echo "$img" | tr ':/' '--')"
  echo "==> $img"
  # A desktop's usual libraries and nothing of the app's: a library the app
  # needs and a desktop lacks would show up here.
  docker --context "$ctx" run --rm --platform linux/amd64 -v "$work:/w" -e SHOT="$name" "$img" bash -c '
    set -e
    . /etc/os-release
    case "$ID" in
      ubuntu|debian) apt-get update -qq >/dev/null
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb imagemagick fontconfig \
          fonts-dejavu-core libxtst6 libxi6 libxrender1 >/dev/null 2>&1 ;;
      fedora) dnf -y -q install xorg-x11-server-Xvfb ImageMagick fontconfig dejavu-sans-fonts \
          libXtst libXi libXrender procps-ng >/dev/null 2>&1 ;;
    esac
    useradd -m tester
    su tester -w SHOT -c "
      Xvfb :99 -screen 0 1280x860x24 >/dev/null 2>&1 &
      sleep 2
      cd /tmp && DISPLAY=:99 /w/app.AppImage --appimage-extract-and-run >/tmp/app.log 2>&1 &
      for i in \$(seq 1 150); do grep -qi \":147C \" /proc/net/tcp /proc/net/tcp6 2>/dev/null && break; sleep 2; done
      grep -qi \":147C \" /proc/net/tcp /proc/net/tcp6 && echo \"    listening on 5244\" || { echo \"!! not listening\"; tail -20 /tmp/app.log; exit 1; }
      sleep 20
      DISPLAY=:99 import -window root /w/\$SHOT.png
      ls ~/.local/share/hubidubitask/hubidubios.db >/dev/null && echo \"    data in ~/.local/share/hubidubitask\"
      # No GPU on a virtual screen: the app says so once and draws in software.
      grep -i \"exception\" /tmp/app.log | grep -v \"Cannot create Linux GL context\" | head -5 || true
    "' || failed=1
  [ -f "$work/$name.png" ] && echo "    $work/$name.png"
done
exit $failed
