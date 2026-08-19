#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"

if [[ "$#" -ne 1 || -z "${1:-}" ]]; then
  cat >&2 <<'USAGE'
Usage: scripts/source-bundle.sh OUTPUT/drawless-chess-ios-1.0.2-build-2-source.tar.gz

Create the deterministic, inclusion-only iOS corresponding-source archive from
the exact clean Git tree and the validated prepared Fairy-Stockfish staged tree.
Existing output files are never replaced.
USAGE
  exit 2
fi
expected_archive='drawless-chess-ios-1.0.2-build-2-source.tar.gz'
[[ "$(basename -- "$1")" == "$expected_archive" ]] || {
  echo "source-bundle: output filename must be $expected_archive" >&2
  exit 2
}
[[ ! -e "$1" && ! -L "$1" ]] || {
  echo "source-bundle: output already exists: $1" >&2
  exit 1
}

command -v python3 >/dev/null 2>&1 || {
  echo 'source-bundle: python3 is required' >&2
  exit 1
}
command -v git >/dev/null 2>&1 || {
  echo 'source-bundle: git is required' >&2
  exit 1
}

if [[ -n "$(git -C "$repository_root" status --porcelain=v1 --untracked-files=all)" ]]; then
  echo 'source-bundle: repository must be clean before the release rebuild gate' >&2
  exit 1
fi

exec python3 "$script_dir/source-bundle.py" \
  --repository-root "$repository_root" \
  --output "$1"
