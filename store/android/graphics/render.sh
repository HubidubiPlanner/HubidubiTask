#!/bin/sh
# Render the store graphics from their SVG sources into the fastlane layout
# (read by `fastlane supply`, and uploadable by hand in Play Console).
# Needs rsvg-convert (brew install librsvg). Uses the app's own fonts.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$repo/mobile/fastlane/metadata/android/en-US/images"
fonts="$repo/mobile/app/src/main/res/font"
mkdir -p "$out"

conf=$(mktemp)
trap 'rm -f "$conf"' EXIT
cat > "$conf" <<XML
<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>$fonts</dir><include ignore_missing="yes">/opt/homebrew/etc/fonts/fonts.conf</include></fontconfig>
XML
export FONTCONFIG_FILE="$conf"
# Pango on macOS defaults to CoreText, which ignores fontconfig.
export PANGOCAIRO_BACKEND=fc

rsvg-convert -w 512 -h 512 "$here/icon.svg" -o "$out/icon.png"
# Feature graphic must have no alpha channel: flatten onto the page colour.
rsvg-convert -w 1024 -h 500 -b '#1C1D18' "$here/feature-graphic.svg" -o "$out/featureGraphic.png"
sips -s format jpeg -s formatOptions 95 "$out/featureGraphic.png" --out "$out/featureGraphic.jpg" >/dev/null
rm "$out/featureGraphic.png"
echo "wrote $out/icon.png and $out/featureGraphic.jpg"
