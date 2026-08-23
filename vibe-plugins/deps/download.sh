#!/bin/sh
# Download pinned external plugin artifacts for the VibeIDEA distribution.
# LSP4IJ (EPL-2.0) is bundled from its GitHub release, not from JetBrains Marketplace.
set -e
cd "$(dirname "$0")"
V=0.20.1
SHA=3d2bffc78df998aebdbefc4714f351353b22762ec5d383eb33591acb54d3e419
[ -f "lsp4ij-$V.zip" ] || curl -sL -o "lsp4ij-$V.zip" "https://github.com/redhat-developer/lsp4ij/releases/download/$V/lsp4ij-$V.zip"
echo "$SHA  lsp4ij-$V.zip" | shasum -a 256 -c -
rm -rf extracted && mkdir -p extracted && unzip -q "lsp4ij-$V.zip" -d extracted
