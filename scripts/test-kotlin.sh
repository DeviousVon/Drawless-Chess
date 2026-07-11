#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="$root/node_modules/kotlin-compiler/bin/kotlinc"
out="$root/build/kotlin-core-tests.jar"

if [[ ! -x "$compiler" ]]; then
  echo "Kotlin compiler missing. Run npm install first." >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
mapfile -t sources < <(find \
  "$root/android/core/src/main/kotlin" \
  "$root/android/core/src/test/kotlin" \
  "$root/android/engine/src/test/kotlin" \
  -name '*.kt' -print | sort)

sources+=(
  "$root/android/engine/src/main/kotlin/com/drawlesschess/engine/FairyNativeBindings.kt"
  "$root/android/engine/src/main/kotlin/com/drawlesschess/engine/JniFairyEnginePort.kt"
  "$root/android/engine/src/main/kotlin/com/drawlesschess/engine/AndroidUciTimeoutScheduler.kt"
)

"$compiler" -jvm-target 17 "${sources[@]}" -include-runtime -d "$out"
java -jar "$out"
