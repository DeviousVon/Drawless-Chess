#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
CORE_ROOT="$ROOT/android/core/src/main/kotlin"
RUNNER_ROOT="$ROOT/tools/selfplay/src/main/kotlin"
TEST_ROOT="$ROOT/tools/selfplay/src/test/kotlin"
COMPILER="$ROOT/node_modules/kotlin-compiler/bin/kotlinc"
BUILD_ROOT="$ROOT/build/headless"
OUTPUT="$BUILD_ROOT/drawless-selfplay.jar"
TEST_OUTPUT="$BUILD_ROOT/drawless-selfplay-tests.jar"
MAIN_CLASS='com.drawlesschess.selfplay.MainKt'
TEST_MAIN_CLASS='com.drawlesschess.selfplay.SelfPlayRunnerTestMainKt'

usage() {
  cat <<'EOF'
Usage: bash scripts/headless-build-runner.sh

Compile the platform-neutral Drawless core plus the headless self-play runner. If
SelfPlayRunnerTestMain.kt exists, a separate executable test jar is built as well.
EOF
}

die() {
  printf 'headless-build-runner: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

if (($#)); then
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
fi

for command_name in awk cmp find grep readlink sha256sum sort tr unzip; do
  require_command "$command_name"
done
[[ -d "$CORE_ROOT" ]] || die "core source root is missing: $CORE_ROOT"
[[ -d "$RUNNER_ROOT" ]] || die "runner source root is missing: $RUNNER_ROOT"
[[ -x "$COMPILER" ]] || die "Kotlin compiler is missing; run npm install: $COMPILER"

if [[ -n "${JAVA_HOME:-}" ]]; then
  JDK_HOME="$(cd -- "$JAVA_HOME" 2>/dev/null && pwd -P)" \
    || die "JAVA_HOME is not a readable directory: $JAVA_HOME"
else
  require_command javac
  javac_path="$(readlink -f "$(command -v javac)")"
  JDK_HOME="$(cd -- "$(dirname -- "$javac_path")/.." && pwd -P)"
fi

JAVA="$JDK_HOME/bin/java"
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"
[[ -x "$JAVA" && -x "$JAVAC" && -x "$JAR" ]] \
  || die "JAVA_HOME must identify a complete JDK with java, javac, and jar: $JDK_HOME"
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

java_version="$($JAVA -XshowSettings:properties -version 2>&1 \
  | awk -F= '$1 ~ /^[[:space:]]*java.version[[:space:]]*$/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')"
javac_version="$($JAVAC -version 2>&1 | awk 'NR == 1 && $1 == "javac" { print $2; exit }')"
[[ -n "$java_version" && -n "$javac_version" ]] || die 'could not determine JDK versions'
java_major="${java_version%%.*}"
javac_major="${javac_version%%.*}"
[[ "$java_major" == "$javac_major" ]] || die "java/javac major mismatch: $java_version / $javac_version"
[[ "$java_major" == 17 || "$java_major" == 21 ]] \
  || die "headless runner requires JDK 17 or 21, found $java_version"

mapfile -d '' -t core_sources < <(find "$CORE_ROOT" -type f -name '*.kt' -print0 | sort -z)
mapfile -d '' -t runner_sources < <(find "$RUNNER_ROOT" -type f -name '*.kt' -print0 | sort -z)
((${#core_sources[@]} > 0)) || die 'no core Kotlin sources were found'
((${#runner_sources[@]} > 0)) || die 'no runner Kotlin sources were found'
production_sources=("${core_sources[@]}" "${runner_sources[@]}")

mkdir -p -- "$BUILD_ROOT"

write_source_manifest() {
  local destination=$1
  shift
  local temporary="$destination.tmp.$$"
  : > "$temporary"
  local source relative hash
  for source in "$@"; do
    case "$source" in
      "$ROOT"/*) relative="${source#"$ROOT"/}" ;;
      *) die "source escaped repository root: $source" ;;
    esac
    hash="$(sha256sum "$source" | awk '{ print $1 }')"
    printf '%s  %s\n' "$hash" "$relative" >> "$temporary"
  done
  mv -f -- "$temporary" "$destination"
}

build_jar() {
  local destination=$1
  local main_class=$2
  local source_manifest=$3
  shift 3
  local -a sources=("$@")
  local temporary="$BUILD_ROOT/.$(basename -- "$destination").$$.jar"
  local before_manifest="$BUILD_ROOT/.$(basename -- "$destination").$$.sources.before"
  local after_manifest="$BUILD_ROOT/.$(basename -- "$destination").$$.sources.after"

  write_source_manifest "$before_manifest" "${sources[@]}"
  if ! "$COMPILER" -jvm-target 17 "${sources[@]}" -include-runtime -d "$temporary"; then
    rm -f -- "$temporary" "$before_manifest" "$after_manifest"
    die "Kotlin compilation failed: $destination"
  fi
  write_source_manifest "$after_manifest" "${sources[@]}"
  if ! cmp -s -- "$before_manifest" "$after_manifest"; then
    rm -f -- "$temporary" "$before_manifest" "$after_manifest"
    die "Kotlin sources changed while compiling: $destination"
  fi
  if ! unzip -p "$temporary" META-INF/MANIFEST.MF | tr -d '\r' \
    | grep -Fqx "Main-Class: $main_class"; then
    "$JAR" --update --file "$temporary" --main-class "$main_class"
  fi
  unzip -tqq "$temporary" || die "jar integrity check failed: $destination"
  unzip -p "$temporary" META-INF/MANIFEST.MF | tr -d '\r' \
    | grep -Fqx "Main-Class: $main_class" \
    || die "jar manifest does not name $main_class"
  mv -f -- "$temporary" "$destination"
  mv -f -- "$before_manifest" "$source_manifest"
  rm -f -- "$after_manifest"
  (
    cd -- "$BUILD_ROOT"
    sha256sum "$(basename -- "$destination")" > "$(basename -- "$destination").sha256"
  )
}

build_jar \
  "$OUTPUT" \
  "$MAIN_CLASS" \
  "$BUILD_ROOT/drawless-selfplay.sources.sha256" \
  "${production_sources[@]}"

mapfile -d '' -t test_sources < <(
  if [[ -d "$TEST_ROOT" ]]; then
    find "$TEST_ROOT" -type f -name '*.kt' -print0 | sort -z
  fi
)
test_main_file=''
for source in "${test_sources[@]}"; do
  if [[ "$(basename -- "$source")" == 'SelfPlayRunnerTestMain.kt' ]]; then
    test_main_file=$source
    break
  fi
done

if ((${#test_sources[@]} > 0)) && [[ -n "$test_main_file" ]]; then
  test_jar_sources=("${production_sources[@]}" "${test_sources[@]}")
  build_jar \
    "$TEST_OUTPUT" \
    "$TEST_MAIN_CLASS" \
    "$BUILD_ROOT/drawless-selfplay-tests.sources.sha256" \
    "${test_jar_sources[@]}"
  printf 'Built headless runner tests: %s\n' "$TEST_OUTPUT"
else
  rm -f -- \
    "$TEST_OUTPUT" \
    "$TEST_OUTPUT.sha256" \
    "$BUILD_ROOT/drawless-selfplay-tests.sources.sha256"
  if ((${#test_sources[@]} > 0)); then
    printf 'Skipped runner test jar: SelfPlayRunnerTestMain.kt is absent.\n' >&2
  else
    printf 'Skipped runner test jar: no test sources are present.\n'
  fi
fi

compiler_version="$($COMPILER -version 2>&1 | awk 'NR == 1 { print; exit }')"
jar_hash="$(sha256sum "$OUTPUT" | awk '{ print $1 }')"
sources_hash="$(sha256sum "$BUILD_ROOT/drawless-selfplay.sources.sha256" | awk '{ print $1 }')"
metadata="$OUTPUT.metadata"
metadata_tmp="$metadata.tmp.$$"
printf '%s\n' \
  'schemaVersion=1' \
  'jvmTarget=17' \
  "mainClass=$MAIN_CLASS" \
  "javaVersion=$java_version" \
  "javacVersion=$javac_version" \
  "compiler=$compiler_version" \
  "sourcesManifestSha256=$sources_hash" \
  "jarSha256=$jar_hash" \
  > "$metadata_tmp"
mv -f -- "$metadata_tmp" "$metadata"

printf 'Built headless runner: %s\n' "$OUTPUT"
printf 'SHA-256: %s\n' "$jar_hash"
