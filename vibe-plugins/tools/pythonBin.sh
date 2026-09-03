#!/usr/bin/env bash
# Resolves the Python 3 interpreter for the tool scripts into $PYTHON.
# Source it after `cd` to the repository root:  . vibe-plugins/tools/pythonBin.sh
#
# Why not plain `python3`: on Windows that name is a Microsoft Store stub which prints an install
# hint and exits, while the real interpreter answers to `python`. The stub is on PATH too, so the
# candidate is probed by RUNNING it, not by looking it up.
VIBE_PYTHON_BIN=""
for candidate in python3 python; do
  if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    VIBE_PYTHON_BIN="$candidate"
    break
  fi
done
[ -n "$VIBE_PYTHON_BIN" ] || { echo "✖ Python 3.8+ не найден ни как python3, ни как python"; exit 1; }
export VIBE_PYTHON_BIN
# Callers get the wrapper, not the bare binary: it strips the "" Windows Python writes into pipes.
PYTHON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pythonLf.sh"
