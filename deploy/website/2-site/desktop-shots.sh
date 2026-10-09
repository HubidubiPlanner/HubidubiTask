#!/bin/sh
# The website's desktop screenshots, rendered from the app itself with the
# same made-up data as the phone screenshots (never anyone's real items):
#
#   sh deploy/website/2-site/desktop-shots.sh
#
# Writes site/assets/screens/desktop-{incoming,projects}[-dark].jpg, 1620 px
# wide (the app's window at 1.5x). The phone ones come from
# deploy/google-play/3-graphics/shoot.py.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
(cd "$repo/src/apps" && SHOTS_DIR="$tmp" ./gradlew -q :desktop:test --tests '*DesktopShotsTest*' --rerun)
for f in "$tmp"/*.png; do
  out="$repo/site/assets/screens/$(basename "${f%.png}").jpg"
  sips -s format jpeg -s formatOptions 82 --resampleWidth 1620 "$f" --out "$out" >/dev/null
  echo "  ${out#$repo/}"
done
