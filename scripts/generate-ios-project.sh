#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
xcodegen="$(command -v xcodegen || true)"
if [[ -z "$xcodegen" && -x "$HOME/.local/bin/xcodegen" ]]; then
  xcodegen="$HOME/.local/bin/xcodegen"
fi
[[ -x "$xcodegen" ]] || {
  echo "XcodeGen is required to regenerate iosApp/DrawlessChess.xcodeproj" >&2
  exit 1
}

"$xcodegen" generate --spec "$root/iosApp/project.yml" --project "$root/iosApp"
