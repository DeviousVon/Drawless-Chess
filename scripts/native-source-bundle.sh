#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point for the reviewed whole-project iOS source archive.
# GPL releases require complete corresponding source, not a native-only subset;
# this alias intentionally retains every clean-room policy and rebuild gate.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/source-bundle.sh" "$@"
