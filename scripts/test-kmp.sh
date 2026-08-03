#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
gradlew="$root/android/gradlew"
apple=false

if [[ ${1:-} == "--apple" ]]; then
  apple=true
  shift
fi
[[ $# -eq 0 ]] || { echo "Usage: bash scripts/test-kmp.sh [--apple]" >&2; exit 1; }
[[ -x "$gradlew" ]] || { echo "Pinned Gradle wrapper is missing" >&2; exit 1; }

if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
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
  [[ -x "$detected_java_home/bin/javac" ]] || {
    echo "KMP verification requires a complete JDK 17 or 21" >&2
    exit 1
  }
  export JAVA_HOME="$detected_java_home"
fi
export PATH="$JAVA_HOME/bin:$PATH"

java_major="$(javac -version 2>&1 | awk 'NR == 1 { split($2, version, "."); print version[1] }')"
[[ "$java_major" == 17 || "$java_major" == 21 ]] || {
  echo "KMP verification requires JDK 17 or 21, found javac $(javac -version 2>&1)" >&2
  exit 1
}

tasks=(":shared-core:jvmTest")
if $apple; then
  [[ "$(uname -s)" == "Darwin" ]] || { echo "Apple framework verification requires macOS" >&2; exit 1; }
  if [[ ! -d "$root/build/ios-engine/DrawlessFairy.xcframework" ]]; then
    bash "$root/scripts/build-ios-engine.sh"
  fi
  export SIMCTL_CHILD_DRAWLESS_VARIANTS_PATH="$root/engine/variants.ini"
  # The deprecated Intel simulator can run RC1 patch-v2 search far slower than an iPhone.
  # This affects only the test process; production/device builds retain the 2-second policy.
  export SIMCTL_CHILD_DRAWLESS_ENGINE_SEARCH_GRACE_MILLIS=12000
  tasks+=(":shared-core:linkDebugFrameworkIosArm64")
  case "$(uname -m)" in
    arm64)
      tasks+=(":shared-core:linkDebugFrameworkIosSimulatorArm64")
      tasks+=(":shared-core:iosSimulatorArm64Test")
      ;;
    x86_64)
      tasks+=(":shared-core:linkDebugFrameworkIosX64")
      tasks+=(":shared-core:iosX64Test")
      ;;
    *) echo "Unsupported Mac architecture: $(uname -m)" >&2; exit 1 ;;
  esac
fi

"$gradlew" -p "$root/multiplatform" --no-daemon "${tasks[@]}"
