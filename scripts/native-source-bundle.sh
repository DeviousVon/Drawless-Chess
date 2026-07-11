#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point. GPL releases require the complete corresponding
# source for the whole combined application, not a native-component subset.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/source-bundle.sh" "$@"
