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

for command_name in tar gzip cp find sort xargs awk; do
    command -v "$command_name" >/dev/null 2>&1 || die "$command_name is required"
done
if command -v sha256sum >/dev/null 2>&1; then
    HASH_COMMAND=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    HASH_COMMAND=(shasum -a 256)
else
    die "sha256sum or shasum is required"
fi

for required_file in LICENSE NOTICE THIRD_PARTY_NOTICES.md README.md package.json \
    package-lock.json docs/RELEASE_LICENSING.md; do
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

# Deliberate allowlist: root VCS metadata, caches, logs, package stores, and
# machine-local directories never enter the staging tree.
ROOT_FILES=(
    .gitattributes
    .gitignore
    LICENSE
    NOTICE
    THIRD_PARTY_NOTICES.md
    README.md
    package.json
    package-lock.json
)
SOURCE_DIRECTORIES=(android contracts docs engine scripts src)

for source_file in "${ROOT_FILES[@]}"; do
    cp "$REPOSITORY_ROOT/$source_file" "$BUNDLE_ROOT/$source_file"
done

# Exclude generated Android state while staging rather than copying it and then
# deleting it. Besides avoiding unnecessary multi-gigabyte I/O, this keeps the
# corresponding-source operation stable if an idle Gradle daemon still owns a
# cache lock or a prior native build left a large .cxx tree behind.
tar -C "$REPOSITORY_ROOT" \
    --exclude='*/build' \
    --exclude='*/.gradle' \
    --exclude='*/.kotlin' \
    --exclude='*/.cxx' \
    --exclude='*/.idea' \
    --exclude='*/.vscode' \
    --exclude='*/captures' \
    --exclude='android/local.properties' \
    --exclude='*/__pycache__' \
    --exclude='src/docs' \
    --exclude='src/engine' \
    --exclude='src/scripts' \
    --exclude='*.class' \
    --exclude='*.pyc' \
    --exclude='*.iml' \
    --exclude='.DS_Store' \
    --exclude='Thumbs.db' \
    --exclude='*.hprof' \
    --exclude='*.trace' \
    -cf - "${SOURCE_DIRECTORIES[@]}" \
    | tar -C "$BUNDLE_ROOT" -xf -

# Defense in depth: reject/remove named generated locations if a future tar or
# platform implementation handles an exclusion differently.
rm -f "$BUNDLE_ROOT/android/local.properties"
find "$BUNDLE_ROOT/android" -depth -type d \
    \( -name build -o -name .gradle -o -name .kotlin -o -name .cxx \
       -o -name .idea -o -name .vscode -o -name captures \) \
    -exec rm -rf -- {} +
find "$BUNDLE_ROOT" -depth -type d -name __pycache__ -exec rm -rf -- {} +
find "$BUNDLE_ROOT" -type f \
    \( -name '*.class' -o -name '*.pyc' -o -name '*.iml' \
       -o -name '.DS_Store' -o -name 'Thumbs.db' \
       -o -name '*.hprof' -o -name '*.trace' \) -delete

# The native checkout's small Git database is intentionally retained: Gradle and
# the native verifier check HEAD, the upstream tree, and the staged patched tree.
# Reflogs and sample hooks are not required to rebuild and can carry local identity.
FAIRY_GIT="$BUNDLE_ROOT/engine/native/upstream/Fairy-Stockfish/.git"
[[ -d "$FAIRY_GIT" ]] || die "prepared Fairy-Stockfish Git metadata was not copied"
rm -rf "$FAIRY_GIT/logs" "$FAIRY_GIT/hooks"
rm -f "$FAIRY_GIT/FETCH_HEAD" "$FAIRY_GIT/ORIG_HEAD" "$FAIRY_GIT/COMMIT_EDITMSG"
if grep -Eiq '(credential|extraheader|user\.(name|email)|insteadof)' "$FAIRY_GIT/config"; then
    die "native Git config contains local identity or credential configuration"
fi

SENSITIVE_FILE=$(find "$BUNDLE_ROOT" -type f \
    \( -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' \
       -o -iname '*.pfx' -o -iname '*.pem' -o -iname '*.key' \
       -o -iname '*.der' -o -iname '*.mobileprovision' \
       -o -iname 'key.properties' -o -iname 'local.properties' \
       -o -iname 'google-services.json' -o -iname '.env' -o -iname '.env.*' \) \
    -print -quit)
[[ -z "$SENSITIVE_FILE" ]] \
    || die "sensitive or machine-local file reached the staging tree: $SENSITIVE_FILE"

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
checkout used by the Android native build. See LICENSE, NOTICE,
THIRD_PARTY_NOTICES.md, and docs/RELEASE_LICENSING.md.

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

(
    cd "$BUNDLE_ROOT"
    find . -type f ! -path './SOURCE-MANIFEST.sha256' -print0 \
        | sort -z \
        | xargs -0 "${HASH_COMMAND[@]}" \
        > SOURCE-MANIFEST.sha256
)

mkdir -p "$(dirname -- "$OUTPUT")"
tar --sort=name \
    --mtime='UTC 2026-07-01 09:31:25' \
    --owner=0 --group=0 --numeric-owner \
    -C "$TEMP_ROOT" -cf - "$BUNDLE_NAME" | gzip -n > "$OUTPUT"

tar -tzf "$OUTPUT" >/dev/null
"${HASH_COMMAND[@]}" "$OUTPUT"
