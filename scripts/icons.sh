#!/bin/sh
# Regenerates every raster icon from branding/*.svg. The Android icon is hand-ported VectorDrawable XML, not generated.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if command -v rsvg-convert >/dev/null 2>&1; then
    svg() { rsvg-convert -w "$1" -h "$1" "$2" -o "$3"; }
else
    svg() { nix shell nixpkgs#librsvg -c rsvg-convert -w "$1" -h "$1" "$2" -o "$3"; }
fi

opaque() { magick "$1" -background '#0F1017' -alpha remove -alpha off "PNG24:$2"; }

icon=$root/branding/icon.svg
small=$root/branding/icon-small.svg

ios=$root/iosApp/src/Assets.xcassets/AppIcon.appiconset
svg 1024 "$icon" "$tmp/full.png"
opaque "$tmp/full.png" "$ios/app-icon-1024.png"
cp "$ios/app-icon-1024.png" "$ios/app-icon-dark-1024.png"
magick "$ios/app-icon-1024.png" -colorspace Gray "PNG24:$ios/app-icon-tinted-1024.png"

mkdir -p "$root/desktopApp/resources"
svg 512 "$icon" "$tmp/d.png"
opaque "$tmp/d.png" "$root/desktopApp/resources/icon.png"

web=$root/webApp/resources
cp "$small" "$web/favicon.svg"
for s in 16 32 48; do svg "$s" "$small" "$tmp/f$s.png"; done
magick "$tmp/f16.png" "$tmp/f32.png" "$tmp/f48.png" "$web/favicon.ico"
svg 180 "$icon" "$tmp/a.png"
opaque "$tmp/a.png" "$web/apple-touch-icon.png"
svg 192 "$icon" "$tmp/i192.png"
opaque "$tmp/i192.png" "$web/icon-192.png"
svg 512 "$icon" "$tmp/i512.png"
opaque "$tmp/i512.png" "$web/icon-512.png"

tv=$root/androidApp/res/drawable-xhdpi
mkdir -p "$tv"
svg 104 "$icon" "$tmp/tv-glyph.png"
magick -size 320x180 xc:'#0F1017' "$tmp/tv-glyph.png" -gravity west -geometry +20+0 -composite \
    -fill '#FFFFFF' -font DejaVu-Sans-Bold -pointsize 23 -gravity west -annotate +138-14 'Meshtastic' \
    -fill '#67EA94' -annotate +138+16 'Aether' "PNG24:$tv/tv_banner.png"
