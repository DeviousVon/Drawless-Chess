#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$ROOT"
BUILD_ROOT="$ROOT/build/headless"
RUNS_ROOT="$BUILD_ROOT/runs"
ENGINE="$BUILD_ROOT/linux-x86_64/drawless-fairy"
RUNNER="$BUILD_ROOT/drawless-selfplay.jar"
VARIANTS="$ROOT/engine/variants.ini"
ACTIVE_ENGINE="$ENGINE"
ACTIVE_RUNNER="$RUNNER"
ACTIVE_VARIANTS="$VARIANTS"
CONFIG="$ROOT/tools/selfplay/config/smoke.properties"
OUTPUT=''
JOBS="${HEADLESS_JOBS:-$(nproc 2>/dev/null || printf '2')}"
MAIN_CLASS='com.drawlesschess.selfplay.MainKt'
BUILD=true
VALIDATE_ONLY=false
RUN_TESTS=true

usage() {
  cat <<'EOF'
Usage: bash scripts/headless-selfplay.sh [options]

Options:
  --config PATH          Source properties file (default: smoke.properties)
  --output PATH          JSONL output under build/headless (stable default per config)
  --jobs N               Parallel compile jobs for Fairy-Stockfish
  --no-build             Reuse existing verified engine and runner artifacts
  --validate-only        Build/validate without starting self-play
  --skip-runner-tests    Validate but do not execute the runner test jar
  --help                 Show this help
EOF
}

die() {
  printf 'headless-selfplay: %s\n' "$*" >&2
  exit 1
}

snapshot_file() {
  local source=$1 destination=$2 expected_hash=$3 mode=$4
  local temporary="$destination.tmp.$$"
  if [[ ! -f "$destination" ]]; then
    install -m "$mode" -- "$source" "$temporary"
    [[ "$(sha256sum "$temporary" | awk '{ print $1 }')" == "$expected_hash" ]] \
      || { rm -f -- "$temporary"; die "snapshot copy hash mismatch: $source"; }
    mv -n -- "$temporary" "$destination"
    rm -f -- "$temporary"
  fi
  [[ -f "$destination" ]] || die "snapshot is missing: $destination"
  [[ "$(sha256sum "$destination" | awk '{ print $1 }')" == "$expected_hash" ]] \
    || die "existing snapshot hash mismatch: $destination"
}

absolute_path() {
  local value=$1
  if [[ "$value" == /* ]]; then
    realpath -m -- "$value"
  else
    realpath -m -- "$ROOT/$value"
  fi
}

optional_property() {
  local key=$1
  awk -F= -v wanted="$key" '
    /^[[:space:]]*[#!]/ { next }
    {
      key = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key == wanted) {
        sub(/^[^=]*=/, "")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "")
        print
        count++
      }
    }
    END {
      if (count == 0) exit 1
      if (count != 1) exit 42
    }
  ' "$CONFIG"
}

while (($#)); do
  case "$1" in
    --config)
      (($# >= 2)) || die '--config requires a path'
      CONFIG="$(absolute_path "$2")"
      shift 2
      ;;
    --output)
      (($# >= 2)) || die '--output requires a path'
      OUTPUT="$(absolute_path "$2")"
      shift 2
      ;;
    --jobs)
      (($# >= 2)) || die '--jobs requires a value'
      JOBS=$2
      shift 2
      ;;
    --no-build)
      BUILD=false
      shift
      ;;
    --validate-only)
      VALIDATE_ONLY=true
      shift
      ;;
    --skip-runner-tests)
      RUN_TESTS=false
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || die '--jobs must be a positive integer'
((JOBS <= 256)) || die '--jobs must not exceed 256'
[[ -f "$CONFIG" ]] || die "config is not a file: $CONFIG"

mkdir -p -- "$RUNS_ROOT" "$BUILD_ROOT/runtime-configs"
config_name="$(basename -- "$CONFIG")"
config_stem="${config_name%.properties}"
if [[ -z "$OUTPUT" ]]; then
  OUTPUT="$RUNS_ROOT/$config_stem.jsonl"
fi
case "$OUTPUT" in
  "$RUNS_ROOT"/*) ;;
  *) die "output must remain under $RUNS_ROOT" ;;
esac
[[ ! -d "$OUTPUT" ]] || die "output is a directory: $OUTPUT"

if [[ "$BUILD" == true ]]; then
  bash "$ROOT/scripts/headless-build-engine.sh" --jobs "$JOBS"
  bash "$ROOT/scripts/headless-build-runner.sh"
fi

for command_name in awk chmod install realpath sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is missing: $command_name"
done
for required in "$ENGINE" "$ENGINE.sha256" "$RUNNER" "$RUNNER.sha256" "$VARIANTS"; do
  [[ -f "$required" ]] || die "verified input is missing: $required"
done
engine_hash="$(awk 'NF == 2 { print $1; exit }' "$ENGINE.sha256")"
runner_hash="$(awk 'NF == 2 { print $1; exit }' "$RUNNER.sha256")"
variants_hash="$(sha256sum "$VARIANTS" | awk '{ print $1 }')"
for hash in "$engine_hash" "$runner_hash" "$variants_hash"; do
  [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || die "invalid input SHA-256: $hash"
done
[[ "$(sha256sum "$ENGINE" | awk '{ print $1 }')" == "$engine_hash" ]] \
  || die 'engine changed before snapshot creation'
[[ "$(sha256sum "$RUNNER" | awk '{ print $1 }')" == "$runner_hash" ]] \
  || die 'runner changed before snapshot creation'

declare -a fixture_keys=() fixture_sources=() fixture_hashes=()
for key in openingsPath ladderLevelsPath adjacentMatchupsPath; do
  if value="$(optional_property "$key")"; then
    source_path="$(absolute_path "$value")"
    [[ -f "$source_path" ]] || die "$key is not a file: $source_path"
    fixture_keys+=("$key")
    fixture_sources+=("$source_path")
    fixture_hashes+=("$(sha256sum "$source_path" | awk '{ print $1 }')")
  else
    status=$?
    ((status == 1)) || die "config must contain at most one $key property"
  fi
done

snapshot_id="$({
  printf '%s\0%s\0%s\0' "$engine_hash" "$runner_hash" "$variants_hash"
  for index in "${!fixture_keys[@]}"; do
    printf '%s\0%s\0' "${fixture_keys[$index]}" "${fixture_hashes[$index]}"
  done
} | sha256sum | awk '{ print $1 }')"
snapshot_root="$BUILD_ROOT/snapshots/$snapshot_id"
mkdir -p -- "$snapshot_root"
ACTIVE_ENGINE="$snapshot_root/drawless-fairy"
ACTIVE_RUNNER="$snapshot_root/drawless-selfplay.jar"
ACTIVE_VARIANTS="$snapshot_root/variants.ini"
snapshot_file "$ENGINE" "$ACTIVE_ENGINE" "$engine_hash" 0555
snapshot_file "$RUNNER" "$ACTIVE_RUNNER" "$runner_hash" 0444
snapshot_file "$VARIANTS" "$ACTIVE_VARIANTS" "$variants_hash" 0444

declare -A active_fixture_paths=()
for index in "${!fixture_keys[@]}"; do
  key="${fixture_keys[$index]}"
  destination="$snapshot_root/$key.tsv"
  snapshot_file \
    "${fixture_sources[$index]}" \
    "$destination" \
    "${fixture_hashes[$index]}" \
    0444
  active_fixture_paths["$key"]="$destination"
done

snapshot_manifest_tmp="$snapshot_root/snapshot.manifest.tmp.$$"
{
  printf 'schemaVersion=1\n'
  printf 'engineSha256=%s\n' "$engine_hash"
  printf 'runnerSha256=%s\n' "$runner_hash"
  printf 'variantsSha256=%s\n' "$variants_hash"
  for index in "${!fixture_keys[@]}"; do
    printf 'fixture.%s.sha256=%s\n' "${fixture_keys[$index]}" "${fixture_hashes[$index]}"
  done
} > "$snapshot_manifest_tmp"
snapshot_manifest_hash="$(sha256sum "$snapshot_manifest_tmp" | awk '{ print $1 }')"
snapshot_manifest="$snapshot_root/snapshot.manifest"
if [[ ! -f "$snapshot_manifest" ]]; then
  chmod 0444 "$snapshot_manifest_tmp"
  mv -n -- "$snapshot_manifest_tmp" "$snapshot_manifest"
fi
rm -f -- "$snapshot_manifest_tmp"
[[ "$(sha256sum "$snapshot_manifest" | awk '{ print $1 }')" == "$snapshot_manifest_hash" ]] \
  || die "existing snapshot manifest mismatch: $snapshot_manifest"

runtime_tmp="$BUILD_ROOT/runtime-configs/.$config_stem.$$.properties"
awk \
  -v engine="$ACTIVE_ENGINE" \
  -v variants="$ACTIVE_VARIANTS" \
  -v output="$OUTPUT" '
    /^[[:space:]]*enginePath[[:space:]]*=/ {
      print "enginePath=" engine
      engine_count++
      next
    }
    /^[[:space:]]*variantsPath[[:space:]]*=/ {
      print "variantsPath=" variants
      variants_count++
      next
    }
    /^[[:space:]]*outputPath[[:space:]]*=/ {
      print "outputPath=" output
      output_count++
      next
    }
    /^[[:space:]]*openingsPath[[:space:]]*=/ && openings != "" {
      print "openingsPath=" openings
      next
    }
    /^[[:space:]]*ladderLevelsPath[[:space:]]*=/ && ladder != "" {
      print "ladderLevelsPath=" ladder
      next
    }
    /^[[:space:]]*adjacentMatchupsPath[[:space:]]*=/ && adjacent != "" {
      print "adjacentMatchupsPath=" adjacent
      next
    }
    { print }
    END {
      if (engine_count != 1 || variants_count != 1 || output_count != 1) exit 42
    }
  ' \
  openings="${active_fixture_paths[openingsPath]-}" \
  ladder="${active_fixture_paths[ladderLevelsPath]-}" \
  adjacent="${active_fixture_paths[adjacentMatchupsPath]-}" \
  "$CONFIG" > "$runtime_tmp" \
  || {
    rm -f -- "$runtime_tmp"
    die 'config must contain exactly one enginePath, variantsPath, and outputPath property'
  }
runtime_hash="$(sha256sum "$runtime_tmp" | awk '{ print $1 }')"
runtime_config="$BUILD_ROOT/runtime-configs/$runtime_hash.properties"
if [[ ! -f "$runtime_config" ]]; then
  chmod 0444 "$runtime_tmp"
  mv -n -- "$runtime_tmp" "$runtime_config"
fi
rm -f -- "$runtime_tmp"
[[ "$(sha256sum "$runtime_config" | awk '{ print $1 }')" == "$runtime_hash" ]] \
  || die "existing runtime config hash mismatch: $runtime_config"

validation_args=(--config "$runtime_config")
if [[ "$RUN_TESTS" != true ]]; then
  validation_args+=(--skip-runner-tests)
fi
bash "$ROOT/scripts/headless-selfplay-validate.sh" "${validation_args[@]}"

[[ "$(sha256sum "$ENGINE" | awk '{ print $1 }')" == "$engine_hash" ]] \
  || die 'engine changed while validation was running; retry with a stable build'
[[ "$(sha256sum "$RUNNER" | awk '{ print $1 }')" == "$runner_hash" ]] \
  || die 'runner changed while validation was running; retry with a stable build'
[[ "$(sha256sum "$VARIANTS" | awk '{ print $1 }')" == "$variants_hash" ]] \
  || die 'variants changed while validation was running; retry with a stable build'
for index in "${!fixture_keys[@]}"; do
  [[ "$(sha256sum "${fixture_sources[$index]}" | awk '{ print $1 }')" == \
    "${fixture_hashes[$index]}" ]] \
    || die "${fixture_keys[$index]} changed while validation was running; retry"
done

if [[ "$VALIDATE_ONLY" == true ]]; then
  printf 'Headless build and validation completed without starting self-play.\n'
  exit 0
fi

exec java -cp "$ACTIVE_RUNNER" "$MAIN_CLASS" --config "$runtime_config"
