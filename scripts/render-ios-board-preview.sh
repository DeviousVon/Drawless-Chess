#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || {
  echo "The SwiftUI board preview requires macOS" >&2
  exit 1
}

output="${1:-$root/build/ios-board-visual-preview.png}"
mkdir -p "$(dirname "$output")"
preview_tmp="$(mktemp -d "${TMPDIR:-/tmp}/drawless-board-preview.XXXXXX")"
trap 'rm -rf -- "$preview_tmp"' EXIT

xcrun swiftc -parse-as-library \
  "$root/iosApp/DrawlessChess/BoardVisuals.swift" \
  "$root/scripts/RenderBoardVisualPreview.swift" \
  -o "$preview_tmp/render-board-visual-preview"
"$preview_tmp/render-board-visual-preview" "$output"

echo "Rendered iOS board catalog: $output"
