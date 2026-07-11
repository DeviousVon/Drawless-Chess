#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ANDROID_ROOT="$REPOSITORY_ROOT/android"
NATIVE_LOCK="$REPOSITORY_ROOT/engine/native/upstream.properties"
EVIDENCE_BASE="$REPOSITORY_ROOT/build/android-machine-verification"

PROJECT_JAVA_COMPATIBILITY=17
SUPPORTED_JAVA_MAJORS=(17 21)
SUPPORTED_JAVA_MAJORS_CSV=17,21
EXPECTED_GRADLE_VERSION=9.4.1
EXPECTED_AGP_VERSION=9.2.1
EXPECTED_COMPOSE_PLUGIN_VERSION=2.3.10
EXPECTED_PLATFORM=36
EXPECTED_BUILD_TOOLS=36.0.0
EXPECTED_MIN_API=26
INSTRUMENTATION_CLASS=com.drawlesschess.engine.AndroidFairyEngineInstrumentedTest
INSTRUMENTATION_TIMEOUT_SECONDS=240

SDK_ARGUMENT=
SERIAL_ARGUMENT=
REQUIRED_ABI=
OUTPUT_ARGUMENT=
WORKERS=2
ALLOW_PHYSICAL=false
PREFLIGHT_ONLY=false

usage() {
    cat <<'USAGE'
Usage: scripts/android-machine-verify.sh [options]

Options:
  --sdk PATH                 Android SDK root (otherwise ANDROID_SDK_ROOT/ANDROID_HOME)
  --serial SERIAL            adb serial; required when more than one device is connected
  --require-abi ABI          require arm64-v8a or x86_64 for the selected device
  --output PATH              new evidence directory (default: build/android-machine-verification/<run>)
  --workers N                Gradle worker limit, 1-8 (default: 2)
  --allow-physical-device    permit instrumentation on a non-emulator device
  --preflight-only           verify host/toolchain/source without building or selecting a device
  -h, --help                 show this help

The gate never accepts SDK licenses, installs packages, publishes artifacts, or retries a
failed native test. Full runtime coverage requires separate x86_64-emulator and ARM64-device runs.
USAGE
}

die_usage() {
    printf 'android-machine-verify: %s\n' "$*" >&2
    usage >&2
    exit 2
}

while (($#)); do
    case "$1" in
        --sdk)
            (($# >= 2)) || die_usage '--sdk requires a path'
            SDK_ARGUMENT=$2
            shift 2
            ;;
        --serial)
            (($# >= 2)) || die_usage '--serial requires a value'
            SERIAL_ARGUMENT=$2
            shift 2
            ;;
        --require-abi)
            (($# >= 2)) || die_usage '--require-abi requires a value'
            REQUIRED_ABI=$2
            shift 2
            ;;
        --output)
            (($# >= 2)) || die_usage '--output requires a path'
            OUTPUT_ARGUMENT=$2
            shift 2
            ;;
        --workers)
            (($# >= 2)) || die_usage '--workers requires a value'
            WORKERS=$2
            shift 2
            ;;
        --allow-physical-device)
            ALLOW_PHYSICAL=true
            shift
            ;;
        --preflight-only)
            PREFLIGHT_ONLY=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *) die_usage "unknown option: $1" ;;
    esac
done

[[ "$WORKERS" =~ ^[1-8]$ ]] || die_usage '--workers must be an integer from 1 through 8'
case "$REQUIRED_ABI" in
    ''|arm64-v8a|x86_64) ;;
    *) die_usage '--require-abi must be arm64-v8a or x86_64' ;;
esac

property() {
    local key=$1
    awk -F= -v key="$key" '
        $1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }
    ' "$NATIVE_LOCK"
}

hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    else
        return 127
    fi
}

hash_text() {
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$1" | sha256sum | awk '{ print $1 }'
    else
        printf '%s' "$1" | shasum -a 256 | awk '{ print $1 }'
    fi
}

json_quote() {
    local value=${1:-}
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '"%s"' "$value"
}

file_size() {
    wc -c < "$1" | tr -d '[:space:]'
}

STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
RUN_ID=$(date -u +%Y%m%dT%H%M%SZ)-$$
mkdir -p "$EVIDENCE_BASE"
LOCK_DIRECTORY="$EVIDENCE_BASE/.machine-gate.lock"
if ! mkdir "$LOCK_DIRECTORY" 2>/dev/null; then
    printf 'android-machine-verify: another machine gate owns %s\n' "$LOCK_DIRECTORY" >&2
    exit 1
fi

if [[ -n "$OUTPUT_ARGUMENT" ]]; then
    OUTPUT=$OUTPUT_ARGUMENT
    [[ "$OUTPUT" == /* ]] || OUTPUT="$PWD/$OUTPUT"
else
    OUTPUT="$EVIDENCE_BASE/$RUN_ID"
fi
if [[ -e "$OUTPUT" ]]; then
    rmdir "$LOCK_DIRECTORY" 2>/dev/null || true
    printf 'android-machine-verify: evidence path already exists: %s\n' "$OUTPUT" >&2
    exit 1
fi
mkdir -p "$OUTPUT/logs" "$OUTPUT/manifests" "$OUTPUT/reports"

STATUS=failed
FAILED_PHASE=preflight
EXIT_CODE=1
MODE=full
[[ "$PREFLIGHT_ONLY" == true ]] && MODE=preflight-only
SDK_ROOT=
JAVA_VERSION=
JAVAC_VERSION=
JAVA_MAJOR=
JAVA_VENDOR=
JAVA_RUNTIME_NAME=
JAVA_VM_NAME=
RESOLVED_JAVA_HOME=
JAVA_REPORTED_HOME=
JAVA_HOME_SOURCE=
JAVA_EXECUTABLE=
JAVAC_EXECUTABLE=
GRADLE_VERSION=
GRADLE_LAUNCHER_JVM=
GRADLE_LAUNCHER_JAVA_VERSION=
GRADLE_LAUNCHER_JAVA_MAJOR=
GRADLE_JAVA_HOME_FORCED=false
ADB_VERSION=
CMAKE_PACKAGE_REVISION=
CMAKE_PACKAGE_PATH=
CMAKE_EXECUTABLE_VERSION=
GIT_VERSION=
PROJECT_GIT_COMMIT=
PROJECT_GIT_DIRTY=
DEVICE_SERIAL_HASH=
DEVICE_API=
DEVICE_ABILIST=
SELECTED_ABI=
DEVICE_MODEL=
DEVICE_MANUFACTURER=
DEVICE_BUILD=
DEVICE_SECURITY_PATCH=
DEVICE_KIND=
RUNTIME_VERIFIED_ABI=
APP_DEBUG_APK=
APP_RELEASE_APK=
ENGINE_TEST_APK=
ENGINE_DEBUG_AAR=
ENGINE_RELEASE_AAR=
VERIFIED_ARTIFACT_SET_SHA=
TEMP_ROOT=

write_manifest() {
    local finished_at
    finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    {
        printf '{\n'
        printf '  "schemaVersion": 1,\n'
        printf '  "result": %s,\n' "$(json_quote "$STATUS")"
        printf '  "exitCode": %s,\n' "$EXIT_CODE"
        printf '  "mode": %s,\n' "$(json_quote "$MODE")"
        printf '  "failedPhase": %s,\n' "$(json_quote "$FAILED_PHASE")"
        printf '  "startedAt": %s,\n' "$(json_quote "$STARTED_AT")"
        printf '  "finishedAt": %s,\n' "$(json_quote "$finished_at")"
        printf '  "privateTestOnly": true,\n'
        printf '  "distributionAuthorized": false,\n'
        printf '  "source": {"revision":%s,"tree":%s,"patchedTree":%s,"patchSeriesSha256":%s,"variantConfigSha256":%s,"bridgeAbiVersion":%s},\n' \
            "$(json_quote "$(property revision)")" \
            "$(json_quote "$(property tree)")" \
            "$(json_quote "$(property patchedTree)")" \
            "$(json_quote "$(property patchSeriesSha256)")" \
            "$(json_quote "$(property variantConfigSha256)")" \
            "$(property nativeBridgeAbiVersion)"
        printf '  "project": {"gitCommit":%s,"gitDirty":%s},\n' \
            "$(json_quote "$PROJECT_GIT_COMMIT")" "$(json_quote "$PROJECT_GIT_DIRTY")"
        printf '  "toolchain": {"java":%s,"javac":%s,"javaMajor":%s,"supportedJavaMajors":[17,21],"javaVendor":%s,"javaRuntimeName":%s,"javaVmName":%s,"javaHome":%s,"javaReportedHome":%s,"javaHomeSource":%s,"javaExecutable":%s,"javacExecutable":%s,"androidSourceCompatibility":%s,"androidTargetCompatibility":%s,"gradle":%s,"gradleLauncherJvm":%s,"gradleLauncherJavaVersion":%s,"gradleLauncherJavaMajor":%s,"gradleJavaHome":%s,"gradleJavaHomeForced":%s,"agp":%s,"composePlugin":%s,"compileSdk":%s,"buildTools":%s,"ndk":%s,"cmake":%s,"cmakePackageRevision":%s,"cmakePackagePath":%s,"cmakeExecutableVersion":%s,"git":%s,"adb":%s,"sdkRoot":%s},\n' \
            "$(json_quote "$JAVA_VERSION")" "$(json_quote "$JAVAC_VERSION")" "${JAVA_MAJOR:-null}" \
            "$(json_quote "$JAVA_VENDOR")" "$(json_quote "$JAVA_RUNTIME_NAME")" \
            "$(json_quote "$JAVA_VM_NAME")" "$(json_quote "$RESOLVED_JAVA_HOME")" \
            "$(json_quote "$JAVA_REPORTED_HOME")" "$(json_quote "$JAVA_HOME_SOURCE")" \
            "$(json_quote "$JAVA_EXECUTABLE")" "$(json_quote "$JAVAC_EXECUTABLE")" \
            "$PROJECT_JAVA_COMPATIBILITY" "$PROJECT_JAVA_COMPATIBILITY" \
            "$(json_quote "$GRADLE_VERSION")" "$(json_quote "$GRADLE_LAUNCHER_JVM")" \
            "$(json_quote "$GRADLE_LAUNCHER_JAVA_VERSION")" \
            "${GRADLE_LAUNCHER_JAVA_MAJOR:-null}" "$(json_quote "$RESOLVED_JAVA_HOME")" \
            "$GRADLE_JAVA_HOME_FORCED" \
            "$(json_quote "$EXPECTED_AGP_VERSION")" \
            "$(json_quote "$EXPECTED_COMPOSE_PLUGIN_VERSION")" "$EXPECTED_PLATFORM" \
            "$(json_quote "$EXPECTED_BUILD_TOOLS")" "$(json_quote "$(property androidNdkVersion)")" \
            "$(json_quote "$(property cmakeVersion)")" \
            "$(json_quote "$CMAKE_PACKAGE_REVISION")" "$(json_quote "$CMAKE_PACKAGE_PATH")" \
            "$(json_quote "$CMAKE_EXECUTABLE_VERSION")" "$(json_quote "$GIT_VERSION")" \
            "$(json_quote "$ADB_VERSION")" \
            "$(json_quote "$SDK_ROOT")"
        printf '  "device": {"serialSha256":%s,"kind":%s,"manufacturer":%s,"model":%s,"build":%s,"securityPatch":%s,"api":%s,"abiList":%s,"selectedAbi":%s},\n' \
            "$(json_quote "$DEVICE_SERIAL_HASH")" "$(json_quote "$DEVICE_KIND")" \
            "$(json_quote "$DEVICE_MANUFACTURER")" "$(json_quote "$DEVICE_MODEL")" \
            "$(json_quote "$DEVICE_BUILD")" "$(json_quote "$DEVICE_SECURITY_PATCH")" \
            "$(json_quote "$DEVICE_API")" "$(json_quote "$DEVICE_ABILIST")" \
            "$(json_quote "$SELECTED_ABI")"
        if [[ -n "$APP_DEBUG_APK" && -f "$APP_DEBUG_APK" ]]; then
            printf '  "artifactAbis": ["arm64-v8a","x86_64"],\n'
            printf '  "artifacts": [\n'
            printf '    {"kind":"app-debug-apk","path":%s,"sizeBytes":%s,"sha256":%s},\n' "$(json_quote "$APP_DEBUG_APK")" "$(file_size "$APP_DEBUG_APK")" "$(json_quote "$(hash_file "$APP_DEBUG_APK")")"
            printf '    {"kind":"app-release-apk","path":%s,"sizeBytes":%s,"sha256":%s},\n' "$(json_quote "$APP_RELEASE_APK")" "$(file_size "$APP_RELEASE_APK")" "$(json_quote "$(hash_file "$APP_RELEASE_APK")")"
            printf '    {"kind":"engine-test-apk","path":%s,"sizeBytes":%s,"sha256":%s},\n' "$(json_quote "$ENGINE_TEST_APK")" "$(file_size "$ENGINE_TEST_APK")" "$(json_quote "$(hash_file "$ENGINE_TEST_APK")")"
            printf '    {"kind":"engine-debug-aar","path":%s,"sizeBytes":%s,"sha256":%s},\n' "$(json_quote "$ENGINE_DEBUG_AAR")" "$(file_size "$ENGINE_DEBUG_AAR")" "$(json_quote "$(hash_file "$ENGINE_DEBUG_AAR")")"
            printf '    {"kind":"engine-release-aar","path":%s,"sizeBytes":%s,"sha256":%s}\n' "$(json_quote "$ENGINE_RELEASE_AAR")" "$(file_size "$ENGINE_RELEASE_AAR")" "$(json_quote "$(hash_file "$ENGINE_RELEASE_AAR")")"
            printf '  ],\n'
        else
            printf '  "artifactAbis": [],\n'
            printf '  "artifacts": [],\n'
        fi
        if [[ -n "$RUNTIME_VERIFIED_ABI" ]]; then
            printf '  "runtimeVerifiedAbis": [%s]\n' "$(json_quote "$RUNTIME_VERIFIED_ABI")"
        else
            printf '  "runtimeVerifiedAbis": []\n'
        fi
        printf '}\n'
    } > "$OUTPUT/manifest.json"
}

finalize() {
    local exit_code=$?
    set +e
    EXIT_CODE=$exit_code
    write_manifest
    (
        cd "$OUTPUT" || exit 1
        find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r path; do
            if command -v sha256sum >/dev/null 2>&1; then
                sha256sum "$path"
            else
                shasum -a 256 "$path"
            fi
        done > SHA256SUMS
    )
    [[ -z "$TEMP_ROOT" ]] || rm -rf "$TEMP_ROOT"
    rmdir "$LOCK_DIRECTORY" 2>/dev/null || true
    printf 'Android machine evidence: %s\n' "$OUTPUT"
    exit "$exit_code"
}
trap finalize EXIT

fail() {
    printf 'android-machine-verify: %s\n' "$*" >&2
    printf 'android-machine-verify: %s\n' "$*" >> "$OUTPUT/logs/gate.log" 2>/dev/null || true
    exit 1
}

run_logged() {
    local log=$1
    shift
    "$@" > >(tee "$log") 2>&1
}

select_timeout() {
    if command -v timeout >/dev/null 2>&1; then
        command -v timeout
    elif command -v gtimeout >/dev/null 2>&1; then
        command -v gtimeout
    else
        return 1
    fi
}

single_artifact() {
    local directory=$1
    local pattern=$2
    local label=$3
    local paths=()
    while IFS= read -r path; do
        [[ -n "$path" ]] && paths+=("$path")
    done < <(find "$directory" -type f -name "$pattern" -print | LC_ALL=C sort)
    ((${#paths[@]} == 1)) || fail "expected exactly one $label, found ${#paths[@]}"
    printf '%s\n' "${paths[0]}"
}

java_property_from_output() {
    local key=$1
    awk -F= -v key="$key" '
        {
            name = $1
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
            if (name == key) {
                sub(/^[^=]*=/, "")
                gsub(/^[[:space:]]+|[[:space:]]+$/, "")
                print
                exit
            }
        }
    '
}

normalize_jdk_home() {
    local candidate=$1
    [[ -d "$candidate" ]] || return 1
    (CDPATH= cd -- "$candidate" && pwd -P)
}

is_complete_jdk_home() {
    local candidate=$1
    [[ -d "$candidate" && -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]
}

java_major_from_ga_version() {
    local version=$1
    if [[ "$version" =~ ^([0-9]+)(\.[0-9]+){0,3}$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi
    return 1
}

is_supported_java_major() {
    local candidate=$1
    local supported
    for supported in "${SUPPORTED_JAVA_MAJORS[@]}"; do
        [[ "$candidate" == "$supported" ]] && return 0
    done
    return 1
}

resolve_build_jdk() {
    local candidate=
    local bootstrap_java=
    local bootstrap_output=
    local bootstrap_home=
    local configured_home=${JAVA_HOME:-}
    local java_output=
    local javac_output=
    local javac_major=
    local reported_home_raw=

    if [[ -n "$configured_home" ]] && is_complete_jdk_home "$configured_home"; then
        candidate=$(normalize_jdk_home "$configured_home") \
            || fail "JAVA_HOME is not a readable JDK directory: $configured_home"
        JAVA_HOME_SOURCE=JAVA_HOME
    else
        bootstrap_java=$(type -P java || true)
        [[ -n "$bootstrap_java" && -x "$bootstrap_java" ]] \
            || fail 'a complete build JDK 17 or 21 is required; set JAVA_HOME or put java on PATH'
        bootstrap_output=$(
            "$bootstrap_java" -XshowSettings:properties -version 2>&1
        ) || fail 'java on PATH could not report java.home'
        bootstrap_home=$(
            printf '%s\n' "$bootstrap_output" | java_property_from_output java.home
        )
        [[ -n "$bootstrap_home" ]] || fail 'java on PATH did not report java.home'
        candidate=$(normalize_jdk_home "$bootstrap_home") \
            || fail "java.home is not a readable directory: $bootstrap_home"
        JAVA_HOME_SOURCE=java.home
    fi

    is_complete_jdk_home "$candidate" \
        || fail "resolved Java home is not a complete JDK with java and javac: $candidate"
    RESOLVED_JAVA_HOME=$candidate
    JAVA_EXECUTABLE="$RESOLVED_JAVA_HOME/bin/java"
    JAVAC_EXECUTABLE="$RESOLVED_JAVA_HOME/bin/javac"

    java_output=$(
        "$JAVA_EXECUTABLE" -XshowSettings:properties -version 2>&1
    ) || fail "selected java could not start: $JAVA_EXECUTABLE"
    javac_output=$(
        "$JAVAC_EXECUTABLE" -version 2>&1
    ) || fail "selected javac could not start: $JAVAC_EXECUTABLE"

    JAVA_VERSION=$(printf '%s\n' "$java_output" | java_property_from_output java.version)
    JAVAC_VERSION=$(printf '%s\n' "$javac_output" | awk '$1 == "javac" { print $2; exit }')
    JAVA_VENDOR=$(printf '%s\n' "$java_output" | java_property_from_output java.vendor)
    JAVA_RUNTIME_NAME=$(printf '%s\n' "$java_output" | java_property_from_output java.runtime.name)
    JAVA_VM_NAME=$(printf '%s\n' "$java_output" | java_property_from_output java.vm.name)
    reported_home_raw=$(printf '%s\n' "$java_output" | java_property_from_output java.home)
    [[ -n "$JAVA_VERSION" && -n "$JAVAC_VERSION" ]] \
        || fail 'selected JDK did not report both java and javac versions'
    [[ -n "$JAVA_VENDOR" && -n "$JAVA_RUNTIME_NAME" && -n "$JAVA_VM_NAME" ]] \
        || fail 'selected JDK did not report complete runtime identity'
    [[ -n "$reported_home_raw" ]] || fail 'selected java did not report java.home'
    JAVA_REPORTED_HOME=$(normalize_jdk_home "$reported_home_raw") \
        || fail "selected java reported an unreadable java.home: $reported_home_raw"
    [[ "$JAVA_REPORTED_HOME" == "$RESOLVED_JAVA_HOME" ]] \
        || fail "selected java reports a different home: $JAVA_REPORTED_HOME"

    JAVA_MAJOR=$(java_major_from_ga_version "$JAVA_VERSION") \
        || fail "java is not an exact GA version: ${JAVA_VERSION:-unknown}"
    javac_major=$(java_major_from_ga_version "$JAVAC_VERSION") \
        || fail "javac is not an exact GA version: ${JAVAC_VERSION:-unknown}"
    [[ "$JAVA_VERSION" == "$JAVAC_VERSION" ]] \
        || fail "java and javac versions differ: $JAVA_VERSION / $JAVAC_VERSION"
    [[ "$JAVA_MAJOR" == "$javac_major" ]] \
        || fail "java and javac majors differ: $JAVA_MAJOR / $javac_major"
    is_supported_java_major "$JAVA_MAJOR" \
        || fail "build JDK major must be 17 or 21 (found $JAVA_MAJOR)"

    export JAVA_HOME="$RESOLVED_JAVA_HOME"
    PATH="$RESOLVED_JAVA_HOME/bin${PATH:+:$PATH}"
    export PATH
    GRADLE_JAVA_HOME_ARGUMENT="-Dorg.gradle.java.home=$RESOLVED_JAVA_HOME"
}

FAILED_PHASE=preflight
for command_name in awk bash cmp date find git grep sed sort tee unzip zipinfo; do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done
command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
    || fail 'sha256sum or shasum is required'
TIMEOUT_TOOL=$(select_timeout) \
    || fail 'timeout (Linux) or gtimeout (macOS coreutils) is required for native-hang containment'

[[ "${SUPPORTED_JAVA_MAJORS[*]}" == '17 21' && "$SUPPORTED_JAVA_MAJORS_CSV" == '17,21' ]] \
    || fail 'build-JDK policy must allow exactly Java 17 and 21'
resolve_build_jdk
GIT_VERSION=$(git --version 2>&1 | awk 'NR == 1 { print }')

if [[ -n "$SDK_ARGUMENT" ]]; then
    SDK_ROOT=$SDK_ARGUMENT
else
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -n "${ANDROID_HOME:-}" && "$ANDROID_SDK_ROOT" != "$ANDROID_HOME" ]]; then
        fail 'ANDROID_SDK_ROOT and ANDROID_HOME disagree; use --sdk to choose explicitly'
    fi
    SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
fi
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || fail \
    'Android SDK not found. Install: platform-tools, platforms;android-36, build-tools;36.0.0, ndk;29.0.14206865, cmake;3.22.1'
SDK_ROOT=$(CDPATH= cd -- "$SDK_ROOT" && pwd)

ADB="$SDK_ROOT/platform-tools/adb"
NDK_ROOT="$SDK_ROOT/ndk/$(property androidNdkVersion)"
CMAKE_ROOT="$SDK_ROOT/cmake/$(property cmakeVersion)"
for required_path in \
    "$ADB" \
    "$SDK_ROOT/platforms/android-$EXPECTED_PLATFORM/android.jar" \
    "$SDK_ROOT/build-tools/$EXPECTED_BUILD_TOOLS/aapt2" \
    "$SDK_ROOT/build-tools/$EXPECTED_BUILD_TOOLS/source.properties" \
    "$NDK_ROOT/source.properties" \
    "$CMAKE_ROOT/source.properties" \
    "$CMAKE_ROOT/bin/cmake"; do
    [[ -e "$required_path" ]] || fail \
        "missing $required_path; sdkmanager packages: platform-tools platforms;android-36 build-tools;36.0.0 ndk;29.0.14206865 cmake;3.22.1"
done
[[ -s "$SDK_ROOT/platforms/android-$EXPECTED_PLATFORM/android.jar" ]] \
    || fail 'the Android platform android.jar is empty'
[[ -x "$ADB" && -x "$CMAKE_ROOT/bin/cmake" && -x "$SDK_ROOT/build-tools/$EXPECTED_BUILD_TOOLS/aapt2" ]] \
    || fail 'adb, aapt2, and pinned CMake must be executable'
NDK_ACTUAL=$(awk -F= '$1 ~ /^[[:space:]]*Pkg.Revision[[:space:]]*$/ { value=$2; gsub(/[[:space:]]/, "", value); print value; exit }' "$NDK_ROOT/source.properties")
BUILD_TOOLS_ACTUAL=$(awk -F= '$1 ~ /^[[:space:]]*Pkg.Revision[[:space:]]*$/ { value=$2; gsub(/[[:space:]]/, "", value); print value; exit }' "$SDK_ROOT/build-tools/$EXPECTED_BUILD_TOOLS/source.properties")
CMAKE_PACKAGE_REVISION=$(awk -F= '$1 ~ /^[[:space:]]*Pkg.Revision[[:space:]]*$/ { value=$2; gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); print value; exit }' "$CMAKE_ROOT/source.properties")
CMAKE_PACKAGE_PATH=$(awk -F= '$1 ~ /^[[:space:]]*Pkg.Path[[:space:]]*$/ { value=$2; gsub(/^[[:space:]]+|[[:space:]]+$/, "", value); print value; exit }' "$CMAKE_ROOT/source.properties")
CMAKE_EXECUTABLE_VERSION=$("$CMAKE_ROOT/bin/cmake" --version 2>&1 | awk 'NR == 1 && $1 == "cmake" && $2 == "version" && NF == 3 { print $3 }')
[[ "$NDK_ACTUAL" == "$(property androidNdkVersion)" ]] \
    || fail "NDK package identity mismatch: ${NDK_ACTUAL:-unknown}"
[[ "$BUILD_TOOLS_ACTUAL" == "$EXPECTED_BUILD_TOOLS" ]] \
    || fail "Build Tools package identity mismatch: ${BUILD_TOOLS_ACTUAL:-unknown}"
[[ "$CMAKE_PACKAGE_REVISION" == "$(property cmakeVersion)" ]] \
    || fail "CMake package revision mismatch: ${CMAKE_PACKAGE_REVISION:-unknown}"
[[ "$CMAKE_PACKAGE_PATH" == "cmake;$(property cmakeVersion)" ]] \
    || fail "CMake package path mismatch: ${CMAKE_PACKAGE_PATH:-unknown}"
[[ "$CMAKE_EXECUTABLE_VERSION" == "$(property cmakeExecutableVersion)" ]] \
    || fail "CMake executable version mismatch: ${CMAKE_EXECUTABLE_VERSION:-unknown}"
ADB_VERSION=$("$ADB" version 2>&1 | awk 'NR == 1 { print; exit }')

if git -C "$REPOSITORY_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    PROJECT_GIT_COMMIT=$(git -C "$REPOSITORY_ROOT" rev-parse HEAD 2>/dev/null || true)
    if [[ -n "$(git -C "$REPOSITORY_ROOT" status --porcelain 2>/dev/null || true)" ]]; then
        PROJECT_GIT_DIRTY=true
    else
        PROJECT_GIT_DIRTY=false
    fi
fi

run_logged "$OUTPUT/logs/android-structure.log" bash "$SCRIPT_DIR/android-validate-structure.sh"
run_logged "$OUTPUT/logs/native-source.log" bash "$SCRIPT_DIR/native-validate-structure.sh" --require-source
GRADLE_JAVA_HOME_FORCED=true
GRADLE_OUTPUT=$(
    ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT" \
        "$ANDROID_ROOT/gradlew" "$GRADLE_JAVA_HOME_ARGUMENT" --version 2>&1
) || fail 'the pinned Gradle wrapper could not start'
printf '%s\n' "$GRADLE_OUTPUT" > "$OUTPUT/logs/gradle-version.log"
GRADLE_VERSION=$(printf '%s\n' "$GRADLE_OUTPUT" | awk '$1 == "Gradle" { print $2; exit }')
[[ "$GRADLE_VERSION" == "$EXPECTED_GRADLE_VERSION" ]] \
    || fail "wrapper resolved Gradle ${GRADLE_VERSION:-unknown}, expected $EXPECTED_GRADLE_VERSION"
GRADLE_LAUNCHER_JVM=$(printf '%s\n' "$GRADLE_OUTPUT" | awk -F: '
    $1 ~ /^[[:space:]]*Launcher JVM[[:space:]]*$/ {
        sub(/^[^:]*:/, "")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "")
        print
        exit
    }
')
[[ -n "$GRADLE_LAUNCHER_JVM" ]] || fail 'Gradle did not report its Launcher JVM'
GRADLE_LAUNCHER_JAVA_VERSION=${GRADLE_LAUNCHER_JVM%%[[:space:]]*}
GRADLE_LAUNCHER_JAVA_MAJOR=$(java_major_from_ga_version "$GRADLE_LAUNCHER_JAVA_VERSION") \
    || fail "Gradle Launcher JVM is not an exact GA version: $GRADLE_LAUNCHER_JVM"
[[ "$GRADLE_LAUNCHER_JAVA_VERSION" == "$JAVA_VERSION" ]] \
    || fail "Gradle Launcher JVM version $GRADLE_LAUNCHER_JAVA_VERSION does not match selected JDK $JAVA_VERSION"
[[ "$GRADLE_LAUNCHER_JAVA_MAJOR" == "$JAVA_MAJOR" ]] \
    || fail "Gradle Launcher JVM major $GRADLE_LAUNCHER_JAVA_MAJOR does not match selected JDK $JAVA_MAJOR"

if [[ "$PREFLIGHT_ONLY" == true ]]; then
    STATUS=preflight-passed
    FAILED_PHASE=
    exit 0
fi

FAILED_PHASE=device-selection
DEVICES=()
while IFS=$'\t ' read -r device_serial device_state _; do
    [[ -n "$device_serial" && "$device_state" == device ]] && DEVICES+=("$device_serial")
done < <("$ADB" devices | sed '1d;/^[[:space:]]*$/d')

if [[ -n "$SERIAL_ARGUMENT" ]]; then
    DEVICE_SERIAL=$SERIAL_ARGUMENT
    DEVICE_STATE=$("$ADB" -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)
    [[ "$DEVICE_STATE" == device ]] || fail "selected adb device is not ready: $DEVICE_SERIAL"
    DEVICE_LIST_CONTAINS_SELECTED=false
    for ready_device in "${DEVICES[@]}"; do
        if [[ "$ready_device" == "$DEVICE_SERIAL" ]]; then
            DEVICE_LIST_CONTAINS_SELECTED=true
            break
        fi
    done
    [[ "$DEVICE_LIST_CONTAINS_SELECTED" == true ]] \
        || fail 'the device selected by --serial is not in the ready-device list'
else
    ((${#DEVICES[@]} > 0)) || fail 'no authorized adb device is connected'
    ((${#DEVICES[@]} == 1)) || fail 'multiple adb devices are connected; choose one with --serial'
    DEVICE_SERIAL=${DEVICES[0]}
fi
export ANDROID_SERIAL=$DEVICE_SERIAL
DEVICE_SERIAL_HASH=$(hash_text "$DEVICE_SERIAL")

adb_prop() {
    "$ADB" -s "$DEVICE_SERIAL" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

[[ "$(adb_prop sys.boot_completed)" == 1 ]] || fail 'selected device has not completed boot'
DEVICE_API=$(adb_prop ro.build.version.sdk)
[[ "$DEVICE_API" =~ ^[0-9]+$ && "$DEVICE_API" -ge "$EXPECTED_MIN_API" ]] \
    || fail "device API must be at least $EXPECTED_MIN_API (found ${DEVICE_API:-unknown})"
DEVICE_ABILIST=$(adb_prop ro.product.cpu.abilist)
[[ -n "$DEVICE_ABILIST" ]] || DEVICE_ABILIST=$(adb_prop ro.product.cpu.abi)
SELECTED_ABI=
IFS=',' read -r -a DEVICE_ABIS <<< "$DEVICE_ABILIST"
for abi in "${DEVICE_ABIS[@]}"; do
    case "$abi" in
        arm64-v8a|x86_64) SELECTED_ABI=$abi; break ;;
    esac
done
[[ -n "$SELECTED_ABI" ]] || fail "device has no supported ABI: $DEVICE_ABILIST"
[[ -z "$REQUIRED_ABI" || "$SELECTED_ABI" == "$REQUIRED_ABI" ]] \
    || fail "device selected ABI $SELECTED_ABI, required $REQUIRED_ABI"

DEVICE_MODEL=$(adb_prop ro.product.model)
DEVICE_MANUFACTURER=$(adb_prop ro.product.manufacturer)
DEVICE_BUILD=$(adb_prop ro.build.id)
DEVICE_SECURITY_PATCH=$(adb_prop ro.build.version.security_patch)
if [[ "$(adb_prop ro.kernel.qemu)" == 1 || "$DEVICE_SERIAL" == emulator-* ]]; then
    DEVICE_KIND=emulator
else
    DEVICE_KIND=physical
    [[ "$ALLOW_PHYSICAL" == true ]] \
        || fail 'physical-device execution requires --allow-physical-device'
fi

FAILED_PHASE=build
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
GRADLE_FLAGS=("$GRADLE_JAVA_HOME_ARGUMENT" --no-daemon --no-parallel --console=plain --stacktrace "--max-workers=$WORKERS")
if ! run_logged "$OUTPUT/logs/build.log" \
    "$ANDROID_ROOT/gradlew" -p "$ANDROID_ROOT" \
    clean :engine:assembleDebug :engine:assembleRelease \
    :app:assembleDebug :app:assembleRelease :engine:assembleDebugAndroidTest \
    "${GRADLE_FLAGS[@]}"; then
    fail 'Android build failed'
fi

ENGINE_DEBUG_AAR=$(single_artifact "$ANDROID_ROOT/engine/build/outputs/aar" 'engine-debug.aar' 'engine debug AAR')
ENGINE_RELEASE_AAR=$(single_artifact "$ANDROID_ROOT/engine/build/outputs/aar" 'engine-release.aar' 'engine release AAR')
APP_DEBUG_APK=$(single_artifact "$ANDROID_ROOT/app/build/outputs/apk/debug" '*.apk' 'app debug APK')
APP_RELEASE_APK=$(single_artifact "$ANDROID_ROOT/app/build/outputs/apk/release" '*.apk' 'app release APK')
ENGINE_TEST_APK=$(single_artifact "$ANDROID_ROOT/engine/build/outputs/apk/androidTest/debug" '*.apk' 'engine test APK')

FAILED_PHASE=artifact-verification
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-android-machine.XXXXXX")
TOOL_SHIMS="$TEMP_ROOT/tool-shims"
mkdir -p "$TOOL_SHIMS"
READELF_TOOL=$(command -v readelf || true)
STRINGS_TOOL=$(command -v strings || true)
for prebuilt_bin in "$NDK_ROOT"/toolchains/llvm/prebuilt/*/bin; do
    [[ -d "$prebuilt_bin" ]] || continue
    [[ -n "$READELF_TOOL" ]] || READELF_TOOL=$prebuilt_bin/llvm-readelf
    [[ -n "$STRINGS_TOOL" ]] || STRINGS_TOOL=$prebuilt_bin/llvm-strings
done
[[ -x "$READELF_TOOL" && -x "$STRINGS_TOOL" ]] \
    || fail 'readelf/strings or the pinned NDK LLVM equivalents are required'
ln -s "$READELF_TOOL" "$TOOL_SHIMS/readelf"
ln -s "$STRINGS_TOOL" "$TOOL_SHIMS/strings"

PATH="$TOOL_SHIMS:$PATH" run_logged "$OUTPUT/logs/aar-debug.log" \
    bash "$SCRIPT_DIR/native-verify-aar.sh" "$ENGINE_DEBUG_AAR" "$OUTPUT/manifests/native-debug.json"
PATH="$TOOL_SHIMS:$PATH" run_logged "$OUTPUT/logs/aar-release.log" \
    bash "$SCRIPT_DIR/native-verify-aar.sh" "$ENGINE_RELEASE_AAR" "$OUTPUT/manifests/native-release.json"
PATH="$TOOL_SHIMS:$PATH" run_logged "$OUTPUT/logs/apk.log" \
    bash "$SCRIPT_DIR/native-verify-apk.sh" \
    "$APP_DEBUG_APK" "$APP_RELEASE_APK" "$ENGINE_TEST_APK" \
    "$ENGINE_DEBUG_AAR" "$ENGINE_RELEASE_AAR" "$SELECTED_ABI" \
    "$OUTPUT/manifests/native-apk.json"
VERIFIED_ARTIFACT_SET_SHA=$(hash_text "$(
    for artifact in "$APP_DEBUG_APK" "$APP_RELEASE_APK" "$ENGINE_TEST_APK" \
        "$ENGINE_DEBUG_AAR" "$ENGINE_RELEASE_AAR"; do
        printf '%s  %s\n' "$(hash_file "$artifact")" "$artifact"
    done
)")

FAILED_PHASE=instrumentation
REPORT_MARKER="$TEMP_ROOT/instrumentation-started"
touch "$REPORT_MARKER"
set +e
ANDROID_SERIAL="$DEVICE_SERIAL" "$TIMEOUT_TOOL" "${INSTRUMENTATION_TIMEOUT_SECONDS}s" \
    "$ANDROID_ROOT/gradlew" -p "$ANDROID_ROOT" :engine:connectedDebugAndroidTest \
    "${GRADLE_FLAGS[@]}" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$INSTRUMENTATION_CLASS" \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
    > >(tee "$OUTPUT/logs/instrumentation.log") 2>&1
INSTRUMENTATION_EXIT=$?
set -e
if ((INSTRUMENTATION_EXIT != 0)); then
    "$ADB" -s "$DEVICE_SERIAL" logcat -d '*:F' > "$OUTPUT/logs/logcat-fatal.log" 2>&1 || true
    fail "instrumentation failed or timed out (exit $INSTRUMENTATION_EXIT)"
fi

MATCHING_REPORTS=()
while IFS= read -r report; do
    if grep -Fq "$INSTRUMENTATION_CLASS" "$report"; then
        MATCHING_REPORTS+=("$report")
    fi
done < <(find "$ANDROID_ROOT/engine/build" -type f -name 'TEST-*.xml' -newer "$REPORT_MARKER" -print | LC_ALL=C sort)
((${#MATCHING_REPORTS[@]} == 1)) \
    || fail "expected one fresh instrumentation XML report, found ${#MATCHING_REPORTS[@]}"
REPORT=${MATCHING_REPORTS[0]}
TESTS=$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' "$REPORT" | head -1)
FAILURES=$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' "$REPORT" | head -1)
ERRORS=$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' "$REPORT" | head -1)
SKIPPED=$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' "$REPORT" | head -1)
[[ "$TESTS" == 1 && "${FAILURES:-0}" == 0 && "${ERRORS:-0}" == 0 && "${SKIPPED:-0}" == 0 ]] \
    || fail "instrumentation XML is not one clean pass: tests=$TESTS failures=$FAILURES errors=$ERRORS skipped=$SKIPPED"
cp "$REPORT" "$OUTPUT/reports/TEST-AndroidFairyEngineInstrumentedTest.xml"
[[ "$("$ADB" -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)" == device ]] \
    || fail 'device disconnected after instrumentation'
CURRENT_ARTIFACT_SET_SHA=$(hash_text "$(
    for artifact in "$APP_DEBUG_APK" "$APP_RELEASE_APK" "$ENGINE_TEST_APK" \
        "$ENGINE_DEBUG_AAR" "$ENGINE_RELEASE_AAR"; do
        printf '%s  %s\n' "$(hash_file "$artifact")" "$artifact"
    done
)")
[[ "$CURRENT_ARTIFACT_SET_SHA" == "$VERIFIED_ARTIFACT_SET_SHA" ]] \
    || fail 'Gradle changed a verified artifact during instrumentation'

RUNTIME_VERIFIED_ABI=$SELECTED_ABI
STATUS=passed
FAILED_PHASE=
printf 'Android machine verification PASS (%s on %s).\n' "$RUNTIME_VERIFIED_ABI" "$DEVICE_KIND"
