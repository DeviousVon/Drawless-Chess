#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
LOCK_FILE="$REPOSITORY_ROOT/engine/native/upstream.properties"

die() {
    printf 'native-verify-apk: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        'Usage: scripts/native-verify-apk.sh APP_DEBUG.apk APP_RELEASE.apk ENGINE_TEST.apk ENGINE_DEBUG.aar ENGINE_RELEASE.aar TEST_ABI [MANIFEST.json]' \
        >&2
    exit 2
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

file_size() {
    wc -c < "$1" | tr -d '[:space:]'
}

json_quote() {
    local value=$1
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '"%s"' "$value"
}

find_ndk_tool() {
    local tool_name=$1
    local ndk_version
    local ndk_root
    local prebuilt_bin
    ndk_version=$(property androidNdkVersion)

    for ndk_root in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_SDK_ROOT:-}/ndk/$ndk_version" \
        "${ANDROID_HOME:-}/ndk/$ndk_version" \
        "${HOME:-}/Library/Android/sdk/ndk/$ndk_version" \
        "${HOME:-}/Android/Sdk/ndk/$ndk_version" \
        "/opt/android-sdk/ndk/$ndk_version"; do
        [[ -n "$ndk_root" && -d "$ndk_root" ]] || continue
        for prebuilt_bin in "$ndk_root"/toolchains/llvm/prebuilt/*/bin; do
            [[ -d "$prebuilt_bin" ]] || continue
            if [[ -x "$prebuilt_bin/$tool_name" ]]; then
                printf '%s\n' "$prebuilt_bin/$tool_name"
                return 0
            fi
        done
    done
    return 1
}

select_readelf() {
    local candidate
    for candidate in readelf llvm-readelf; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    find_ndk_tool llvm-readelf
}

select_strings() {
    local candidate
    for candidate in strings llvm-strings; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
    done
    find_ndk_tool llvm-strings
}

archive_list() {
    local archive=$1
    if command -v zipinfo >/dev/null 2>&1; then
        zipinfo -1 "$archive"
    else
        unzip -Z1 "$archive"
    fi
}

require_unique_path() {
    local list_file=$1
    local archive_label=$2
    local archive_path=$3
    local count
    count=$(grep -Fxc "$archive_path" "$list_file" || true)
    [[ "$count" == 1 ]] \
        || die "$archive_label must contain exactly one $archive_path (found $count)"
}

extract_archive_path() {
    local archive=$1
    local archive_path=$2
    local destination=$3
    unzip -p "$archive" "$archive_path" > "$destination"
    [[ -s "$destination" ]] \
        || die "archive member is empty or unreadable: $archive_path in $archive"
}

archive_abis() {
    local list_file=$1
    local prefix=$2
    awk -F/ -v prefix="$prefix" '
        $1 == prefix && NF >= 3 && $2 != "" { print $2 }
    ' "$list_file" | LC_ALL=C sort -u
}

require_exact_abis() {
    local list_file=$1
    local prefix=$2
    local archive_label=$3
    shift 3
    local expected
    local actual
    expected=$(printf '%s\n' "$@" | LC_ALL=C sort -u)
    actual=$(archive_abis "$list_file" "$prefix")
    [[ "$actual" == "$expected" ]] \
        || die "$archive_label ABI set mismatch; expected $(printf '%s ' "$@"), found ${actual:-none}"
}

require_only_native_libraries() {
    local list_file=$1
    local prefix=$2
    local archive_label=$3
    shift 3
    local abi
    local library_name
    local library_names=()
    while (($# > 0)) && [[ "$1" != -- ]]; do
        library_names+=("$1")
        shift
    done
    (($# > 0)) || die "internal native-library allowlist is missing its ABI separator"
    shift
    ((${#library_names[@]} > 0)) || die "internal native-library allowlist is empty"
    (($# > 0)) || die "internal native-library ABI list is empty"
    local expected
    local actual
    expected=$(
        for abi in "$@"; do
            for library_name in "${library_names[@]}"; do
                printf '%s/%s/%s\n' "$prefix" "$abi" "$library_name"
            done
        done | LC_ALL=C sort
    )
    actual=$(
        awk -F/ -v prefix="$prefix" '
            $1 == prefix && $NF ~ /\.so$/ { print }
        ' "$list_file" | LC_ALL=C sort
    )
    [[ "$actual" == "$expected" ]] \
        || die "$archive_label contains an unexpected native library; expected only: ${expected//$'\n'/, }"
}

json_abi_array() {
    local separator=
    local abi
    printf '['
    for abi in "$@"; do
        printf '%s"%s"' "$separator" "$abi"
        separator=,
    done
    printf ']'
}

verify_elf_header() {
    local library=$1
    local abi=$2
    local label=$3
    local header=$TEMP_ROOT/elf-header.txt
    "$READELF_TOOL" -h "$library" > "$header"
    grep -E 'Class:[[:space:]]+ELF64' "$header" >/dev/null \
        || die "$label is not ELF64"
    case "$abi" in
        arm64-v8a)
            grep -Ei 'Machine:.*(AArch64|ARM.*64)' "$header" >/dev/null \
                || die "$label is not an AArch64 ELF"
            ;;
        x86_64)
            grep -Ei 'Machine:.*(X86-64|x86_64|AMD.*64|Advanced Micro Devices)' "$header" >/dev/null \
                || die "$label is not an x86-64 ELF"
            ;;
        *) die "internal unsupported ABI: $abi" ;;
    esac
}

verify_compiled_identity() {
    local library=$1
    local label=$2
    local identity
    local strings_file=$TEMP_ROOT/library-strings.txt
    "$STRINGS_TOOL" "$library" > "$strings_file"
    for identity in \
        "$(property revision)" \
        "$(property tree)" \
        "$(property patchedTree)" \
        "$(property patchSeriesSha256)" \
        'Drawless Patch Version' \
        'com/drawlesschess/engine/FairyNativeBindings'; do
        grep -Fx "$identity" "$strings_file" >/dev/null \
            || die "$label is missing compiled identity: $identity"
    done
}

compare_library() {
    local apk=$1
    local apk_path=$2
    local aar=$3
    local aar_path=$4
    local abi=$5
    local label=$6
    local apk_library=$TEMP_ROOT/apk-library.so
    local aar_library=$TEMP_ROOT/aar-library.so
    extract_archive_path "$apk" "$apk_path" "$apk_library"
    extract_archive_path "$aar" "$aar_path" "$aar_library"
    cmp -s "$apk_library" "$aar_library" \
        || die "$label JNI library is not byte-identical to its verified AAR"
    verify_elf_header "$apk_library" "$abi" "$label"
    verify_compiled_identity "$apk_library" "$label"
}

compare_asset() {
    local archive=$1
    local archive_path=$2
    local baseline_archive=$3
    local baseline_path=$4
    local label=$5
    local actual=$TEMP_ROOT/asset-actual
    local baseline=$TEMP_ROOT/asset-baseline
    extract_archive_path "$archive" "$archive_path" "$actual"
    extract_archive_path "$baseline_archive" "$baseline_path" "$baseline"
    cmp -s "$actual" "$baseline" \
        || die "$label differs from its verified AAR: $archive_path"
}

if (($# < 6 || $# > 7)); then
    usage
fi

APP_DEBUG=$1
APP_RELEASE=$2
ENGINE_TEST=$3
ENGINE_DEBUG_AAR=$4
ENGINE_RELEASE_AAR=$5
TEST_ABI=$6
OUTPUT=${7:-}

case "$TEST_ABI" in
    arm64-v8a|x86_64) ;;
    *) die "TEST_ABI must be arm64-v8a or x86_64 (received: $TEST_ABI)" ;;
esac

INPUT_PATHS=(
    "$APP_DEBUG"
    "$APP_RELEASE"
    "$ENGINE_TEST"
    "$ENGINE_DEBUG_AAR"
    "$ENGINE_RELEASE_AAR"
)
INPUT_LABELS=(
    "app debug APK"
    "app release APK"
    "engine test APK"
    "engine debug AAR"
    "engine release AAR"
)

for index in 0 1 2 3 4; do
    [[ -f "${INPUT_PATHS[$index]}" ]] \
        || die "${INPUT_LABELS[$index]} does not exist: ${INPUT_PATHS[$index]}"
    [[ -s "${INPUT_PATHS[$index]}" ]] \
        || die "${INPUT_LABELS[$index]} is empty: ${INPUT_PATHS[$index]}"
done

for left in 0 1 2 3 4; do
    for right in 0 1 2 3 4; do
        ((right > left)) || continue
        [[ "${INPUT_PATHS[$left]}" != "${INPUT_PATHS[$right]}" ]] \
            || die "artifact inputs must be distinct: ${INPUT_PATHS[$left]}"
    done
done

if [[ -n "$OUTPUT" ]]; then
    [[ "$OUTPUT" != /* ]] && OUTPUT="$PWD/$OUTPUT"
    [[ ! -e "$OUTPUT" ]] || die "manifest output already exists: $OUTPUT"
fi

for tool in awk cmp grep ln mktemp sed sort tr unzip wc; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
if ! command -v zipinfo >/dev/null 2>&1; then
    unzip -Z1 "$APP_DEBUG" >/dev/null 2>&1 \
        || die "zipinfo or an unzip implementation supporting -Z1 is required"
fi

READELF_TOOL=$(select_readelf) \
    || die "readelf/llvm-readelf is required; install it or the pinned Android NDK $(property androidNdkVersion)"
STRINGS_TOOL=$(select_strings) \
    || die "strings/llvm-strings is required; install it or the pinned Android NDK $(property androidNdkVersion)"

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-apk.XXXXXX")
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

for index in 0 1 2 3 4; do
    unzip -tqq "${INPUT_PATHS[$index]}" >/dev/null \
        || die "${INPUT_LABELS[$index]} is not an intact ZIP archive: ${INPUT_PATHS[$index]}"
done

APP_DEBUG_LIST=$TEMP_ROOT/app-debug.list
APP_RELEASE_LIST=$TEMP_ROOT/app-release.list
ENGINE_TEST_LIST=$TEMP_ROOT/engine-test.list
ENGINE_DEBUG_AAR_LIST=$TEMP_ROOT/engine-debug-aar.list
ENGINE_RELEASE_AAR_LIST=$TEMP_ROOT/engine-release-aar.list
archive_list "$APP_DEBUG" > "$APP_DEBUG_LIST"
archive_list "$APP_RELEASE" > "$APP_RELEASE_LIST"
archive_list "$ENGINE_TEST" > "$ENGINE_TEST_LIST"
archive_list "$ENGINE_DEBUG_AAR" > "$ENGINE_DEBUG_AAR_LIST"
archive_list "$ENGINE_RELEASE_AAR" > "$ENGINE_RELEASE_AAR_LIST"

# The upstream verifier is the authority for JNI exports, pinned identity, legal/source
# material, and exact AAR ABI packaging. Tool shims let it run on macOS when GNU readelf
# is absent but the pinned NDK's LLVM tools are installed.
TOOL_SHIMS=$TEMP_ROOT/tool-shims
mkdir -p "$TOOL_SHIMS"
ln -s "$READELF_TOOL" "$TOOL_SHIMS/readelf"
ln -s "$STRINGS_TOOL" "$TOOL_SHIMS/strings"
PATH="$TOOL_SHIMS:$PATH" bash "$SCRIPT_DIR/native-verify-aar.sh" \
    "$ENGINE_DEBUG_AAR" "$TEMP_ROOT/engine-debug-aar-manifest.json" >/dev/null
PATH="$TOOL_SHIMS:$PATH" bash "$SCRIPT_DIR/native-verify-aar.sh" \
    "$ENGINE_RELEASE_AAR" "$TEMP_ROOT/engine-release-aar-manifest.json" >/dev/null

LIBRARY_BASENAME="lib$(property nativeLibraryName).so"
SUPPORTED_ABIS=(arm64-v8a x86_64)
require_exact_abis "$APP_DEBUG_LIST" lib "app debug APK" "${SUPPORTED_ABIS[@]}"
require_exact_abis "$APP_RELEASE_LIST" lib "app release APK" "${SUPPORTED_ABIS[@]}"
require_exact_abis "$ENGINE_DEBUG_AAR_LIST" jni "engine debug AAR" "${SUPPORTED_ABIS[@]}"
require_exact_abis "$ENGINE_RELEASE_AAR_LIST" jni "engine release AAR" "${SUPPORTED_ABIS[@]}"

TEST_APK_ABIS=()
TEST_ABI_PRESENT=false
while IFS= read -r packaged_abi; do
    [[ -n "$packaged_abi" ]] || continue
    case "$packaged_abi" in
        arm64-v8a|x86_64) ;;
        *) die "engine test APK contains unsupported ABI: $packaged_abi" ;;
    esac
    TEST_APK_ABIS+=("$packaged_abi")
    [[ "$packaged_abi" == "$TEST_ABI" ]] && TEST_ABI_PRESENT=true
done < <(archive_abis "$ENGINE_TEST_LIST" lib)
((${#TEST_APK_ABIS[@]} > 0)) || die "engine test APK contains no native ABI"
[[ "$TEST_ABI_PRESENT" == true ]] \
    || die "engine test APK does not contain selected TEST_ABI $TEST_ABI"

require_only_native_libraries "$APP_DEBUG_LIST" lib "app debug APK" \
    "$LIBRARY_BASENAME" libandroidx.graphics.path.so -- "${SUPPORTED_ABIS[@]}"
require_only_native_libraries "$APP_RELEASE_LIST" lib "app release APK" \
    "$LIBRARY_BASENAME" libandroidx.graphics.path.so -- "${SUPPORTED_ABIS[@]}"
require_only_native_libraries "$ENGINE_DEBUG_AAR_LIST" jni "engine debug AAR" \
    "$LIBRARY_BASENAME" -- "${SUPPORTED_ABIS[@]}"
require_only_native_libraries "$ENGINE_RELEASE_AAR_LIST" jni "engine release AAR" \
    "$LIBRARY_BASENAME" -- "${SUPPORTED_ABIS[@]}"
require_only_native_libraries "$ENGINE_TEST_LIST" lib "engine test APK" \
    "$LIBRARY_BASENAME" -- "${TEST_APK_ABIS[@]}"

for abi in "${SUPPORTED_ABIS[@]}"; do
    app_path="lib/$abi/$LIBRARY_BASENAME"
    aar_path="jni/$abi/$LIBRARY_BASENAME"
    require_unique_path "$APP_DEBUG_LIST" "app debug APK" "$app_path"
    require_unique_path "$APP_RELEASE_LIST" "app release APK" "$app_path"
    require_unique_path "$ENGINE_DEBUG_AAR_LIST" "engine debug AAR" "$aar_path"
    require_unique_path "$ENGINE_RELEASE_AAR_LIST" "engine release AAR" "$aar_path"
    compare_library "$APP_DEBUG" "$app_path" "$ENGINE_DEBUG_AAR" "$aar_path" \
        "$abi" "app debug $abi"
    compare_library "$APP_RELEASE" "$app_path" "$ENGINE_RELEASE_AAR" "$aar_path" \
        "$abi" "app release $abi"
done

TEST_APK_LIBRARY_PATH="lib/$TEST_ABI/$LIBRARY_BASENAME"
TEST_AAR_LIBRARY_PATH="jni/$TEST_ABI/$LIBRARY_BASENAME"
require_unique_path "$ENGINE_TEST_LIST" "engine test APK" "$TEST_APK_LIBRARY_PATH"
compare_library "$ENGINE_TEST" "$TEST_APK_LIBRARY_PATH" \
    "$ENGINE_DEBUG_AAR" "$TEST_AAR_LIBRARY_PATH" "$TEST_ABI" "engine test $TEST_ABI"

ASSET_PATHS=(
    assets/engine/drawless-variants.ini
    assets/legal/drawless-chess/LICENSE
    assets/legal/drawless-chess/NOTICE
    assets/legal/drawless-chess/THIRD_PARTY_NOTICES.md
    assets/third_party/fairy-stockfish/Copying.txt
    assets/third_party/fairy-stockfish/AUTHORS
    assets/third_party/fairy-stockfish/SOURCE_NOTICE.txt
    assets/third_party/fairy-stockfish/upstream.properties
    assets/third_party/fairy-stockfish/wasm-poc.properties
    assets/third_party/fairy-stockfish/patches/series
    assets/third_party/fairy-stockfish/patches/manifest.json
    assets/third_party/fairy-stockfish/patches/checksums.sha256
    assets/third_party/fairy-stockfish/patches/README.md
)

SERIES_PATH=assets/third_party/fairy-stockfish/patches/series
SERIES_FILE=$TEMP_ROOT/series
require_unique_path "$ENGINE_DEBUG_AAR_LIST" "engine debug AAR" "$SERIES_PATH"
extract_archive_path "$ENGINE_DEBUG_AAR" "$SERIES_PATH" "$SERIES_FILE"
while IFS= read -r patch_entry || [[ -n "$patch_entry" ]]; do
    patch_entry=${patch_entry%$'\r'}
    [[ -n "$patch_entry" && "$patch_entry" != \#* ]] || continue
    case "$patch_entry" in
        /*|*../*|../*|*'/..') die "unsafe patch path in packaged series: $patch_entry" ;;
    esac
    ASSET_PATHS+=("assets/third_party/fairy-stockfish/patches/$patch_entry")
done < "$SERIES_FILE"

for asset_path in "${ASSET_PATHS[@]}"; do
    require_unique_path "$ENGINE_DEBUG_AAR_LIST" "engine debug AAR" "$asset_path"
    require_unique_path "$ENGINE_RELEASE_AAR_LIST" "engine release AAR" "$asset_path"
    require_unique_path "$APP_DEBUG_LIST" "app debug APK" "$asset_path"
    require_unique_path "$APP_RELEASE_LIST" "app release APK" "$asset_path"
    require_unique_path "$ENGINE_TEST_LIST" "engine test APK" "$asset_path"

    compare_asset "$ENGINE_RELEASE_AAR" "$asset_path" \
        "$ENGINE_DEBUG_AAR" "$asset_path" "engine release AAR asset"
    compare_asset "$APP_DEBUG" "$asset_path" \
        "$ENGINE_DEBUG_AAR" "$asset_path" "app debug APK asset"
    compare_asset "$APP_RELEASE" "$asset_path" \
        "$ENGINE_RELEASE_AAR" "$asset_path" "app release APK asset"
    compare_asset "$ENGINE_TEST" "$asset_path" \
        "$ENGINE_DEBUG_AAR" "$asset_path" "engine test APK asset"
done

VARIANT_FILE=$TEMP_ROOT/drawless-variants.ini
extract_archive_path "$APP_DEBUG" assets/engine/drawless-variants.ini "$VARIANT_FILE"
VARIANT_SHA256=$(hash_file "$VARIANT_FILE")
[[ "$VARIANT_SHA256" == "$(property variantConfigSha256)" ]] \
    || die "packaged Drawless variant configuration does not match the native lock"

DEBUG_ARM64=$TEMP_ROOT/debug-arm64.so
DEBUG_X86_64=$TEMP_ROOT/debug-x86_64.so
RELEASE_ARM64=$TEMP_ROOT/release-arm64.so
RELEASE_X86_64=$TEMP_ROOT/release-x86_64.so
extract_archive_path "$ENGINE_DEBUG_AAR" "jni/arm64-v8a/$LIBRARY_BASENAME" "$DEBUG_ARM64"
extract_archive_path "$ENGINE_DEBUG_AAR" "jni/x86_64/$LIBRARY_BASENAME" "$DEBUG_X86_64"
extract_archive_path "$ENGINE_RELEASE_AAR" "jni/arm64-v8a/$LIBRARY_BASENAME" "$RELEASE_ARM64"
extract_archive_path "$ENGINE_RELEASE_AAR" "jni/x86_64/$LIBRARY_BASENAME" "$RELEASE_X86_64"

MANIFEST_TEMP=$TEMP_ROOT/native-apk-manifest.json
{
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "engineId": "fairy-stockfish@%s",\n' "$(property revision)"
    printf '  "drawlessPatchVersion": %s,\n' "$(property drawlessPatchVersion)"
    printf '  "nativeBridgeAbiVersion": %s,\n' "$(property nativeBridgeAbiVersion)"
    printf '  "variantConfigSha256": "%s",\n' "$VARIANT_SHA256"
    printf '  "testAbi": "%s",\n' "$TEST_ABI"
    printf '  "artifacts": [\n'
    printf '    {"kind":"app-debug-apk","file":%s,"sizeBytes":%s,"sha256":"%s","abis":["arm64-v8a","x86_64"]},\n' \
        "$(json_quote "$APP_DEBUG")" "$(file_size "$APP_DEBUG")" "$(hash_file "$APP_DEBUG")"
    printf '    {"kind":"app-release-apk","file":%s,"sizeBytes":%s,"sha256":"%s","abis":["arm64-v8a","x86_64"]},\n' \
        "$(json_quote "$APP_RELEASE")" "$(file_size "$APP_RELEASE")" "$(hash_file "$APP_RELEASE")"
    printf '    {"kind":"engine-test-apk","file":%s,"sizeBytes":%s,"sha256":"%s","abis":%s},\n' \
        "$(json_quote "$ENGINE_TEST")" "$(file_size "$ENGINE_TEST")" "$(hash_file "$ENGINE_TEST")" \
        "$(json_abi_array "${TEST_APK_ABIS[@]}")"
    printf '    {"kind":"engine-debug-aar","file":%s,"sizeBytes":%s,"sha256":"%s","abis":["arm64-v8a","x86_64"]},\n' \
        "$(json_quote "$ENGINE_DEBUG_AAR")" "$(file_size "$ENGINE_DEBUG_AAR")" "$(hash_file "$ENGINE_DEBUG_AAR")"
    printf '    {"kind":"engine-release-aar","file":%s,"sizeBytes":%s,"sha256":"%s","abis":["arm64-v8a","x86_64"]}\n' \
        "$(json_quote "$ENGINE_RELEASE_AAR")" "$(file_size "$ENGINE_RELEASE_AAR")" "$(hash_file "$ENGINE_RELEASE_AAR")"
    printf '  ],\n'
    printf '  "nativeLibraries": [\n'
    printf '    {"variant":"debug","abi":"arm64-v8a","fileName":"%s","sizeBytes":%s,"sha256":"%s"},\n' \
        "$LIBRARY_BASENAME" "$(file_size "$DEBUG_ARM64")" "$(hash_file "$DEBUG_ARM64")"
    printf '    {"variant":"debug","abi":"x86_64","fileName":"%s","sizeBytes":%s,"sha256":"%s"},\n' \
        "$LIBRARY_BASENAME" "$(file_size "$DEBUG_X86_64")" "$(hash_file "$DEBUG_X86_64")"
    printf '    {"variant":"release","abi":"arm64-v8a","fileName":"%s","sizeBytes":%s,"sha256":"%s"},\n' \
        "$LIBRARY_BASENAME" "$(file_size "$RELEASE_ARM64")" "$(hash_file "$RELEASE_ARM64")"
    printf '    {"variant":"release","abi":"x86_64","fileName":"%s","sizeBytes":%s,"sha256":"%s"}\n' \
        "$LIBRARY_BASENAME" "$(file_size "$RELEASE_X86_64")" "$(hash_file "$RELEASE_X86_64")"
    printf '  ]\n'
    printf '}\n'
} > "$MANIFEST_TEMP"

if [[ -n "$OUTPUT" ]]; then
    mkdir -p "$(dirname -- "$OUTPUT")"
    cp "$MANIFEST_TEMP" "$OUTPUT"
    printf 'Native APK PASS; manifest written to %s\n' "$OUTPUT" >&2
else
    command cat "$MANIFEST_TEMP"
fi
