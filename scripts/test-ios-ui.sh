#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
bash "$root/scripts/generate-ios-project.sh"

device_id="${DRAWLESS_IOS_SIMULATOR_ID:-}"
if [[ -z "$device_id" ]]; then
  device_id="$(xcrun simctl list devices booted | sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -1)"
fi
[[ -n "$device_id" ]] || {
  echo "Boot an iOS simulator or set DRAWLESS_IOS_SIMULATOR_ID" >&2
  exit 1
}

xcodebuild \
  -quiet \
  -project "$root/iosApp/DrawlessChess.xcodeproj" \
  -scheme DrawlessChess \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$device_id" \
  -only-testing:DrawlessChessUITests \
  test
