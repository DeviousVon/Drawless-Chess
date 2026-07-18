#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$ROOT"
BUILD_ROOT="$ROOT/build/headless"
PUZZLE_ROOT="$BUILD_ROOT/puzzles"
ENGINE="$BUILD_ROOT/linux-x86_64/drawless-fairy"
RUNNER="$BUILD_ROOT/drawless-selfplay.jar"
VARIANTS="$ROOT/engine/variants.ini"
BUILD=true

usage() {
  cat <<'EOF'
Usage:
  bash scripts/headless-puzzles.sh mine \
    --input REPORT_OR_RUN_DIRECTORY [--input ...] --output build/headless/puzzles/NAME.jsonl \
    [--replace] [--no-build]

  bash scripts/headless-puzzles.sh verify \
    --input build/headless/puzzles/CANDIDATES.jsonl \
    --output build/headless/puzzles/VERIFIED.jsonl \
    [--primary-nodes 250000] [--confirm-nodes 1000000] [--multi-pv 5] \
    [--parallel 4] [--hash-mb 64] [--min-advantage-cp 150] [--min-gap-cp 120] \
    [--max-candidates N] [--replace] [--no-build]

Verification is resumable by default. --replace intentionally starts a new verification report.
All generated outputs must remain under build/headless/puzzles.
EOF
}

die() {
  printf 'headless-puzzles: %s\n' "$*" >&2
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
  [[ "$(sha256sum "$destination" | awk '{ print $1 }')" == "$expected_hash" ]] \
    || die "existing snapshot hash mismatch: $destination"
}

(($#)) || { usage; exit 2; }
case "$1" in
  mine|verify) command_name=$1 ;;
  --help|-h) usage; exit 0 ;;
  *) die "first argument must be mine or verify" ;;
esac
shift

declare -a forwarded=()
output=''
while (($#)); do
  case "$1" in
    --no-build)
      BUILD=false
      shift
      ;;
    --engine|--variants)
      die "$1 is managed by the verified snapshot wrapper"
      ;;
    --output)
      (($# >= 2)) || die '--output requires a path'
      output=$2
      forwarded+=("$1" "$2")
      shift 2
      ;;
    *)
      forwarded+=("$1")
      shift
      ;;
  esac
done

[[ -n "$output" ]] || die '--output is required'
output_absolute="$(realpath -m -- "$output")"
case "$output_absolute" in
  "$PUZZLE_ROOT"/*) ;;
  *) die "output must remain under $PUZZLE_ROOT" ;;
esac
mkdir -p -- "$PUZZLE_ROOT"

if [[ "$BUILD" == true ]]; then
  if [[ "$command_name" == verify ]]; then
    bash "$ROOT/scripts/headless-build-engine.sh"
  fi
  bash "$ROOT/scripts/headless-build-runner.sh"
fi

for required in "$RUNNER" "$RUNNER.sha256"; do
  [[ -f "$required" ]] || die "verified input is missing: $required"
done
runner_hash="$(awk 'NF == 2 { print $1; exit }' "$RUNNER.sha256")"
[[ "$runner_hash" =~ ^[0-9a-f]{64}$ ]] || die 'runner SHA-256 metadata is invalid'
[[ "$(sha256sum "$RUNNER" | awk '{ print $1 }')" == "$runner_hash" ]] \
  || die 'runner does not match its SHA-256 metadata'

if [[ "$command_name" == mine ]]; then
  exec java -jar "$RUNNER" --mine-puzzles "${forwarded[@]}"
fi

for required in "$ENGINE" "$ENGINE.sha256" "$VARIANTS"; do
  [[ -f "$required" ]] || die "verified input is missing: $required"
done
engine_hash="$(awk 'NF == 2 { print $1; exit }' "$ENGINE.sha256")"
variants_hash="$(sha256sum "$VARIANTS" | awk '{ print $1 }')"
for hash in "$engine_hash" "$variants_hash"; do
  [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || die "invalid SHA-256 metadata: $hash"
done
[[ "$(sha256sum "$ENGINE" | awk '{ print $1 }')" == "$engine_hash" ]] \
  || die 'engine does not match its SHA-256 metadata'

snapshot_id="$(printf '%s\0%s\0%s\0' "$engine_hash" "$runner_hash" "$variants_hash" \
  | sha256sum | awk '{ print $1 }')"
snapshot_root="$BUILD_ROOT/puzzle-snapshots/$snapshot_id"
mkdir -p -- "$snapshot_root"
snapshot_engine="$snapshot_root/drawless-fairy"
snapshot_runner="$snapshot_root/drawless-selfplay.jar"
snapshot_variants="$snapshot_root/variants.ini"
snapshot_file "$ENGINE" "$snapshot_engine" "$engine_hash" 0555
snapshot_file "$RUNNER" "$snapshot_runner" "$runner_hash" 0444
snapshot_file "$VARIANTS" "$snapshot_variants" "$variants_hash" 0444

manifest="$snapshot_root/snapshot.manifest"
expected_manifest="schemaVersion=1
engineSha256=$engine_hash
runnerSha256=$runner_hash
variantsSha256=$variants_hash"
if [[ ! -f "$manifest" ]]; then
  printf '%s\n' "$expected_manifest" > "$manifest.tmp.$$"
  chmod 0444 "$manifest.tmp.$$"
  mv -n -- "$manifest.tmp.$$" "$manifest"
  rm -f -- "$manifest.tmp.$$"
fi
[[ "$(cat -- "$manifest")" == "$expected_manifest" ]] || die 'snapshot manifest mismatch'

exec java -jar "$snapshot_runner" --verify-puzzles \
  "${forwarded[@]}" --engine "$snapshot_engine" --variants "$snapshot_variants"
