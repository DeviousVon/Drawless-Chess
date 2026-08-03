#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
gradlew="$root/android/gradlew"

if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
  detected_java_home=""
  if [[ -x /usr/libexec/java_home ]]; then
    detected_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
  if [[ ! -x "$detected_java_home/bin/javac" && -d "$HOME/.local/share/jdks" ]]; then
    while IFS= read -r javac; do
      detected_java_home="$(dirname "$(dirname "$javac")")"
      break
    done < <(find "$HOME/.local/share/jdks" -type f -path '*/Contents/Home/bin/javac' -print | sort)
  fi
  [[ -x "$detected_java_home/bin/javac" ]] || {
    echo "Xcode KMP integration requires a complete JDK 17 or 21" >&2
    exit 1
  }
  export JAVA_HOME="$detected_java_home"
fi
export PATH="$JAVA_HOME/bin:$PATH"
java_major="$(javac -version 2>&1 | awk 'NR == 1 { split($2, version, "."); print version[1] }')"
[[ "$java_major" == 17 || "$java_major" == 21 ]] || {
  echo "Xcode KMP integration requires JDK 17 or 21, found javac $(javac -version 2>&1)" >&2
  exit 1
}

if [[ ! -d "$root/build/ios-engine/DrawlessFairy.xcframework" ]]; then
  bash "$root/scripts/build-ios-engine.sh"
fi

"$gradlew" -p "$root/multiplatform" --no-daemon \
  :shared-core:embedAndSignAppleFrameworkForXcode
