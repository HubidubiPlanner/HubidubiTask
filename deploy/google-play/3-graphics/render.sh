#!/bin/sh
# Render the store icon and feature graphic from their SVG sources, next to
# this script, ready to upload in Play Console. The feature graphic is also
# the website's link preview (site/assets/og-image.jpg).
# Needs rsvg-convert (brew install librsvg). Uses the app's own fonts.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$here"
fonts="$repo/src/apps/android/src/main/res/font"
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

rsvg-convert -w 512 -h 512 "$here/icon.svg" -o "$out/icon-512.png"
# Feature graphic must have no alpha channel: flatten onto the page colour.
rsvg-convert -w 1024 -h 500 -b '#1C1D18' "$here/feature-graphic.svg" -o "$out/feature-graphic.png"
sips -s format jpeg -s formatOptions 95 "$out/feature-graphic.png" --out "$out/feature-graphic-1024x500.jpg" >/dev/null
rm "$out/feature-graphic.png"
cp "$out/feature-graphic-1024x500.jpg" "$repo/site/assets/og-image.jpg"
echo "wrote icon-512.png and feature-graphic-1024x500.jpg (and the site's og-image.jpg)"
