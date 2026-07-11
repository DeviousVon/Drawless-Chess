#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
NATIVE_ROOT="$REPOSITORY_ROOT/engine/native"
LOCK_FILE="$NATIVE_ROOT/upstream.properties"

property() {
    local key=$1
    awk -F= -v key="$key" '
        $1 == key {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
            print
            exit
        }
    ' "$LOCK_FILE"
}

die() {
    printf 'native-fetch-fairy: %s\n' "$*" >&2
    exit 1
}

hash_file() {
    local file=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{ print $1 }'
    else
        die "sha256sum or shasum is required"
    fi
}

usage() {
    cat <<'USAGE'
Usage: scripts/native-fetch-fairy.sh [--upstream-only] [--destination PATH]

Fetch the exact native Fairy-Stockfish commit, verify its Git tree, and apply
the ordered Drawless patch series. Existing destinations are never replaced.

--upstream-only  Fetch and verify the canonical upstream base without patches.
                 This checkout is for inspection and is rejected by Gradle.
--destination    Override engine/native/upstream/Fairy-Stockfish.
USAGE
}

APPLY_PATCHES=true
DESTINATION=""
while (($#)); do
    case "$1" in
        --upstream-only)
            APPLY_PATCHES=false
            shift
            ;;
        --destination)
            (($# >= 2)) || die "--destination requires a path"
            DESTINATION=$2
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown argument '$1' (use --help)"
            ;;
    esac
done

[[ -f "$LOCK_FILE" ]] || die "missing lock file: $LOCK_FILE"

REPOSITORY=$(property repository)
REVISION=$(property revision)
TREE=$(property tree)
PATCHED_TREE=$(property patchedTree)
SOURCE_DIRECTORY=$(property sourceDirectory)
PATCH_SERIES_RELATIVE=$(property patchSeries)
EXPECTED_PATCH_HASH=$(property patchSeriesSha256)
PATCH_VERSION=$(property drawlessPatchVersion)

[[ "$REPOSITORY" == https://github.com/fairy-stockfish/Fairy-Stockfish.git ]] \
    || die "unexpected upstream repository in lock"
[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || die "revision must be a full 40-character Git hash"
[[ "$TREE" =~ ^[0-9a-f]{40}$ ]] || die "tree must be a full 40-character Git hash"
[[ "$PATCHED_TREE" =~ ^[0-9a-f]{40}$ ]] || die "patched tree must be a full 40-character Git hash"
[[ "$EXPECTED_PATCH_HASH" =~ ^[0-9a-f]{64}$ ]] || die "patch-series SHA-256 is invalid"
[[ "$PATCH_VERSION" =~ ^[1-9][0-9]*$ ]] || die "invalid Drawless patch version"

if [[ -z "$DESTINATION" ]]; then
    DESTINATION="$NATIVE_ROOT/$SOURCE_DIRECTORY"
elif [[ "$DESTINATION" != /* ]]; then
    DESTINATION="$PWD/$DESTINATION"
fi

[[ ! -e "$DESTINATION" ]] \
    || die "destination already exists; validate it or remove it deliberately: $DESTINATION"

PATCH_SERIES="$NATIVE_ROOT/$PATCH_SERIES_RELATIVE"
if $APPLY_PATCHES; then
    [[ -f "$PATCH_SERIES" ]] || die "missing ordered patch series: $PATCH_SERIES"
fi

DESTINATION_PARENT=$(dirname -- "$DESTINATION")
mkdir -p "$DESTINATION_PARENT"
TEMP_ROOT=$(mktemp -d "$DESTINATION_PARENT/.fairy-fetch.XXXXXX")
TEMP_CHECKOUT="$TEMP_ROOT/Fairy-Stockfish"
PATCH_HASH_INPUT="$TEMP_ROOT/patch-series.sha256-input"
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

git init --quiet "$TEMP_CHECKOUT"
git -C "$TEMP_CHECKOUT" config core.autocrlf false
git -C "$TEMP_CHECKOUT" config core.eol lf
git -C "$TEMP_CHECKOUT" config core.filemode false
git -C "$TEMP_CHECKOUT" remote add origin "$REPOSITORY"
git -C "$TEMP_CHECKOUT" fetch --quiet --depth 1 --no-tags origin "$REVISION"
git -C "$TEMP_CHECKOUT" checkout --quiet --detach FETCH_HEAD

ACTUAL_REVISION=$(git -C "$TEMP_CHECKOUT" rev-parse HEAD)
ACTUAL_TREE=$(git -C "$TEMP_CHECKOUT" rev-parse 'HEAD^{tree}')
[[ "$ACTUAL_REVISION" == "$REVISION" ]] \
    || die "fetched revision mismatch: expected $REVISION, received $ACTUAL_REVISION"
[[ "$ACTUAL_TREE" == "$TREE" ]] \
    || die "fetched tree mismatch: expected $TREE, received $ACTUAL_TREE"

PATCH_HASH=none
if $APPLY_PATCHES; then
    PATCH_DIRECTORY=$(dirname -- "$PATCH_SERIES")
    : > "$PATCH_HASH_INPUT"
    printf 'series\0' >> "$PATCH_HASH_INPUT"
    command cat "$PATCH_SERIES" >> "$PATCH_HASH_INPUT"

    PATCH_COUNT=0
    while IFS= read -r patch_entry || [[ -n "$patch_entry" ]]; do
        patch_entry=${patch_entry%$'\r'}
        [[ -n "$patch_entry" && "$patch_entry" != \#* ]] || continue
        case "$patch_entry" in
            /*|*../*|../*|*'/..')
                die "unsafe patch path in series: $patch_entry"
                ;;
        esac

        patch_file="$PATCH_DIRECTORY/$patch_entry"
        [[ -f "$patch_file" ]] || die "series references missing patch: $patch_file"
        printf '\0patch\0%s\0' "$patch_entry" >> "$PATCH_HASH_INPUT"
        command cat "$patch_file" >> "$PATCH_HASH_INPUT"

        git -C "$TEMP_CHECKOUT" apply --check --index "$patch_file"
        git -C "$TEMP_CHECKOUT" apply --index "$patch_file"
        PATCH_COUNT=$((PATCH_COUNT + 1))
    done < "$PATCH_SERIES"

    ((PATCH_COUNT > 0)) || die "the production patch series is empty"
    git -C "$TEMP_CHECKOUT" diff --cached --check
    PATCH_HASH=$(hash_file "$PATCH_HASH_INPUT")
    [[ "$PATCH_HASH" == "$EXPECTED_PATCH_HASH" ]] \
        || die "patch series SHA-256 mismatch: expected $EXPECTED_PATCH_HASH, received $PATCH_HASH"
    ACTUAL_PATCHED_TREE=$(git -C "$TEMP_CHECKOUT" write-tree)
    [[ "$ACTUAL_PATCHED_TREE" == "$PATCHED_TREE" ]] \
        || die "patched tree mismatch: expected $PATCHED_TREE, received $ACTUAL_PATCHED_TREE"
fi

STATE_FILE="$TEMP_CHECKOUT/.drawless-source-state.properties"
{
    printf 'schemaVersion=1\n'
    printf 'upstreamRevision=%s\n' "$REVISION"
    printf 'upstreamTree=%s\n' "$TREE"
    printf 'patchedTree=%s\n' "$PATCHED_TREE"
    printf 'patchVersion=%s\n' "$PATCH_VERSION"
    printf 'patchesApplied=%s\n' "$APPLY_PATCHES"
    printf 'patchSeriesSha256=%s\n' "$PATCH_HASH"
} > "$STATE_FILE"

mv "$TEMP_CHECKOUT" "$DESTINATION"
printf 'Prepared Fairy-Stockfish source at %s\n' "$DESTINATION"
printf '  upstream revision: %s\n' "$REVISION"
printf '  upstream tree:     %s\n' "$TREE"
printf '  patches applied:   %s\n' "$APPLY_PATCHES"
printf '  patch series hash: %s\n' "$PATCH_HASH"
