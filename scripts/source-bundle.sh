#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'source-bundle: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'USAGE'
Usage: scripts/source-bundle.sh OUTPUT.tar.gz

Create the whole-project GPL corresponding-source archive. The exact prepared
Fairy-Stockfish checkout is required. Existing output files are never replaced.
USAGE
}

if (($# != 1)); then
    usage >&2
    exit 2
fi

OUTPUT=$1
if [[ "$OUTPUT" != /* ]]; then
    OUTPUT="$PWD/$OUTPUT"
fi
[[ "$OUTPUT" == *.tar.gz ]] || die "output must end in .tar.gz"
[[ ! -e "$OUTPUT" ]] || die "output already exists: $OUTPUT"

for command_name in tar gzip cp find sort xargs awk grep git bash chmod; do
    command -v "$command_name" >/dev/null 2>&1 || die "$command_name is required"
done

SOURCE_COMMIT=$(git -C "$REPOSITORY_ROOT" rev-parse --verify HEAD 2>/dev/null) \
    || die "repository HEAD could not be resolved"
[[ "$SOURCE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "repository HEAD is not a full Git commit"
WORKTREE_STATUS=$(git -C "$REPOSITORY_ROOT" status --porcelain --untracked-files=all)
[[ -z "$WORKTREE_STATUS" ]] \
    || die "repository must be clean before creating exact release source"
if command -v sha256sum >/dev/null 2>&1; then
    HASH_COMMAND=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    HASH_COMMAND=(shasum -a 256)
else
    die "sha256sum or shasum is required"
fi

for required_file in LICENSE APACHE-2.0.txt NOTICE THIRD_PARTY_NOTICES.md README.md package.json \
    package-lock.json docs/RELEASE_LICENSING.md release/reports/release-runtime-dependencies.txt \
    release/reports/release-sbom.cdx.json; do
    [[ -f "$REPOSITORY_ROOT/$required_file" ]] \
        || die "required release file is missing: $required_file"
done

# This proves that the archive will contain the exact staged patch tree used by
# the Android native build, rather than a nearby upstream checkout.
"$SCRIPT_DIR/native-validate-structure.sh" --require-source

VERSION=$(awk -F'"' '/^[[:space:]]*"version"[[:space:]]*:/ { print $4; exit }' \
    "$REPOSITORY_ROOT/package.json")
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.-]+)?$ ]] \
    || die "package.json has an invalid or missing version"

BUNDLE_NAME="drawless-chess-$VERSION-source"
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-source-bundle.XXXXXX")
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

BUNDLE_ROOT="$TEMP_ROOT/$BUNDLE_NAME"
mkdir -p "$BUNDLE_ROOT"

# Export the exact committed project blobs. This is independent of working-tree
# line-ending conversion, global ignore rules, caches, and untracked files.
git -C "$REPOSITORY_ROOT" -c core.autocrlf=false \
    archive --format=tar "$SOURCE_COMMIT" \
    | tar -C "$BUNDLE_ROOT" -xf -

# The prepared Fairy tree is intentionally excluded from the outer repository.
# Its staged index was already proved to equal the locked patched tree, so export
# those canonical index blobs and the single reviewed source-state marker only.
NATIVE_SOURCE_RELATIVE=$(awk -F= '
    $1 == "sourceDirectory" { sub(/\r$/, "", $2); print $2; exit }
' "$REPOSITORY_ROOT/engine/native/upstream.properties")
[[ "$NATIVE_SOURCE_RELATIVE" == upstream/Fairy-Stockfish ]] \
    || die "unexpected native source directory: $NATIVE_SOURCE_RELATIVE"
NATIVE_SOURCE="$REPOSITORY_ROOT/engine/native/$NATIVE_SOURCE_RELATIVE"
ARCHIVE_FAIRY="$BUNDLE_ROOT/engine/native/$NATIVE_SOURCE_RELATIVE"
mkdir -p "$ARCHIVE_FAIRY"
EXPECTED_NATIVE_PATCHED_TREE=$(awk -F= '
    $1 == "patchedTree" { sub(/\r$/, "", $2); print $2; exit }
' "$REPOSITORY_ROOT/engine/native/upstream.properties")
ACTUAL_NATIVE_PATCHED_TREE=$(git -C "$NATIVE_SOURCE" write-tree)
[[ "$ACTUAL_NATIVE_PATCHED_TREE" == "$EXPECTED_NATIVE_PATCHED_TREE" ]] \
    || die "native staged tree differs from the locked patched tree"
git -C "$NATIVE_SOURCE" -c core.autocrlf=false \
    archive --format=tar "$ACTUAL_NATIVE_PATCHED_TREE" \
    | tar -C "$ARCHIVE_FAIRY" -xf -
cp "$NATIVE_SOURCE/.drawless-source-state.properties" \
    "$ARCHIVE_FAIRY/.drawless-source-state.properties"

# The prepared native source was already proved against its pinned Git revision,
# tree, staged patched tree, and ordered patch series before staging. Administrative
# Git data is neither Corresponding Source nor safe/deterministic release content.
# Replace it with a complete, sorted byte manifest of the actual patched source.
ARCHIVE_FAIRY_MANIFEST="$BUNDLE_ROOT/engine/native/archive-fairy-source.sha256"
[[ -d "$ARCHIVE_FAIRY" ]] || die "prepared Fairy-Stockfish source was not copied"
[[ ! -e "$ARCHIVE_FAIRY/.git" ]] || die "native administrative Git data reached staging"
UNSAFE_LINK=$(find "$BUNDLE_ROOT" -type l -print -quit)
[[ -z "$UNSAFE_LINK" ]] || die "symbolic link reached the staging tree: $UNSAFE_LINK"
while IFS= read -r -d '' native_file; do
    case "$native_file" in
        *$'\n'*|*$'\r'*|*\\*|*:*)
            die "native source contains a non-portable filename: $native_file" ;;
    esac
done < <(find "$ARCHIVE_FAIRY" -type f -print0)
(
    cd "$ARCHIVE_FAIRY"
    find . -type f -print0 | sort -z | xargs -0 "${HASH_COMMAND[@]}"
) > "$ARCHIVE_FAIRY_MANIFEST"
[[ -s "$ARCHIVE_FAIRY_MANIFEST" ]] || die "native archive source manifest is empty"
bash "$BUNDLE_ROOT/scripts/native-validate-structure.sh" --require-source

SENSITIVE_FILE=$(find "$BUNDLE_ROOT" -type f \
    \( -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' \
       -o -iname '*.pfx' -o -iname '*.pem' -o -iname '*.key' \
       -o -iname '*.der' -o -iname '*.mobileprovision' \
       -o -iname 'key.properties' -o -iname 'local.properties' \
       -o -iname 'signing.properties' \
       -o -iname 'google-services.json' -o -iname '.env' -o -iname '.env.*' \) \
    -print -quit)
[[ -z "$SENSITIVE_FILE" ]] \
    || die "sensitive or machine-local file reached the staging tree: $SENSITIVE_FILE"

SIGNING_EXAMPLE="$BUNDLE_ROOT/android/signing.properties.example"
[[ "$(grep -Fxc 'storePassword=replace-locally' "$SIGNING_EXAMPLE")" == 1 ]] \
    || die "signing properties example does not contain the exact store-password sentinel"
[[ "$(grep -Fxc 'keyPassword=replace-locally' "$SIGNING_EXAMPLE")" == 1 ]] \
    || die "signing properties example does not contain the exact key-password sentinel"
SENSITIVE_CONTENT=$(grep -RIlm1 --include='*.properties' \
    -E '^[[:space:]]*(storePassword|keyPassword)[[:space:]]*=' \
    "$BUNDLE_ROOT" || true)
while IFS= read -r sensitive_file; do
    [[ -z "$sensitive_file" || "$sensitive_file" == "$SIGNING_EXAMPLE" ]] \
        || die "signing-password content reached the staging tree: $sensitive_file"
done <<< "$SENSITIVE_CONTENT"
ENV_SECRET=$(grep -RIlm1 \
    -E '^[[:space:]]*DRAWLESS_UPLOAD_(STORE_PASSWORD|KEY_PASSWORD)[[:space:]]*=' \
    "$BUNDLE_ROOT" || true)
[[ -z "$ENV_SECRET" ]] || die "release signing environment secret reached staging: $ENV_SECRET"

GENERATED_BINARY=$(find "$BUNDLE_ROOT" -type f \
    \( -iname '*.apk' -o -iname '*.aab' -o -iname '*.aar' \
       -o -iname '*.so' -o -iname '*.dll' -o -iname '*.dylib' \
       -o -iname '*.o' -o -iname '*.obj' \) -print -quit)
[[ -z "$GENERATED_BINARY" ]] \
    || die "generated binary reached the staging tree: $GENERATED_BINARY"

cat > "$BUNDLE_ROOT/SOURCE-BUNDLE-README.md" <<EOF
# Drawless Chess $VERSION corresponding source

This archive contains the complete project source selected by the Drawless Chess
release bundler, including the exact prepared and patched Fairy-Stockfish Git
source tree used by the Android native build. See LICENSE, APACHE-2.0.txt, NOTICE,
THIRD_PARTY_NOTICES.md, release/reports/release-sbom.cdx.json, and
docs/RELEASE_LICENSING.md.

From this directory, validate the source and run the project gates with:

    scripts/native-validate-structure.sh --require-source
    npm ci
    npm run test:all

Build the Android artifacts from android/ with the checked-in Gradle wrapper and
the JDK/Android SDK/NDK versions documented in docs/ANDROID_MACHINE_VERIFICATION.md.
SDKs, the JDK, signing keys, build outputs, caches, and device logs are not part of
Corresponding Source and are intentionally absent.

The release distributor must publish this archive's SHA-256 next to the matching
binary identity and provide the actual public source URL. This archive does not by
itself authorize distribution.
EOF

printf '%s\n' "$SOURCE_COMMIT" > "$BUNDLE_ROOT/SOURCE-COMMIT"

(
    cd "$BUNDLE_ROOT"
    find . -type f ! -path './SOURCE-MANIFEST.sha256' -print0 \
        | sort -z \
        | xargs -0 "${HASH_COMMAND[@]}" \
        > SOURCE-MANIFEST.sha256
)
"${HASH_COMMAND[@]}" "$BUNDLE_ROOT/SOURCE-MANIFEST.sha256" \
    | awk '{ print $1 }' > "$BUNDLE_ROOT/SOURCE-MANIFEST.sha256.digest"

# Normalize archive modes so independent clean checkouts produce the same tarball.
find "$BUNDLE_ROOT" -type d -exec chmod 0755 {} +
find "$BUNDLE_ROOT" -type f -exec chmod 0644 {} +
find "$BUNDLE_ROOT" -type f -name '*.sh' -exec chmod 0755 {} +
chmod 0755 "$BUNDLE_ROOT/android/gradlew"

mkdir -p "$(dirname -- "$OUTPUT")"
tar --sort=name \
    --mtime='UTC 2026-07-01 09:31:25' \
    --owner=0 --group=0 --numeric-owner \
    -C "$TEMP_ROOT" -cf - "$BUNDLE_NAME" | gzip -n > "$OUTPUT"

tar -tzf "$OUTPUT" >/dev/null
"${HASH_COMMAND[@]}" "$OUTPUT"
