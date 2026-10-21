#!/bin/sh
# Render the store icon and feature graphic from their SVG sources, next to
# this script, into fastlane/metadata/android/en-US/images/, where F-Droid
# reads them and from where they are uploaded in Play Console. The feature
# graphic is also the website's link preview (site/assets/og-image.jpg).
# Needs rsvg-convert (brew install librsvg). Uses the app's own fonts.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
out="$repo/fastlane/metadata/android/en-US/images"
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

rsvg-convert -w 512 -h 512 "$here/icon.svg" -o "$out/icon.png"
# Feature graphic must have no alpha channel: flatten onto the page colour.
rsvg-convert -w 1024 -h 500 -b '#1C1D18' "$here/feature-graphic.svg" -o "$out/feature-graphic.png"
sips -s format jpeg -s formatOptions 95 "$out/feature-graphic.png" --out "$out/featureGraphic.jpg" >/dev/null
rm "$out/feature-graphic.png"
cp "$out/featureGraphic.jpg" "$repo/site/assets/og-image.jpg"
echo "wrote ${out#$repo/}/icon.png and featureGraphic.jpg (and the site's og-image.jpg)"
