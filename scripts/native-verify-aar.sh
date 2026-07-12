#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
LOCK_FILE="$REPOSITORY_ROOT/engine/native/upstream.properties"

die() {
    printf 'native-verify-aar: %s\n' "$*" >&2
    exit 1
}

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

if (($# < 1 || $# > 2)); then
    printf 'Usage: scripts/native-verify-aar.sh ENGINE.aar [MANIFEST.json]\n' >&2
    exit 2
fi

AAR=$1
OUTPUT=${2:-}
[[ -f "$AAR" ]] || die "AAR does not exist: $AAR"
if [[ -n "$OUTPUT" ]]; then
    [[ "$OUTPUT" != /* ]] && OUTPUT="$PWD/$OUTPUT"
    [[ ! -e "$OUTPUT" ]] || die "manifest output already exists: $OUTPUT"
fi

for tool in unzip zipinfo readelf strings; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

# Verify that the source tree still contains the exact JNI bridge, binding, shrinker,
# and Android instrumentation contracts expected by the binary checks below.
"$SCRIPT_DIR/native-validate-structure.sh" >/dev/null

REVISION=$(property revision)
PATCH_VERSION=$(property drawlessPatchVersion)
BRIDGE_ABI_VERSION=$(property nativeBridgeAbiVersion)
MINIMUM_API=$(property androidMinSdk)
LIBRARY_BASENAME="lib$(property nativeLibraryName).so"
AAR_SHA256=$(hash_file "$AAR")

EXPECTED_PATHS=(
    "jni/arm64-v8a/$LIBRARY_BASENAME"
    "jni/x86_64/$LIBRARY_BASENAME"
)
ACTUAL_PATHS=()
while IFS= read -r archive_path; do
    case "$archive_path" in
        jni/*/"$LIBRARY_BASENAME")
            relative_path=${archive_path#jni/}
            abi=${relative_path%%/*}
            [[ "$archive_path" == "jni/$abi/$LIBRARY_BASENAME" ]] \
                && ACTUAL_PATHS+=("$archive_path")
            ;;
    esac
done < <(zipinfo -1 "$AAR")

EXPECTED_SORTED=$(printf '%s\n' "${EXPECTED_PATHS[@]}" | LC_ALL=C sort)
ACTUAL_SORTED=$(printf '%s\n' "${ACTUAL_PATHS[@]}" | LC_ALL=C sort)
[[ "$ACTUAL_SORTED" == "$EXPECTED_SORTED" ]] || die \
    "AAR ABI set mismatch; expected arm64-v8a and x86_64 only, found: ${ACTUAL_PATHS[*]:-(none)}"

for runtime_path in AndroidManifest.xml classes.jar proguard.txt; do
    zipinfo -1 "$AAR" | grep -Fx "$runtime_path" >/dev/null \
        || die "AAR is missing required runtime artifact: $runtime_path"
done

for legal_path in \
    assets/legal/drawless-chess/LICENSE \
    assets/legal/drawless-chess/NOTICE \
    assets/legal/drawless-chess/THIRD_PARTY_NOTICES.md \
    assets/third_party/android-runtime/APACHE-2.0.txt \
    assets/third_party/android-runtime/release-sbom.cdx.json \
    assets/release/SOURCE-COMMIT \
    assets/third_party/fairy-stockfish/Copying.txt \
    assets/third_party/fairy-stockfish/AUTHORS \
    assets/third_party/fairy-stockfish/SOURCE_NOTICE.txt \
    assets/third_party/fairy-stockfish/upstream.properties \
    assets/third_party/fairy-stockfish/patches/series \
    assets/engine/drawless-variants.ini; do
    zipinfo -1 "$AAR" | grep -Fx "$legal_path" >/dev/null \
        || die "AAR is missing required legal/source identity asset: $legal_path"
done

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-aar.XXXXXX")
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

unzip -p "$AAR" classes.jar > "$TEMP_ROOT/classes.jar"
[[ -s "$TEMP_ROOT/classes.jar" ]] || die "AAR classes.jar is empty"
for required_class in \
    com/drawlesschess/engine/BuildConfig.class \
    com/drawlesschess/engine/FairyNativeBindings.class \
    com/drawlesschess/engine/JniFairyEnginePort.class \
    com/drawlesschess/engine/AndroidFairyEngineFactory.class \
    com/drawlesschess/engine/AndroidFairyEngineSession.class \
    com/drawlesschess/engine/AndroidUciTimeoutScheduler.class \
    com/drawlesschess/engine/VariantConfigInstaller.class; do
    zipinfo -1 "$TEMP_ROOT/classes.jar" | grep -Fx "$required_class" >/dev/null \
        || die "AAR classes.jar is missing JNI runtime class: $required_class"
done

unzip -p "$AAR" proguard.txt > "$TEMP_ROOT/proguard.txt"
grep -Fq -- '-keep class com.drawlesschess.engine.FairyNativeBindings {' \
    "$TEMP_ROOT/proguard.txt" \
    || die "AAR consumer rules do not keep the registered JNI binding class"
grep -Fq -- 'public static native <methods>;' "$TEMP_ROOT/proguard.txt" \
    || die "AAR consumer rules do not keep the registered JNI methods"

unzip -p "$AAR" assets/engine/drawless-variants.ini > "$TEMP_ROOT/drawless-variants.ini"
[[ "$(hash_file "$TEMP_ROOT/drawless-variants.ini")" == "$(property variantConfigSha256)" ]] \
    || die "packaged Drawless variant configuration does not match the native lock"
unzip -p "$AAR" assets/third_party/fairy-stockfish/upstream.properties \
    > "$TEMP_ROOT/upstream.properties"
cmp -s "$LOCK_FILE" "$TEMP_ROOT/upstream.properties" \
    || die "packaged native source lock differs from the repository lock"

PATCH_ASSET_ROOT=assets/third_party/fairy-stockfish/patches
unzip -p "$AAR" "$PATCH_ASSET_ROOT/series" > "$TEMP_ROOT/series"
PATCH_HASH_INPUT="$TEMP_ROOT/patch-series.sha256-input"
: > "$PATCH_HASH_INPUT"
printf 'series\0' >> "$PATCH_HASH_INPUT"
command cat "$TEMP_ROOT/series" >> "$PATCH_HASH_INPUT"
while IFS= read -r patch_entry || [[ -n "$patch_entry" ]]; do
    patch_entry=${patch_entry%$'\r'}
    [[ -n "$patch_entry" && "$patch_entry" != \#* ]] || continue
    case "$patch_entry" in
        /*|*../*|../*|*'/..') die "unsafe patch path in packaged series: $patch_entry" ;;
    esac
    zipinfo -1 "$AAR" | grep -Fx "$PATCH_ASSET_ROOT/$patch_entry" >/dev/null \
        || die "packaged series references missing patch: $patch_entry"
    printf '\0patch\0%s\0' "$patch_entry" >> "$PATCH_HASH_INPUT"
    unzip -p "$AAR" "$PATCH_ASSET_ROOT/$patch_entry" >> "$PATCH_HASH_INPUT"
done < "$TEMP_ROOT/series"
[[ "$(hash_file "$PATCH_HASH_INPUT")" == "$(property patchSeriesSha256)" ]] \
    || die "packaged Drawless patch series differs from the native lock"

extract_and_measure() {
    local abi=$1
    local expected_machine=$2
    local archive_path="jni/$abi/$LIBRARY_BASENAME"
    local extracted="$TEMP_ROOT/$abi-$LIBRARY_BASENAME"
    unzip -p "$AAR" "$archive_path" > "$extracted"
    [[ -s "$extracted" ]] || die "$archive_path is empty"
    readelf -h "$extracted" | grep -E 'Class:[[:space:]]+ELF64' >/dev/null \
        || die "$archive_path is not ELF64"
    readelf -h "$extracted" | grep -F "$expected_machine" >/dev/null \
        || die "$archive_path has the wrong ELF machine"
    local symbol_table="$TEMP_ROOT/$abi-dynamic-symbols.txt"
    readelf --wide --dyn-syms "$extracted" > "$symbol_table"
    for required_symbol in \
        JNI_OnLoad \
        drawless_fairy_upstream_revision \
        drawless_fairy_upstream_tree \
        drawless_fairy_patched_tree \
        drawless_fairy_patch_series_sha256 \
        drawless_fairy_patch_version \
        drawless_fairy_bridge_abi_version \
        drawless_fairy_android_abi; do
        grep -F "$required_symbol" "$symbol_table" >/dev/null \
            || die "$archive_path is missing required native export $required_symbol"
    done
    [[ "$(awk '$8 == "JNI_OnLoad" && $5 == "GLOBAL" && $7 != "UND" { count++ } END { print count + 0 }' "$symbol_table")" == 1 ]] \
        || die "$archive_path must export exactly one defined global JNI_OnLoad"
    if awk '$8 ~ /^Java_/ { print; found=1 } END { exit !found }' "$symbol_table" >/dev/null; then
        die "$archive_path exports Java_* symbols instead of using the locked RegisterNatives ABI"
    fi
    for required_identity in \
        "$REVISION" \
        "$(property tree)" \
        "$(property patchedTree)" \
        "$(property patchSeriesSha256)" \
        'Drawless Patch Version'; do
        strings "$extracted" | grep -Fx "$required_identity" >/dev/null \
            || die "$archive_path is missing compiled identity: $required_identity"
    done
    for required_binding in \
        'com/drawlesschess/engine/FairyNativeBindings' \
        nativeCreate \
        nativeStart \
        nativeWrite \
        nativeRead \
        nativeReadError \
        nativeClose \
        '(Ljava/lang/String;)J' \
        '(J[BII)I' \
        '(J)V'; do
        strings "$extracted" | grep -Fx "$required_binding" >/dev/null \
            || die "$archive_path is missing registered JNI binding evidence: $required_binding"
    done
    printf '%s %s\n' "$(wc -c < "$extracted" | tr -d ' ')" "$(hash_file "$extracted")"
}

read -r ARM64_SIZE ARM64_SHA < <(extract_and_measure arm64-v8a 'AArch64')
read -r X86_64_SIZE X86_64_SHA < <(extract_and_measure x86_64 'Advanced Micro Devices X86-64')

MANIFEST_TEMP="$TEMP_ROOT/native-engine-manifest.json"
{
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "engineId": "fairy-stockfish@%s",\n' "$REVISION"
    printf '  "buildId": "aar-sha256:%s",\n' "$AAR_SHA256"
    printf '  "drawlessPatchVersion": %s,\n' "$PATCH_VERSION"
    printf '  "nativeBridgeAbiVersion": %s,\n' "$BRIDGE_ABI_VERSION"
    printf '  "minimumAndroidApi": %s,\n' "$MINIMUM_API"
    printf '  "artifacts": [\n'
    printf '    {"abi": "arm64-v8a", "libraryFileName": "%s", "uncompressedSizeBytes": %s, "sha256": "%s"},\n' \
        "$LIBRARY_BASENAME" "$ARM64_SIZE" "$ARM64_SHA"
    printf '    {"abi": "x86_64", "libraryFileName": "%s", "uncompressedSizeBytes": %s, "sha256": "%s"}\n' \
        "$LIBRARY_BASENAME" "$X86_64_SIZE" "$X86_64_SHA"
    printf '  ]\n'
    printf '}\n'
} > "$MANIFEST_TEMP"

if [[ -n "$OUTPUT" ]]; then
    mkdir -p "$(dirname -- "$OUTPUT")"
    cp "$MANIFEST_TEMP" "$OUTPUT"
    printf 'Native AAR PASS; manifest written to %s\n' "$OUTPUT" >&2
else
    command cat "$MANIFEST_TEMP"
fi
