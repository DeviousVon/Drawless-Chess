#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$ROOT"
BUILD_ROOT="$ROOT/build/headless"
ENGINE="$BUILD_ROOT/linux-x86_64/drawless-fairy"
RUNNER="$BUILD_ROOT/drawless-selfplay.jar"
TEST_RUNNER="$BUILD_ROOT/drawless-selfplay-tests.jar"
VARIANTS="$ROOT/engine/variants.ini"
PROPERTIES="$ROOT/engine/native/upstream.properties"
PATCH_DIR="$ROOT/engine/patches"
CONFIG="$ROOT/tools/selfplay/config/smoke.properties"
OPENINGS="$ROOT/tools/selfplay/fixtures/openings.tsv"
ENDGAMES="$ROOT/tools/selfplay/fixtures/endgames.tsv"
MAIN_CLASS='com.drawlesschess.selfplay.MainKt'
TEST_MAIN_CLASS='com.drawlesschess.selfplay.SelfPlayRunnerTestMainKt'
TEST_MAIN_SOURCE="$ROOT/tools/selfplay/src/test/kotlin/com/drawlesschess/selfplay/SelfPlayRunnerTestMain.kt"
RUN_TESTS=true

die() {
  printf 'headless-selfplay-validate: %s\n' "$*" >&2
  exit 1
}

property() {
  local file=$1 key=$2
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; found=1; exit }
    END { if (!found) exit 1 }' "$file"
}

while (($#)); do
  case "$1" in
    --config)
      (($# >= 2)) || die '--config requires a path'
      CONFIG=$2
      shift 2
      ;;
    --skip-runner-tests)
      RUN_TESTS=false
      shift
      ;;
    --help|-h)
      printf 'Usage: bash scripts/headless-selfplay-validate.sh [--config PATH] [--skip-runner-tests]\n'
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

for command_name in awk diff file find grep java node readelf sha256sum sort tr unzip; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is missing: $command_name"
done
for required in "$ENGINE" "$ENGINE.sha256" "$ENGINE.metadata" \
  "$RUNNER" "$RUNNER.sha256" "$RUNNER.metadata" \
  "$BUILD_ROOT/drawless-selfplay.sources.sha256" "$VARIANTS" "$CONFIG" "$OPENINGS"; do
  [[ -f "$required" ]] || die "required artifact is missing: $required"
done
[[ -f "$ENDGAMES" || -f "$ROOT/tools/selfplay/fixtures/rule-fixtures.tsv" ]] \
  || die "endgame/rule fixture table is missing: $ENDGAMES"
[[ -x "$ENGINE" ]] || die "engine is not executable: $ENGINE"

(
  cd -- "$(dirname -- "$ENGINE")"
  sha256sum --check --strict "$(basename -- "$ENGINE.sha256")"
)
(
  cd -- "$BUILD_ROOT"
  sha256sum --check --strict "$(basename -- "$RUNNER.sha256")"
)

binary_hash="$(sha256sum "$ENGINE" | awk '{ print $1 }')"
[[ "$(property "$ENGINE.metadata" binarySha256)" == "$binary_hash" ]] \
  || die 'engine metadata hash mismatch'
[[ "$(property "$ENGINE.metadata" revision)" == "$(property "$PROPERTIES" revision)" ]] \
  || die 'engine revision metadata mismatch'
[[ "$(property "$ENGINE.metadata" patchedTree)" == "$(property "$PROPERTIES" patchedTree)" ]] \
  || die 'engine patched-tree metadata mismatch'
[[ "$(property "$ENGINE.metadata" patchSeriesSha256)" == "$(property "$PROPERTIES" patchSeriesSha256)" ]] \
  || die 'engine patch-series metadata mismatch'
[[ "$(property "$ENGINE.metadata" prefetch)" == no ]] || die 'headless engine was not built with prefetch=no'
grep -Fq "prefetch: 'no'" "$BUILD_ROOT/linux-x86_64/engine-build.log" \
  || die 'engine build evidence does not confirm prefetch=no'
readelf -h "$ENGINE" | grep -Eq 'Class:[[:space:]]+ELF64' || die 'engine is not ELF64'
readelf -h "$ENGINE" | grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64' \
  || die 'engine is not Linux x86-64'
node "$PATCH_DIR/verify-engine.mjs" "$ENGINE" "$PATCH_DIR/test-variants.ini" patched

variant_hash="$(sha256sum "$VARIANTS" | awk '{ print $1 }')"
[[ "$variant_hash" == "$(property "$PROPERTIES" variantConfigSha256)" ]] \
  || die 'variant configuration hash mismatch'

unzip -tqq "$RUNNER" || die 'runner jar is corrupt'
unzip -p "$RUNNER" META-INF/MANIFEST.MF | tr -d '\r' \
  | grep -Fqx "Main-Class: $MAIN_CLASS" || die "runner jar main class is not $MAIN_CLASS"
jar_hash="$(sha256sum "$RUNNER" | awk '{ print $1 }')"
[[ "$(property "$RUNNER.metadata" jarSha256)" == "$jar_hash" ]] \
  || die 'runner metadata hash mismatch'

check_source_manifest() {
  local manifest=$1
  shift
  local -a roots=("$@") current=()
  local source relative expected_paths actual_paths
  for source in "${roots[@]}"; do
    [[ -d "$source" ]] || continue
    while IFS= read -r -d '' file_path; do
      current+=("$file_path")
    done < <(find "$source" -type f -name '*.kt' -print0 | sort -z)
  done
  (cd -- "$ROOT" && sha256sum --check --strict "$manifest") \
    || die "source hash verification failed: $manifest"
  expected_paths=''
  for source in "${current[@]}"; do
    case "$source" in
      "$ROOT"/*) relative="${source#"$ROOT"/}" ;;
      *) die "source escaped repository root: $source" ;;
    esac
    expected_paths+="$relative"$'\n'
  done
  actual_paths="$(awk '{ print substr($0, 67) }' "$manifest")"$'\n'
  [[ "$actual_paths" == "$expected_paths" ]] || die "source set changed since build: $manifest"
}

check_source_manifest "$BUILD_ROOT/drawless-selfplay.sources.sha256" \
  "$ROOT/android/core/src/main/kotlin" \
  "$ROOT/tools/selfplay/src/main/kotlin"

java -cp "$RUNNER" "$MAIN_CLASS" --validate-config "$CONFIG"

if [[ -f "$TEST_MAIN_SOURCE" ]]; then
  for required in "$TEST_RUNNER.sha256" "$BUILD_ROOT/drawless-selfplay-tests.sources.sha256"; do
    [[ -f "$required" ]] || die "runner test artifact is incomplete: $required"
  done
  (cd -- "$BUILD_ROOT" && sha256sum --check --strict "$(basename -- "$TEST_RUNNER.sha256")")
  unzip -tqq "$TEST_RUNNER" || die 'runner test jar is corrupt'
  unzip -p "$TEST_RUNNER" META-INF/MANIFEST.MF | tr -d '\r' \
    | grep -Fqx "Main-Class: $TEST_MAIN_CLASS" \
    || die "runner test jar main class is not $TEST_MAIN_CLASS"
  check_source_manifest "$BUILD_ROOT/drawless-selfplay-tests.sources.sha256" \
    "$ROOT/android/core/src/main/kotlin" \
    "$ROOT/tools/selfplay/src/main/kotlin" \
    "$ROOT/tools/selfplay/src/test/kotlin"
  if [[ "$RUN_TESTS" == true ]]; then
    (cd -- "$ROOT" && java -cp "$TEST_RUNNER" "$TEST_MAIN_CLASS")
  fi
elif [[ -f "$TEST_RUNNER" || -f "$TEST_RUNNER.sha256" || \
  -f "$BUILD_ROOT/drawless-selfplay-tests.sources.sha256" ]]; then
  die 'stale runner test artifacts exist without SelfPlayRunnerTestMain.kt'
fi

for text_file in "$CONFIG" "$OPENINGS"; do
  [[ -s "$text_file" ]] || die "input file is empty: $text_file"
  if grep -q $'\r' "$text_file"; then
    die "input file contains CRLF/CR bytes: $text_file"
  fi
done

printf 'Headless self-play artifacts validated.\n'
