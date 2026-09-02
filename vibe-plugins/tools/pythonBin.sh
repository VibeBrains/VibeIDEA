#!/usr/bin/env bash
# Resolves the Python 3 interpreter for the tool scripts into $PYTHON.
# Source it after `cd` to the repository root:  . vibe-plugins/tools/pythonBin.sh
#
# Why not plain `python3`: on Windows that name is a Microsoft Store stub which prints an install
# hint and exits, while the real interpreter answers to `python`. The stub is on PATH too, so the
# candidate is probed by RUNNING it, not by looking it up.
PYTHON=""
for candidate in python3 python; do
  if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
[ -n "$PYTHON" ] || { echo "✖ Python 3.8+ не найден ни как python3, ни как python"; exit 1; }
