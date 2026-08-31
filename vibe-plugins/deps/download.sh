#!/bin/sh
# Download pinned external artifacts for the VibeIDEA distribution.
#
# LSP4IJ (EPL-2.0) is bundled from its GitHub release, not from JetBrains Marketplace.
# Phpactor (MIT) is bundled as its single phar: 4.3 MB for a whole language working out of the box.
# Both are PINNED by version and verified by sha256 — «latest» in a build script is a build that
# means something different every day.
set -e
cd "$(dirname "$0")"
V=0.20.1
SHA=3d2bffc78df998aebdbefc4714f351353b22762ec5d383eb33591acb54d3e419
[ -f "lsp4ij-$V.zip" ] || curl -sL -o "lsp4ij-$V.zip" "https://github.com/redhat-developer/lsp4ij/releases/download/$V/lsp4ij-$V.zip"
echo "$SHA  lsp4ij-$V.zip" | shasum -a 256 -c -
rm -rf extracted && mkdir -p extracted && unzip -q "lsp4ij-$V.zip" -d extracted

# --- Phpactor: PHP language server, one self-contained phar ---
# Runs on the machine's PHP (>= 8.1). We do not ship a PHP runtime: a PHP developer without PHP
# does not exist, and bundling one would add a second interpreter to keep patched.
PHPACTOR_V=2026.06.23.0
PHPACTOR_SHA=25645647d9aa2dc69536fb4f75c976e33ef1a7b5533534a8456736e5e6fd5079
[ -f "phpactor-$PHPACTOR_V.phar" ] || curl -sL -o "phpactor-$PHPACTOR_V.phar" \
  "https://github.com/phpactor/phpactor/releases/download/$PHPACTOR_V/phpactor.phar"
echo "$PHPACTOR_SHA  phpactor-$PHPACTOR_V.phar" | shasum -a 256 -c -
mkdir -p extracted/servers && cp "phpactor-$PHPACTOR_V.phar" extracted/servers/phpactor.phar
# MIT requires the licence text to travel with the copy — it ships next to the phar.
[ -f "phpactor-LICENSE" ] || curl -sL -o "phpactor-LICENSE" \
  "https://raw.githubusercontent.com/phpactor/phpactor/$PHPACTOR_V/LICENSE"
cp "phpactor-LICENSE" extracted/servers/phpactor-LICENSE
printf 'Phpactor %s (MIT), bundled from the project release; see phpactor-LICENSE.\n' "$PHPACTOR_V" \
  > extracted/servers/README.txt
