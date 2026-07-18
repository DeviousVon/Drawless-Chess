#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
PROPERTIES="$ROOT/engine/native/upstream.properties"
PATCH_DIR="$ROOT/engine/patches"
SOURCE="$ROOT/engine/native/upstream/Fairy-Stockfish"
VARIANTS="$ROOT/engine/variants.ini"
BUILD_ROOT="$ROOT/build/headless"
OUTPUT_DIR="$BUILD_ROOT/linux-x86_64"
OUTPUT="$OUTPUT_DIR/drawless-fairy"
JOBS="${HEADLESS_JOBS:-$(nproc 2>/dev/null || printf '2')}"

usage() {
  cat <<'EOF'
Usage: bash scripts/headless-build-engine.sh [--jobs N]

Build the exact pinned and patched Fairy-Stockfish standalone engine for Linux x86-64.
The verified binary is written to build/headless/linux-x86_64/drawless-fairy.
EOF
}

die() {
  printf 'headless-build-engine: %s\n' "$*" >&2
  exit 1
}

property() {
  local key=$1
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[^=]*=/, "")
      print
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$PROPERTIES"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

while (($#)); do
  case "$1" in
    --jobs)
      (($# >= 2)) || die '--jobs requires a value'
      JOBS=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || die '--jobs must be a positive integer'
((JOBS <= 256)) || die '--jobs must not exceed 256'

for command_name in awk file git grep install make mktemp node nproc readelf sha256sum tee; do
  require_command "$command_name"
done
[[ -f "$PROPERTIES" ]] || die "native lock is missing: $PROPERTIES"
[[ -d "$SOURCE/.git" ]] || die "prepared Fairy-Stockfish Git checkout is missing: $SOURCE"
[[ -f "$PATCH_DIR/checksums.sha256" && -f "$PATCH_DIR/series" ]] \
  || die "patch manifest is incomplete: $PATCH_DIR"
[[ -f "$VARIANTS" ]] || die "variant configuration is missing: $VARIANTS"

revision="$(property revision)" || die 'native lock has no revision'
upstream_tree="$(property tree)" || die 'native lock has no upstream tree'
patched_tree="$(property patchedTree)" || die 'native lock has no patched tree'
patch_series_hash="$(property patchSeriesSha256)" || die 'native lock has no patch-series hash'
patch_version="$(property drawlessPatchVersion)" || die 'native lock has no patch version'
variant_hash="$(property variantConfigSha256)" || die 'native lock has no variant hash'

for hash in "$revision" "$upstream_tree" "$patched_tree" "$patch_series_hash" "$variant_hash"; do
  [[ "$hash" =~ ^[0-9a-f]{40}$ || "$hash" =~ ^[0-9a-f]{64}$ ]] \
    || die "native lock contains an invalid hash: $hash"
done
[[ "$patch_version" =~ ^[1-9][0-9]*$ ]] || die 'native lock contains an invalid patch version'

(
  cd -- "$PATCH_DIR"
  sha256sum --check --strict checksums.sha256
) || die 'patch checksum verification failed'
actual_series_hash="$({
  printf 'series\0'
  cat -- "$PATCH_DIR/series"
  while IFS= read -r patch_name || [[ -n "$patch_name" ]]; do
    patch_name=${patch_name%$'\r'}
    [[ -z "$patch_name" || "$patch_name" == \#* ]] && continue
    [[ "$patch_name" != /* && "$patch_name" != *../* && "$patch_name" != ../* \
      && "$patch_name" != *'/..' && "$patch_name" != *\\* ]] \
      || die "unsafe patch-series entry: $patch_name"
    patch_path="$PATCH_DIR/$patch_name"
    [[ -f "$patch_path" ]] || die "patch-series entry is missing: $patch_name"
    printf '\0patch\0%s\0' "$patch_name"
    cat -- "$patch_path"
  done < "$PATCH_DIR/series"
} | sha256sum | awk '{ print $1 }')"
[[ "$actual_series_hash" == "$patch_series_hash" ]] || die 'patch-series hash does not match native lock'
actual_variant_hash="$(sha256sum "$VARIANTS" | awk '{ print $1 }')"
[[ "$actual_variant_hash" == "$variant_hash" ]] || die 'variant configuration hash does not match native lock'

source_git=(git -c "safe.directory=$SOURCE" -C "$SOURCE")
[[ "$("${source_git[@]}" rev-parse HEAD)" == "$revision" ]] \
  || die 'prepared source revision does not match native lock'
[[ "$("${source_git[@]}" rev-parse 'HEAD^{tree}')" == "$upstream_tree" ]] \
  || die 'prepared upstream tree does not match native lock'
"${source_git[@]}" diff --quiet || die 'prepared source has unstaged tracked changes'
"${source_git[@]}" diff --cached --check || die 'prepared source patch has whitespace errors'
[[ "$("${source_git[@]}" write-tree)" == "$patched_tree" ]] \
  || die 'prepared patched tree does not match native lock'
unexpected_untracked="$(
  "${source_git[@]}" ls-files --others --exclude-standard \
    | grep -vxF '.drawless-source-state.properties' \
    || true
)"
[[ -z "$unexpected_untracked" ]] \
  || die "prepared source contains unexpected untracked files: $unexpected_untracked"

mkdir -p -- "$BUILD_ROOT" "$OUTPUT_DIR"
WORK_PARENT="${TMPDIR:-/tmp}"
[[ "$WORK_PARENT" == /* && -d "$WORK_PARENT" && -w "$WORK_PARENT" ]] \
  || die "temporary build root is not a writable absolute directory: $WORK_PARENT"
WORK_PARENT="$(cd -- "$WORK_PARENT" && pwd -P)"
work="$(mktemp -d "$WORK_PARENT/drawless-headless-engine.XXXXXX")"
cleanup() {
  case "$work" in
    "$WORK_PARENT"/drawless-headless-engine.*) rm -rf -- "$work" ;;
    *) printf 'headless-build-engine: refusing unsafe cleanup path: %s\n' "$work" >&2 ;;
  esac
}
trap cleanup EXIT

git -c "safe.directory=$SOURCE" clone --quiet --no-hardlinks --no-checkout \
  "$SOURCE" "$work/source"
git -C "$work/source" config core.autocrlf false
git -C "$work/source" config core.eol lf
git -C "$work/source" config core.filemode false
git -C "$work/source" checkout --quiet --detach "$revision"
[[ "$(git -C "$work/source" rev-parse 'HEAD^{tree}')" == "$upstream_tree" ]] \
  || die 'temporary source clone has the wrong upstream tree'

while IFS= read -r patch_name || [[ -n "$patch_name" ]]; do
  [[ -z "$patch_name" || "$patch_name" == \#* ]] && continue
  [[ "$patch_name" != */* && "$patch_name" != *\\* ]] \
    || die "unsafe patch-series entry: $patch_name"
  patch_path="$PATCH_DIR/$patch_name"
  [[ -f "$patch_path" ]] || die "patch-series entry is missing: $patch_name"
  git -C "$work/source" apply --check --index "$patch_path"
  git -C "$work/source" apply --index "$patch_path"
done < "$PATCH_DIR/series"

git -C "$work/source" diff --quiet || die 'patched build tree has unstaged changes'
git -C "$work/source" diff --cached --check || die 'patched build tree has whitespace errors'
[[ "$(git -C "$work/source" write-tree)" == "$patched_tree" ]] \
  || die 'independently applied patch tree does not match native lock'

node "$PATCH_DIR/verify-elo-rounding.mjs" "$work/source/src/search.cpp"

# The production Android engine defines NO_PREFETCH. Keep the headless comparison binary
# aligned instead of accepting Fairy-Stockfish's x86-64 preset, which enables prefetch.
make -C "$work/source/src" -j"$JOBS" build ARCH=x86-64 prefetch=no \
  2>&1 | tee "$work/engine-build.log"
grep -Fq "arch: 'x86_64'" "$work/engine-build.log" \
  || die 'build log did not confirm the x86-64 architecture'
grep -Fq "bits: '64'" "$work/engine-build.log" \
  || die 'build log did not confirm a 64-bit binary'
grep -Fq "prefetch: 'no'" "$work/engine-build.log" \
  || die 'build log did not confirm prefetch=no'

built_engine="$work/source/src/stockfish"
[[ -x "$built_engine" ]] || die 'Fairy-Stockfish build did not produce an executable'
readelf_header="$(readelf -h "$built_engine")"
grep -Eq 'Class:[[:space:]]+ELF64' <<<"$readelf_header" \
  || die 'built engine is not ELF64'
grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64' <<<"$readelf_header" \
  || die 'built engine is not Linux x86-64'
node "$PATCH_DIR/verify-engine.mjs" \
  "$built_engine" "$PATCH_DIR/test-variants.ini" patched

temporary_output="$OUTPUT_DIR/.drawless-fairy.$$"
install -m 0755 -- "$built_engine" "$temporary_output"
mv -f -- "$temporary_output" "$OUTPUT"
install -m 0644 -- "$work/engine-build.log" "$OUTPUT_DIR/engine-build.log"

binary_hash="$(sha256sum "$OUTPUT" | awk '{ print $1 }')"
(
  cd -- "$OUTPUT_DIR"
  sha256sum drawless-fairy > drawless-fairy.sha256
)
metadata="$OUTPUT.metadata"
metadata_tmp="$metadata.tmp.$$"
printf '%s\n' \
  'schemaVersion=1' \
  'platform=linux-x86_64' \
  'makeArch=x86-64' \
  'prefetch=no' \
  "revision=$revision" \
  "tree=$upstream_tree" \
  "patchedTree=$patched_tree" \
  "patchSeriesSha256=$patch_series_hash" \
  "drawlessPatchVersion=$patch_version" \
  "variantConfigSha256=$variant_hash" \
  "binarySha256=$binary_hash" \
  > "$metadata_tmp"
mv -f -- "$metadata_tmp" "$metadata"

printf 'Built verified headless engine: %s\n' "$OUTPUT"
printf 'SHA-256: %s\n' "$binary_hash"
