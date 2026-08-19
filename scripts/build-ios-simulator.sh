#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
bash "$root/scripts/generate-ios-project.sh"

destination="${DRAWLESS_IOS_DESTINATION:-}"
if [[ -z "$destination" ]]; then
  device_id="${DRAWLESS_IOS_SIMULATOR_ID:-}"
  if [[ -z "$device_id" ]]; then
    device_id="$(xcrun simctl list devices booted | sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -1)"
  fi
  if [[ -n "$device_id" ]]; then
    destination="platform=iOS Simulator,id=$device_id"
  else
    destination="generic/platform=iOS Simulator"
  fi
fi

derived_data="${DRAWLESS_IOS_DERIVED_DATA:-$root/build/xcode-derived}"
echo "Building unsigned iOS host for $destination"

xcodebuild \
  -quiet \
  -project "$root/iosApp/DrawlessChess.xcodeproj" \
  -scheme DrawlessChess \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "$destination" \
  -derivedDataPath "$derived_data" \
  CODE_SIGNING_ALLOWED=NO \
  build
