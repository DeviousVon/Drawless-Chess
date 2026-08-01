#!/usr/bin/env bash
set -euo pipefail

PIN="fb78cb561aa01708338e35b3dc3b65a42149a3c4"
UPSTREAM_TREE="dfe4b96037c10ab60e22613bf634452612fc2b04"
PATCHED_TREE="bf58452cf6bb2254050e7aa442d2b23f3664aaec"
UPSTREAM="https://github.com/fairy-stockfish/Fairy-Stockfish.git"
PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE=""
JOBS="${JOBS:-2}"

while (($#)); do
  case "$1" in
    --source)
      SOURCE="$2"
      shift 2
      ;;
    --jobs)
      JOBS="$2"
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/drawless-fairy-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if [[ -n "$SOURCE" ]]; then
  git clone --no-hardlinks "$SOURCE" "$WORK/source"
else
  git clone --filter=blob:none --no-checkout "$UPSTREAM" "$WORK/source"
fi

git -C "$WORK/source" checkout --detach "$PIN"
[[ "$(git -C "$WORK/source" rev-parse HEAD)" == "$PIN" ]]
[[ "$(git -C "$WORK/source" rev-parse 'HEAD^{tree}')" == "$UPSTREAM_TREE" ]]

(cd "$PATCH_DIR" && sha256sum --check checksums.sha256)

make -C "$WORK/source/src" -j"$JOBS" build ARCH=x86-64
node "$PATCH_DIR/verify-engine.mjs" \
  "$WORK/source/src/stockfish" \
  "$PATCH_DIR/test-variants-unpatched.ini" \
  unpatched

while IFS= read -r patch; do
  [[ -z "$patch" || "$patch" == \#* ]] && continue
  git -C "$WORK/source" apply --check --index "$PATCH_DIR/$patch"
  git -C "$WORK/source" apply --index "$PATCH_DIR/$patch"
done < "$PATCH_DIR/series"

git -C "$WORK/source" diff --cached --check
[[ "$(git -C "$WORK/source" write-tree)" == "$PATCHED_TREE" ]]
node "$PATCH_DIR/verify-elo-rounding.mjs" "$WORK/source/src/search.cpp"
make -C "$WORK/source/src" -j"$JOBS" build ARCH=x86-64

# Link a verification-only executable against the just-built engine objects so
# null-move and repetition-key state can be asserted directly instead of being
# inferred from a fragile UCI search position.
STATE_TEST_OBJECT="$WORK/drawless-native-state-test.o"
STATE_TEST_BINARY="$WORK/drawless-native-state-test"
ENGINE_OBJECTS=()
while IFS= read -r source_entry || [[ -n "$source_entry" ]]; do
  source_entry=${source_entry%$'\r'}
  [[ -n "$source_entry" && "$source_entry" != \#* ]] || continue
  object_name="$(basename "${source_entry%.cpp}").o"
  [[ -f "$WORK/source/src/$object_name" ]]
  ENGINE_OBJECTS+=("$WORK/source/src/$object_name")
done < "$PATCH_DIR/../native/source-manifest.txt"

g++ -std=c++17 -O2 -m64 -pthread \
  -Wall -Wcast-qual -fno-exceptions -fno-strict-aliasing \
  -DIS_64BIT -DUSE_PTHREADS -DNNUE_EMBEDDING_OFF -DUSE_SSE2 -DNO_PREFETCH \
  -I"$WORK/source/src" \
  -c "$PATCH_DIR/drawless-native-state-test.cpp" -o "$STATE_TEST_OBJECT"
g++ -m64 -pthread -flto "$STATE_TEST_OBJECT" "${ENGINE_OBJECTS[@]}" \
  -o "$STATE_TEST_BINARY"
"$STATE_TEST_BINARY" "$PATCH_DIR/test-variants.ini"

node "$PATCH_DIR/verify-engine.mjs" \
  "$WORK/source/src/stockfish" \
  "$PATCH_DIR/test-variants.ini" \
  patched

echo "ok - patch set applies, compiles, advertises identity, and passes native parity gates"
