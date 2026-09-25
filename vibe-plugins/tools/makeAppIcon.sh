#!/usr/bin/env bash
# Builds the macOS bundle icon (vibeidea.icns) from the logo SVGs.
#
# Each iconset entry is rendered straight at its pixel size by rsvg-convert:
# a raster renderer that draws a 16-point SVG into a larger canvas leaves the logo in a corner,
# and Finder's list view (16 px) then shows an empty square instead of the logo.
# Up to 32 px the hand-simplified vibeidea_16.svg is used: the full logo's strokes melt at that size.
#
# The icns is committed built, like the dmg background: the distribution build takes a ready file.
# Windows images are derived from it: run makeWinImages.py afterwards.
#
# Requires: rsvg-convert (brew install librsvg). iconutil is part of macOS.
set -euo pipefail
cd "$(dirname "$0")/../.."

RES=vibeidea-customization/resources
FULL=$RES/vibeidea.svg
SMALL=$RES/vibeidea_16.svg
OUT=$RES/mac/vibeidea.icns
# The largest pixel size drawn from the simplified logo
SMALL_MAX=32

command -v rsvg-convert >/dev/null || { echo "✖ нет rsvg-convert: brew install librsvg"; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
SET=$TMP/vibeidea.iconset
mkdir "$SET"

render() {  # render <pixels> <file name>
  local src=$FULL
  [ "$1" -le "$SMALL_MAX" ] && src=$SMALL
  rsvg-convert -w "$1" -h "$1" "$src" -o "$SET/$2"
}

for size in 16 32 128 256 512; do
  render "$size" "icon_${size}x${size}.png"
  render $((size * 2)) "icon_${size}x${size}@2x.png"
done

iconutil -c icns "$SET" -o "$OUT"

# Beside the icon: the hashes it was built from and its own
# The UI gate compares them: a logo edited without a rebuild, or an icon replaced by hand, fails there
# A 16-px defect is invisible on the Dock and in the About box, and was shipped for a month unnoticed
shasum -a 256 "$FULL" "$SMALL" "$OUT" | awk '{print $1}' > "$OUT.sources"
echo "✔ $OUT"
