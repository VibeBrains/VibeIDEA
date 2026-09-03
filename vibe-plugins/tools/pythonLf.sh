#!/usr/bin/env bash
# Runs the interpreter resolved by pythonBin.sh and strips carriage returns from its stdout.
#
# Python on Windows writes "\r\n" into pipes, so every bash consumer — `read`, `mapfile`, `$(...)`
# — would carry a stray "\r" into comparisons: a sha256 that "does not match", a version pin that
# is never found in the licence report. Caught by the release dry-run on 02.09.2026. Stripping here,
# once, keeps the callers identical on every OS; on macOS/Linux there is nothing to strip.
set -o pipefail
"$VIBE_PYTHON_BIN" "$@" | tr -d '\r'
