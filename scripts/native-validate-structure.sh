#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
NATIVE_ROOT="$REPOSITORY_ROOT/engine/native"
ANDROID_ENGINE="$REPOSITORY_ROOT/android/engine"
LOCK_FILE="$NATIVE_ROOT/upstream.properties"
WASM_LOCK="$NATIVE_ROOT/wasm-poc.properties"
SOURCE_MANIFEST="$NATIVE_ROOT/source-manifest.txt"
ARCHIVE_SOURCE_MANIFEST="$NATIVE_ROOT/archive-fairy-source.sha256"
NATIVE_BRIDGE="$ANDROID_ENGINE/src/main/cpp/native_bridge.cpp"
NATIVE_EXPORTS="$ANDROID_ENGINE/src/main/cpp/native_exports.map"
KOTLIN_BINDINGS="$ANDROID_ENGINE/src/main/kotlin/com/drawlesschess/engine/FairyNativeBindings.kt"
JNI_PORT="$ANDROID_ENGINE/src/main/kotlin/com/drawlesschess/engine/JniFairyEnginePort.kt"
ENGINE_FACTORY="$ANDROID_ENGINE/src/main/kotlin/com/drawlesschess/engine/AndroidFairyEngineFactory.kt"
INSTRUMENTED_TEST="$ANDROID_ENGINE/src/androidTest/kotlin/com/drawlesschess/engine/AndroidFairyEngineInstrumentedTest.kt"
CONSUMER_RULES="$ANDROID_ENGINE/consumer-rules.pro"
HOST_BINDINGS="$NATIVE_ROOT/host-test/com/drawlesschess/engine/FairyNativeBindings.java"
REQUIRE_SOURCE=false

if (($# > 1)); then
    printf 'Usage: scripts/native-validate-structure.sh [--require-source]\n' >&2
    exit 2
fi
if (($# == 1)); then
    [[ "$1" == --require-source ]] || {
        printf 'Unknown argument: %s\n' "$1" >&2
        exit 2
    }
    REQUIRE_SOURCE=true
fi

die() {
    printf 'native-validate-structure: %s\n' "$*" >&2
    exit 1
}

property_from() {
    local file=$1
    local key=$2
    awk -F= -v key="$key" '
        $1 == key {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
            print
            exit
        }
    ' "$file"
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

require_text() {
    local file=$1
    local text=$2
    grep -Fq -- "$text" "$file" || die "$file does not contain required text: $text"
}

reject_text() {
    local file=$1
    local text=$2
    if grep -Fq -- "$text" "$file"; then
        die "$file contains rejected text: $text"
    fi
}

for required_file in \
    "$LOCK_FILE" \
    "$WASM_LOCK" \
    "$SOURCE_MANIFEST" \
    "$NATIVE_ROOT/SOURCE_NOTICE.txt" \
    "$ANDROID_ENGINE/build.gradle.kts" \
    "$CONSUMER_RULES" \
    "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" \
    "$NATIVE_BRIDGE" \
    "$NATIVE_EXPORTS" \
    "$ANDROID_ENGINE/src/main/cpp/native_identity.cpp" \
    "$KOTLIN_BINDINGS" \
    "$JNI_PORT" \
    "$ENGINE_FACTORY" \
    "$ANDROID_ENGINE/src/main/kotlin/com/drawlesschess/engine/AndroidUciTimeoutScheduler.kt" \
    "$ANDROID_ENGINE/src/main/kotlin/com/drawlesschess/engine/VariantConfigInstaller.kt" \
    "$ANDROID_ENGINE/src/test/kotlin/com/drawlesschess/core/JniFairyEnginePortTests.kt" \
    "$INSTRUMENTED_TEST" \
    "$HOST_BINDINGS" \
    "$REPOSITORY_ROOT/scripts/native-verify-jni-host.sh" \
    "$REPOSITORY_ROOT/scripts/native-verify-aar.sh"; do
    [[ -f "$required_file" ]] || die "missing required file: $required_file"
done

REVISION=$(property_from "$LOCK_FILE" revision)
TREE=$(property_from "$LOCK_FILE" tree)
PATCHED_TREE=$(property_from "$LOCK_FILE" patchedTree)
REPOSITORY=$(property_from "$LOCK_FILE" repository)
SOURCE_DIRECTORY=$(property_from "$LOCK_FILE" sourceDirectory)
PATCH_SERIES_RELATIVE=$(property_from "$LOCK_FILE" patchSeries)
EXPECTED_PATCH_HASH=$(property_from "$LOCK_FILE" patchSeriesSha256)
PATCH_VERSION=$(property_from "$LOCK_FILE" drawlessPatchVersion)
NDK_VERSION=$(property_from "$LOCK_FILE" androidNdkVersion)
CMAKE_VERSION=$(property_from "$LOCK_FILE" cmakeVersion)
CMAKE_EXECUTABLE_VERSION=$(property_from "$LOCK_FILE" cmakeExecutableVersion)
LIBRARY_NAME=$(property_from "$LOCK_FILE" nativeLibraryName)
VARIANT_CONFIG_HASH=$(property_from "$LOCK_FILE" variantConfigSha256)
BRIDGE_ABI_VERSION=$(property_from "$LOCK_FILE" nativeBridgeAbiVersion)

[[ "$REVISION" =~ ^[0-9a-f]{40}$ ]] || die "native revision is not a full Git hash"
[[ "$TREE" =~ ^[0-9a-f]{40}$ ]] || die "native tree is not a full Git hash"
[[ "$PATCHED_TREE" =~ ^[0-9a-f]{40}$ ]] || die "patched tree is not a full Git hash"
[[ "$EXPECTED_PATCH_HASH" =~ ^[0-9a-f]{64}$ ]] || die "invalid patch-series SHA-256"
[[ "$REPOSITORY" == https://github.com/fairy-stockfish/Fairy-Stockfish.git ]] \
    || die "native repository is not the canonical Fairy-Stockfish URL"
[[ "$PATCH_VERSION" =~ ^[1-9][0-9]*$ ]] || die "invalid Drawless patch version"
[[ "$NDK_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid NDK version"
[[ "$CMAKE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid CMake version"
[[ "$CMAKE_EXECUTABLE_VERSION" == "${CMAKE_VERSION}-g37088a8-dirty" ]] \
    || die "unexpected Android SDK CMake executable identity"
[[ "$LIBRARY_NAME" == drawless_fairy ]] || die "unexpected version-1 native library name"
[[ "$VARIANT_CONFIG_HASH" =~ ^[0-9a-f]{64}$ ]] || die "invalid variant-config SHA-256"
[[ "$BRIDGE_ABI_VERSION" =~ ^[1-9][0-9]*$ ]] || die "invalid native bridge ABI version"
[[ "$(hash_file "$REPOSITORY_ROOT/engine/variants.ini")" == "$VARIANT_CONFIG_HASH" ]] \
    || die "Drawless native variant configuration drifted from the lock"

WASM_GIT_HEAD=$(property_from "$WASM_LOCK" packageGitHead)
WASM_VERSION=$(property_from "$WASM_LOCK" packageVersion)
WASM_SEPARATE=$(property_from "$WASM_LOCK" nativeSourceIdentityIsSeparate)
[[ "$WASM_GIT_HEAD" == 5589ea54f322e8e76c199440e55ae39fe5d3b09c ]] \
    || die "WASM POC identity drifted"
[[ "$WASM_VERSION" == 1.1.11 ]] || die "WASM POC package version drifted"
[[ "$WASM_SEPARATE" == true ]] || die "WASM and native identities must remain separate"

PATCH_SERIES="$NATIVE_ROOT/$PATCH_SERIES_RELATIVE"
[[ -f "$PATCH_SERIES" ]] || die "missing ordered Drawless patch series: $PATCH_SERIES"
PATCH_DIRECTORY=$(dirname -- "$PATCH_SERIES")
PATCH_HASH_INPUT=$(mktemp "${TMPDIR:-/tmp}/drawless-patches.XXXXXX")
EXPECTED_INDEX=""
cleanup() {
    rm -f "$PATCH_HASH_INPUT"
    [[ -z "$EXPECTED_INDEX" ]] || rm -f "$EXPECTED_INDEX"
}
trap cleanup EXIT

: > "$PATCH_HASH_INPUT"
printf 'series\0' >> "$PATCH_HASH_INPUT"
command cat "$PATCH_SERIES" >> "$PATCH_HASH_INPUT"
PATCH_COUNT=0
PATCH_ENTRIES=()
while IFS= read -r patch_entry || [[ -n "$patch_entry" ]]; do
    patch_entry=${patch_entry%$'\r'}
    [[ -n "$patch_entry" && "$patch_entry" != \#* ]] || continue
    case "$patch_entry" in
        /*|*../*|../*|*'/..') die "unsafe patch path in series: $patch_entry" ;;
    esac
    [[ -f "$PATCH_DIRECTORY/$patch_entry" ]] \
        || die "series references missing patch: $patch_entry"
    printf '\0patch\0%s\0' "$patch_entry" >> "$PATCH_HASH_INPUT"
    command cat "$PATCH_DIRECTORY/$patch_entry" >> "$PATCH_HASH_INPUT"
    PATCH_ENTRIES+=("$patch_entry")
    PATCH_COUNT=$((PATCH_COUNT + 1))
done < "$PATCH_SERIES"
((PATCH_COUNT > 0)) || die "production patch series is empty"
PATCH_HASH=$(hash_file "$PATCH_HASH_INPUT")
[[ "$PATCH_HASH" == "$EXPECTED_PATCH_HASH" ]] || die "ordered patch series drifted from native lock"

MANIFEST_ENTRIES=()
while IFS= read -r source_entry || [[ -n "$source_entry" ]]; do
    source_entry=${source_entry%$'\r'}
    [[ -n "$source_entry" && "$source_entry" != \#* ]] || continue
    [[ "$source_entry" == *.cpp ]] || die "non-C++ entry in source manifest: $source_entry"
    [[ "$source_entry" != main.cpp ]] || die "main.cpp must not enter the shared-library scaffold"
    MANIFEST_ENTRIES+=("$source_entry")
done < "$SOURCE_MANIFEST"
((${#MANIFEST_ENTRIES[@]} > 0)) || die "source manifest is empty"
UNIQUE_COUNT=$(printf '%s\n' "${MANIFEST_ENTRIES[@]}" | LC_ALL=C sort -u | wc -l | tr -d ' ')
[[ "$UNIQUE_COUNT" == "${#MANIFEST_ENTRIES[@]}" ]] || die "duplicate source-manifest entry"

require_text "$ANDROID_ENGINE/build.gradle.kts" 'abiFilters += listOf("arm64-v8a", "x86_64")'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'ndkVersion = nativePin("androidNdkVersion")'
require_text "$ANDROID_ENGINE/build.gradle.kts" '"-DANDROID_STL=c++_static"'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'gitOutput("write-tree")'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'archive-fairy-source.sha256'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'patchesApplied" to "true"'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'consumerProguardFiles("consumer-rules.pro")'
require_text "$ANDROID_ENGINE/build.gradle.kts" '"NATIVE_BRIDGE_ABI_VERSION"'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'androidTestImplementation("androidx.test:core:1.7.0")'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'androidTestImplementation("androidx.test:runner:1.7.0")'
require_text "$ANDROID_ENGINE/build.gradle.kts" 'androidTestImplementation("androidx.test.ext:junit:1.3.0")'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'set(DRAWLESS_ALLOWED_ABIS arm64-v8a x86_64)'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'cmake_path(SET FAIRY_SOURCE_DIR NORMALIZE "${FAIRY_SOURCE_DIR}")'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'cmake_path(SET DRAWLESS_NATIVE_ROOT NORMALIZE "${DRAWLESS_NATIVE_ROOT}")'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'add_library(drawless_fairy SHARED'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'native_bridge.cpp'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'native_identity.cpp'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'DRAWLESS_BRIDGE_ABI_VERSION=${DRAWLESS_BRIDGE_ABI_VERSION}'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'COMMAND "${GIT_EXECUTABLE}" -C "${FAIRY_SOURCE_DIR}" write-tree'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'archive-fairy-source.sha256'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'find_library(ANDROID_LOG_LIBRARY log REQUIRED)'
reject_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'ANDROID_ATOMIC_LIBRARY'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" 'target_link_options(drawless_fairy PRIVATE -Wl,--no-gc-sections)'
require_text "$ANDROID_ENGINE/src/main/cpp/CMakeLists.txt" '-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/native_exports.map'
require_text "$NATIVE_EXPORTS" 'JNI_OnLoad;'
require_text "$NATIVE_EXPORTS" 'local:'
require_text "$NATIVE_EXPORTS" '*;'
require_text "$NATIVE_BRIDGE" 'constexpr char kBindingClass[] = "com/drawlesschess/engine/FairyNativeBindings";'
require_text "$NATIVE_BRIDGE" 'extern "C" JNIEXPORT jint JNICALL JNI_OnLoad'
require_text "$NATIVE_BRIDGE" 'environment->RegisterNatives('
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeCreate"), const_cast<char*>("(Ljava/lang/String;)J")'
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeStart"), const_cast<char*>("(J)V")'
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeWrite"), const_cast<char*>("(J[BII)I")'
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeRead"), const_cast<char*>("(J[BII)I")'
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeReadError"), const_cast<char*>("(J[BII)I")'
require_text "$NATIVE_BRIDGE" '{const_cast<char*>("nativeClose"), const_cast<char*>("(J)V")'
require_text "$KOTLIN_BINDINGS" 'System.loadLibrary("drawless_fairy")'
require_text "$KOTLIN_BINDINGS" 'object FairyNativeBindings {'
require_text "$KOTLIN_BINDINGS" 'external fun nativeCreate(variantConfigPath: String): Long'
require_text "$KOTLIN_BINDINGS" 'external fun nativeStart(handle: Long)'
require_text "$KOTLIN_BINDINGS" 'external fun nativeWrite(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int'
require_text "$KOTLIN_BINDINGS" 'external fun nativeRead(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int'
require_text "$KOTLIN_BINDINGS" 'external fun nativeReadError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int'
require_text "$KOTLIN_BINDINGS" 'external fun nativeClose(handle: Long)'
require_text "$CONSUMER_RULES" '-keep class com.drawlesschess.engine.FairyNativeBindings {'
require_text "$CONSUMER_RULES" 'public static native <methods>;'
require_text "$JNI_PORT" 'class JniFairyEnginePort private constructor('
require_text "$ENGINE_FACTORY" 'port = JniFairyEnginePort(variantFile.absolutePath, portPolicy)'
require_text "$ENGINE_FACTORY" 'BuildConfig.NATIVE_BRIDGE_ABI_VERSION'
require_text "$INSTRUMENTED_TEST" '@RunWith(AndroidJUnit4::class)'
require_text "$INSTRUMENTED_TEST" 'class AndroidFairyEngineInstrumentedTest'
require_text "$INSTRUMENTED_TEST" 'fun forcedRepetitionSearchClosesAndRestartsSequentially()'
require_text "$INSTRUMENTED_TEST" 'AndroidFairyEngineFactory('
require_text "$INSTRUMENTED_TEST" 'val first = factory.create()'
require_text "$INSTRUMENTED_TEST" 'first.close()'
require_text "$INSTRUMENTED_TEST" 'val second = factory.create()'
require_text "$INSTRUMENTED_TEST" 'second.close()'
require_text "$INSTRUMENTED_TEST" 'h8g8'
require_text "$INSTRUMENTED_TEST" 'response.engine.drawlessPatch'
require_text "$HOST_BINDINGS" 'private static native long nativeCreate(String variantConfigPath);'
require_text "$HOST_BINDINGS" 'private static native void nativeClose(long handle);'
[[ "$(grep -Fc '@JvmStatic' "$KOTLIN_BINDINGS")" == 6 ]] \
    || die "$KOTLIN_BINDINGS must expose exactly six static JNI methods"
[[ "$(grep -Fc 'external fun native' "$KOTLIN_BINDINGS")" == 6 ]] \
    || die "$KOTLIN_BINDINGS must declare exactly six native methods"
[[ "$(grep -Fc 'reinterpret_cast<void*>(native_' "$NATIVE_BRIDGE")" == 6 ]] \
    || die "$NATIVE_BRIDGE must register exactly six native functions"
require_text "$NATIVE_ROOT/SOURCE_NOTICE.txt" 'GNU General Public License'
require_text "$NATIVE_ROOT/SOURCE_NOTICE.txt" "$REVISION"
require_text "$NATIVE_ROOT/SOURCE_NOTICE.txt" 'complete corresponding source'

SOURCE="$NATIVE_ROOT/$SOURCE_DIRECTORY"
if [[ ! -d "$SOURCE" ]]; then
    $REQUIRE_SOURCE && die "pinned source is absent; run scripts/native-fetch-fairy.sh"
    printf 'Native structure PASS (source checkout absent; NDK compilation not tested).\n'
    printf '  native pin: %s\n' "$REVISION"
    printf '  patch series SHA-256: %s\n' "$PATCH_HASH"
    printf '  ABIs: arm64-v8a, x86_64\n'
    printf '  JNI bridge ABI: %s; binding and instrumentation contracts present\n' "$BRIDGE_ABI_VERSION"
    exit 0
fi

STATE_FILE="$SOURCE/.drawless-source-state.properties"
[[ -f "$STATE_FILE" ]] || die "missing source-state marker; rerun native-fetch-fairy.sh"
[[ "$(property_from "$STATE_FILE" upstreamRevision)" == "$REVISION" ]] \
    || die "source-state revision mismatch"
[[ "$(property_from "$STATE_FILE" upstreamTree)" == "$TREE" ]] \
    || die "source-state tree mismatch"
[[ "$(property_from "$STATE_FILE" patchedTree)" == "$PATCHED_TREE" ]] \
    || die "source-state patched-tree mismatch"
[[ "$(property_from "$STATE_FILE" patchVersion)" == "$PATCH_VERSION" ]] \
    || die "source-state patch version mismatch"
[[ "$(property_from "$STATE_FILE" patchesApplied)" == true ]] \
    || die "production build requires the Drawless patches"
[[ "$(property_from "$STATE_FILE" patchSeriesSha256)" == "$PATCH_HASH" ]] \
    || die "source-state patch-series hash mismatch"

for source_entry in "${MANIFEST_ENTRIES[@]}"; do
    [[ -f "$SOURCE/src/$source_entry" ]] || die "checkout is missing src/$source_entry"
done
[[ -f "$SOURCE/Copying.txt" ]] || die "checkout is missing the full GPL text"
[[ -f "$SOURCE/AUTHORS" ]] || die "checkout is missing AUTHORS"

if [[ -d "$SOURCE/.git" ]]; then
    ACTUAL_REVISION=$(git -C "$SOURCE" rev-parse HEAD)
    ACTUAL_TREE=$(git -C "$SOURCE" rev-parse 'HEAD^{tree}')
    [[ "$ACTUAL_REVISION" == "$REVISION" ]] || die "source revision does not match lock"
    [[ "$ACTUAL_TREE" == "$TREE" ]] || die "source base tree does not match lock"

    EXPECTED_INDEX=$(mktemp "${TMPDIR:-/tmp}/drawless-index.XXXXXX")
    rm -f "$EXPECTED_INDEX"
    GIT_INDEX_FILE="$EXPECTED_INDEX" git -C "$SOURCE" read-tree HEAD
    for patch_entry in "${PATCH_ENTRIES[@]}"; do
        GIT_INDEX_FILE="$EXPECTED_INDEX" git -C "$SOURCE" apply --cached \
            "$PATCH_DIRECTORY/$patch_entry"
    done
    EXPECTED_PATCHED_TREE=$(GIT_INDEX_FILE="$EXPECTED_INDEX" git -C "$SOURCE" write-tree)
    ACTUAL_PATCHED_TREE=$(git -C "$SOURCE" write-tree)
    [[ "$ACTUAL_PATCHED_TREE" == "$EXPECTED_PATCHED_TREE" ]] \
        || die "staged source does not exactly match the ordered Drawless patch series"
    [[ "$ACTUAL_PATCHED_TREE" == "$PATCHED_TREE" ]] \
        || die "patched source tree does not match the native lock"

    git -C "$SOURCE" diff --quiet || die "source has unstaged modifications"
    git -C "$SOURCE" diff --cached --check
    UNEXPECTED_UNTRACKED=$(git -C "$SOURCE" ls-files --others --exclude-standard \
        | grep -Fvx '.drawless-source-state.properties' || true)
    [[ -z "$UNEXPECTED_UNTRACKED" ]] \
        || die "source contains unexpected untracked files: $UNEXPECTED_UNTRACKED"
elif [[ -f "$ARCHIVE_SOURCE_MANIFEST" ]]; then
    ARCHIVE_ENTRIES=()
    while IFS= read -r manifest_line || [[ -n "$manifest_line" ]]; do
        manifest_line=${manifest_line%$'\r'}
        if [[ ! "$manifest_line" =~ ^([0-9a-f]{64})[[:space:]]+\*?\./(.+)$ ]]; then
            die "invalid native archive manifest row: $manifest_line"
        fi
        expected_hash=${BASH_REMATCH[1]}
        relative_path=${BASH_REMATCH[2]}
        case "$relative_path" in
            /*|*\\*|*:|*:*|../*|*/../*|*/..|.|..)
                die "unsafe native archive manifest path: $relative_path" ;;
        esac
        [[ -f "$SOURCE/$relative_path" ]] \
            || die "native archive manifest file is absent: $relative_path"
        [[ "$(hash_file "$SOURCE/$relative_path")" == "$expected_hash" ]] \
            || die "native archive source hash mismatch: $relative_path"
        ARCHIVE_ENTRIES+=("$relative_path")
    done < "$ARCHIVE_SOURCE_MANIFEST"
    ((${#ARCHIVE_ENTRIES[@]} > 0)) || die "native archive source manifest is empty"
    UNIQUE_ARCHIVE_COUNT=$(printf '%s\n' "${ARCHIVE_ENTRIES[@]}" | sort -u | wc -l | tr -d ' ')
    [[ "$UNIQUE_ARCHIVE_COUNT" == "${#ARCHIVE_ENTRIES[@]}" ]] \
        || die "duplicate native archive source manifest entry"
    EXPECTED_ARCHIVE_FILES=$(printf './%s\n' "${ARCHIVE_ENTRIES[@]}" | sort)
    ACTUAL_ARCHIVE_FILES=$(cd "$SOURCE" && find . -type f -print | sort)
    [[ "$ACTUAL_ARCHIVE_FILES" == "$EXPECTED_ARCHIVE_FILES" ]] \
        || die "native archive source file set differs from its manifest"
    ACTUAL_PATCHED_TREE="$PATCHED_TREE"
else
    die "source has neither pinned Git metadata nor an archive source manifest"
fi

printf 'Native structure and pinned source PASS.\n'
printf '  native pin: %s\n' "$REVISION"
printf '  upstream tree: %s\n' "$TREE"
printf '  patched index tree: %s\n' "$ACTUAL_PATCHED_TREE"
printf '  patch series SHA-256: %s\n' "$PATCH_HASH"
printf '  ABIs: arm64-v8a, x86_64\n'
printf '  NDK/CMake package/executable: %s / %s / %s\n' \
    "$NDK_VERSION" "$CMAKE_VERSION" "$CMAKE_EXECUTABLE_VERSION"
printf '  JNI bridge ABI: %s; binding and instrumentation contracts present\n' "$BRIDGE_ABI_VERSION"
