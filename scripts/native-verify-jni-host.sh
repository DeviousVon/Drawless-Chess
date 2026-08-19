#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
NATIVE_ROOT="$REPOSITORY_ROOT/engine/native"
LOCK_FILE="$NATIVE_ROOT/upstream.properties"
SOURCE_MANIFEST="$NATIVE_ROOT/source-manifest.txt"
SOURCE="$NATIVE_ROOT/$(awk -F= '$1 == "sourceDirectory" { print $2 }' "$LOCK_FILE")"
BRIDGE_ROOT="$REPOSITORY_ROOT/android/engine/src/main/cpp"
HOST_OS=$(uname -s)
HOST_ARCH=$(uname -m)

die() {
    printf 'native-verify-jni-host: %s\n' "$*" >&2
    exit 1
}

property() {
    local key=$1
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK_FILE"
}

command -v g++ >/dev/null 2>&1 || die "g++ is required"

if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_TOOL=$(command -v timeout)
elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_TOOL=$(command -v gtimeout)
else
    die "timeout (Linux) or gtimeout (macOS coreutils) is required"
fi

case "$HOST_ARCH" in
    arm64|aarch64)
        ENGINE_FEATURE_FLAGS=(-DUSE_NEON -DUSE_POPCNT)
        ;;
    x86_64|amd64)
        ENGINE_FEATURE_FLAGS=(-DUSE_SSE2 -DNO_PREFETCH)
        ;;
    *)
        die "unsupported host architecture: $HOST_ARCH"
        ;;
esac

case "$HOST_OS" in
    Darwin)
        command -v nm >/dev/null 2>&1 || die "nm is required on macOS"
        JNI_PLATFORM=darwin
        LIBRARY_EXTENSION=dylib
        SHARED_LIBRARY_FLAGS=(-dynamiclib)
        SHARED_LINKER_FLAGS=()
        HOST_LINKER_FLAGS=()
        ;;
    Linux)
        JNI_PLATFORM=linux
        LIBRARY_EXTENSION=so
        SHARED_LIBRARY_FLAGS=(-shared)
        SHARED_LINKER_FLAGS=(
            -Wl,-z,defs
            -Wl,--no-gc-sections
            -Wl,--version-script="$BRIDGE_ROOT/native_exports.map"
        )
        HOST_LINKER_FLAGS=(-Wl,-z,defs -Wl,--no-gc-sections)
        ;;
    *)
        die "unsupported host operating system: $HOST_OS"
        ;;
esac

"$SCRIPT_DIR/native-validate-structure.sh" --require-source

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-jni-host.XXXXXX")
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT

SOURCES=()
while IFS= read -r source_entry || [[ -n "$source_entry" ]]; do
    source_entry=${source_entry%$'\r'}
    [[ -n "$source_entry" && "$source_entry" != \#* ]] || continue
    SOURCES+=("$SOURCE/src/$source_entry")
done < "$SOURCE_MANIFEST"

SANITIZE=${DRAWLESS_HOST_SANITIZERS:-0}
[[ "$SANITIZE" == 0 || "$SANITIZE" == 1 ]] \
    || die "DRAWLESS_HOST_SANITIZERS must be 0 or 1"

RESOLVED_JAVA_HOME=
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    if [[ "$HOST_OS" == Darwin ]]; then
        if [[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/include/jni.h" ]]; then
            RESOLVED_JAVA_HOME=$JAVA_HOME
        elif [[ -x /usr/libexec/java_home ]]; then
            RESOLVED_JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
        fi
    elif command -v readlink >/dev/null 2>&1; then
        JAVAC_PATH=$(readlink -f "$(command -v javac)")
        RESOLVED_JAVA_HOME=$(CDPATH= cd -- "$(dirname -- "$JAVAC_PATH")/.." && pwd)
    fi
fi

if [[ "$SANITIZE" == 0 && -n "$RESOLVED_JAVA_HOME" && \
      -f "$RESOLVED_JAVA_HOME/include/jni.h" && \
      -f "$RESOLVED_JAVA_HOME/include/$JNI_PLATFORM/jni_md.h" ]]; then
    if [[ "$HOST_OS" == Darwin ]]; then
        DARWIN_EXPORTS="$TEMP_ROOT/native_exports.list"
        awk '
            $1 == "global:" { in_global = 1; next }
            $1 == "local:" { in_global = 0 }
            in_global {
                symbol = $1
                sub(/;$/, "", symbol)
                if (symbol ~ /^[A-Za-z_][A-Za-z0-9_]*$/)
                    print "_" symbol
            }
        ' "$BRIDGE_ROOT/native_exports.map" > "$DARWIN_EXPORTS"
        SHARED_LINKER_FLAGS+=("-Wl,-exported_symbols_list,$DARWIN_EXPORTS")
    fi

    LIBRARY="$TEMP_ROOT/libdrawless_fairy.$LIBRARY_EXTENSION"
    g++ -std=c++17 -O2 -fPIC "${SHARED_LIBRARY_FLAGS[@]}" -pthread \
        -Wall -Wcast-qual -fno-exceptions -fno-strict-aliasing \
        -DIS_64BIT -DUSE_PTHREADS -DNNUE_EMBEDDING_OFF \
        "${ENGINE_FEATURE_FLAGS[@]}" \
        "-DDRAWLESS_UPSTREAM_REVISION=\"$(property revision)\"" \
        "-DDRAWLESS_UPSTREAM_TREE=\"$(property tree)\"" \
        "-DDRAWLESS_PATCHED_TREE=\"$(property patchedTree)\"" \
        "-DDRAWLESS_PATCH_SERIES_SHA256=\"$(property patchSeriesSha256)\"" \
        "-DDRAWLESS_PATCH_VERSION=$(property drawlessPatchVersion)" \
        "-DDRAWLESS_BRIDGE_ABI_VERSION=$(property nativeBridgeAbiVersion)" \
        -I"$SOURCE/src" -I"$RESOLVED_JAVA_HOME/include" \
        -I"$RESOLVED_JAVA_HOME/include/$JNI_PLATFORM" \
        "${SOURCES[@]}" \
        "$BRIDGE_ROOT/native_bridge.cpp" \
        "$BRIDGE_ROOT/native_identity.cpp" \
        "${SHARED_LINKER_FLAGS[@]}" \
        -o "$LIBRARY"

    if [[ "$HOST_OS" == Darwin ]]; then
        DARWIN_DEFINED_GLOBALS=$(nm -gU "$LIBRARY")
        while IFS= read -r required_symbol; do
            [[ -n "$required_symbol" ]] || continue
            awk -v required="$required_symbol" \
                '$NF == required { found = 1 } END { exit !found }' \
                <<< "$DARWIN_DEFINED_GLOBALS" \
                || die "required JNI/identity export is missing: $required_symbol"
        done < "$DARWIN_EXPORTS"
    fi

    javac -d "$TEMP_ROOT/classes" \
        "$NATIVE_ROOT/host-test/com/drawlesschess/engine/FairyNativeBindings.java"

    "$TIMEOUT_TOOL" 45s java -cp "$TEMP_ROOT/classes" com.drawlesschess.engine.FairyNativeBindings \
        "$LIBRARY" "$REPOSITORY_ROOT/engine/variants.ini"
else
    if [[ "$SANITIZE" == 1 ]]; then
        printf '%s\n' "native-verify-jni-host: using ASan/UBSan C++ host bridge lane"
        SANITIZER_FLAGS=(-fsanitize=address,undefined -fno-omit-frame-pointer)
    else
        printf '%s\n' "native-verify-jni-host: JDK headers unavailable; using C++ host bridge lane"
        SANITIZER_FLAGS=()
    fi
    HOST_TEST="$TEMP_ROOT/drawless-host-bridge-test"
    g++ -std=c++17 -O2 -pthread \
        -Wall -Wcast-qual -fno-exceptions -fno-strict-aliasing \
        ${SANITIZER_FLAGS[@]+"${SANITIZER_FLAGS[@]}"} \
        -DDRAWLESS_HOST_BRIDGE_TEST \
        -DIS_64BIT -DUSE_PTHREADS -DNNUE_EMBEDDING_OFF \
        "${ENGINE_FEATURE_FLAGS[@]}" \
        -I"$SOURCE/src" \
        "${SOURCES[@]}" \
        "$BRIDGE_ROOT/native_bridge.cpp" \
        "$NATIVE_ROOT/host-test/native_bridge_smoke.cpp" \
        ${HOST_LINKER_FLAGS[@]+"${HOST_LINKER_FLAGS[@]}"} \
        -o "$HOST_TEST"
    if [[ "$SANITIZE" == 1 ]]; then
        ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 \
        UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
            "$TIMEOUT_TOOL" 45s "$HOST_TEST" "$REPOSITORY_ROOT/engine/variants.ini"
    else
        "$TIMEOUT_TOOL" 45s "$HOST_TEST" "$REPOSITORY_ROOT/engine/variants.ini"
    fi
fi
