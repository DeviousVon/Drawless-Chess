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

die() {
    printf 'native-verify-jni-host: %s\n' "$*" >&2
    exit 1
}

property() {
    local key=$1
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK_FILE"
}

for tool in g++ timeout; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done

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

if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1 && \
   command -v readlink >/dev/null 2>&1; then
    JAVAC_PATH=$(readlink -f "$(command -v javac)")
    JAVA_HOME=$(CDPATH= cd -- "$(dirname -- "$JAVAC_PATH")/.." && pwd)
else
    JAVA_HOME=
fi

if [[ "$SANITIZE" == 0 && -n "$JAVA_HOME" && -f "$JAVA_HOME/include/jni.h" && \
      -f "$JAVA_HOME/include/linux/jni_md.h" ]]; then
    LIBRARY="$TEMP_ROOT/libdrawless_fairy.so"
    g++ -std=c++17 -O2 -fPIC -shared -pthread \
        -Wall -Wcast-qual -fno-exceptions -fno-strict-aliasing \
        -DIS_64BIT -DUSE_PTHREADS -DNNUE_EMBEDDING_OFF -DUSE_SSE2 -DNO_PREFETCH \
        "-DDRAWLESS_UPSTREAM_REVISION=\"$(property revision)\"" \
        "-DDRAWLESS_UPSTREAM_TREE=\"$(property tree)\"" \
        "-DDRAWLESS_PATCHED_TREE=\"$(property patchedTree)\"" \
        "-DDRAWLESS_PATCH_SERIES_SHA256=\"$(property patchSeriesSha256)\"" \
        "-DDRAWLESS_PATCH_VERSION=$(property drawlessPatchVersion)" \
        "-DDRAWLESS_BRIDGE_ABI_VERSION=$(property nativeBridgeAbiVersion)" \
        -I"$SOURCE/src" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
        "${SOURCES[@]}" \
        "$BRIDGE_ROOT/native_bridge.cpp" \
        "$BRIDGE_ROOT/native_identity.cpp" \
        -Wl,-z,defs -Wl,--no-gc-sections \
        -Wl,--version-script="$BRIDGE_ROOT/native_exports.map" \
        -o "$LIBRARY"

    javac -d "$TEMP_ROOT/classes" \
        "$NATIVE_ROOT/host-test/com/drawlesschess/engine/FairyNativeBindings.java"

    timeout 45s java -cp "$TEMP_ROOT/classes" com.drawlesschess.engine.FairyNativeBindings \
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
        "${SANITIZER_FLAGS[@]}" \
        -DDRAWLESS_HOST_BRIDGE_TEST \
        -DIS_64BIT -DUSE_PTHREADS -DNNUE_EMBEDDING_OFF -DUSE_SSE2 -DNO_PREFETCH \
        -I"$SOURCE/src" \
        "${SOURCES[@]}" \
        "$BRIDGE_ROOT/native_bridge.cpp" \
        "$NATIVE_ROOT/host-test/native_bridge_smoke.cpp" \
        -Wl,-z,defs -Wl,--no-gc-sections \
        -o "$HOST_TEST"
    if [[ "$SANITIZE" == 1 ]]; then
        ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 \
        UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
            timeout 45s "$HOST_TEST" "$REPOSITORY_ROOT/engine/variants.ini"
    else
        timeout 45s "$HOST_TEST" "$REPOSITORY_ROOT/engine/variants.ini"
    fi
fi
