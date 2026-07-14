#!/usr/bin/env bash
set -euo pipefail

PIN="fb78cb561aa01708338e35b3dc3b65a42149a3c4"
UPSTREAM_TREE="dfe4b96037c10ab60e22613bf634452612fc2b04"
PATCHED_TREE="80208e5f35549b88505df983e4bc0f7621083fd4"
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

node "$PATCH_DIR/verify-engine.mjs" \
  "$WORK/source/src/stockfish" \
  "$PATCH_DIR/test-variants.ini" \
  patched

echo "ok - patch set applies, compiles, advertises identity, and passes native parity gates"
