#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="$root/node_modules/kotlin-compiler/bin/kotlinc"
out="$root/build/kotlin-core-tests.jar"

if [[ ! -x "${JAVA_HOME:-}/bin/java" || ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
  detected_java_home=""
  if [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
    detected_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [[ ! -x "$detected_java_home/bin/javac" && -d "$HOME/.local/share/jdks" ]]; then
    while IFS= read -r javac; do
      detected_java_home="$(dirname "$(dirname "$javac")")"
      break
    done < <(find "$HOME/.local/share/jdks" -type f -path '*/Contents/Home/bin/javac' -print | sort)
  fi
  [[ -x "$detected_java_home/bin/java" && -x "$detected_java_home/bin/javac" ]] || {
    echo "Kotlin tests require a complete JDK 17 or 21" >&2
    exit 1
  }
  export JAVA_HOME="$detected_java_home"
fi
export PATH="$JAVA_HOME/bin:$PATH"
java_major="$("$JAVA_HOME/bin/javac" -version 2>&1 | awk 'NR == 1 { split($2, version, "."); print version[1] }')"
[[ "$java_major" == 17 || "$java_major" == 21 ]] || {
  echo "Kotlin tests require JDK 17 or 21, found javac $("$JAVA_HOME/bin/javac" -version 2>&1)" >&2
  exit 1
}

if [[ ! -x "$compiler" ]]; then
  echo "Kotlin compiler missing. Run npm install first." >&2
  exit 1
fi

mkdir -p "$(dirname "$out")"
sources=()
while IFS= read -r source; do
  sources+=("$source")
done < <(find \
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
"$JAVA_HOME/bin/java" -jar "$out"
