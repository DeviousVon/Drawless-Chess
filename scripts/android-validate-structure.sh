#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ANDROID_ROOT="$REPOSITORY_ROOT/android"
ROOT_BUILD="$ANDROID_ROOT/build.gradle.kts"
SETTINGS="$ANDROID_ROOT/settings.gradle.kts"
GRADLE_PROPERTIES="$ANDROID_ROOT/gradle.properties"
APP_BUILD="$ANDROID_ROOT/app/build.gradle.kts"
CORE_BUILD="$ANDROID_ROOT/core/build.gradle.kts"
ENGINE_BUILD="$ANDROID_ROOT/engine/build.gradle.kts"
NATIVE_LOCK="$REPOSITORY_ROOT/engine/native/upstream.properties"
WRAPPER_PROPERTIES="$ANDROID_ROOT/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="$ANDROID_ROOT/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_JAR_CHECKSUM="$ANDROID_ROOT/gradle/wrapper/gradle-wrapper.jar.sha256"
MACHINE_GATE="$SCRIPT_DIR/android-machine-verify.sh"
WINDOWS_MACHINE_GATE="$SCRIPT_DIR/android-machine-verify.ps1"
WINDOWS_NATIVE_FETCH="$SCRIPT_DIR/native-fetch-fairy.ps1"
NATIVE_FETCH="$SCRIPT_DIR/native-fetch-fairy.sh"
APK_GATE="$SCRIPT_DIR/native-verify-apk.sh"
INSTRUMENTED_TEST="$ANDROID_ROOT/engine/src/androidTest/kotlin/com/drawlesschess/engine/AndroidFairyEngineInstrumentedTest.kt"
PACKAGE_JSON="$REPOSITORY_ROOT/package.json"
GIT_ATTRIBUTES="$REPOSITORY_ROOT/.gitattributes"

# AGP 9.2's stable compatibility floor and this checkpoint's deliberately stable SDK.
EXPECTED_AGP_VERSION=9.2.1
EXPECTED_GRADLE_VERSION=9.4.1
EXPECTED_COMPOSE_PLUGIN_VERSION=2.3.10
EXPECTED_COMPILE_SDK=36
EXPECTED_TARGET_SDK=36
EXPECTED_MIN_SDK=26
EXPECTED_BUILD_TOOLS_VERSION=36.0.0
EXPECTED_NDK_VERSION=29.0.14206865
EXPECTED_CMAKE_VERSION=3.22.1
EXPECTED_CMAKE_EXECUTABLE_VERSION=3.22.1-g37088a8-dirty
EXPECTED_DISTRIBUTION_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
EXPECTED_WRAPPER_JAR_SHA256=55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c

die() {
    printf 'android-validate-structure: %s\n' "$*" >&2
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

require_property() {
    local file=$1
    local key=$2
    local expected=$3
    local values
    values=$(awk -F= -v key="$key" '
        $1 == key {
            sub(/^[^=]*=/, "")
            sub(/\r$/, "")
            print
        }
    ' "$file")
    [[ "$values" == "$expected" ]] \
        || die "$file must set $key=$expected exactly once; found: ${values:-none}"
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

require_text_count() {
    local file=$1
    local text=$2
    local expected_count=$3
    local actual_count
    actual_count=$(grep -Fc -- "$text" "$file" || true)
    [[ "$actual_count" == "$expected_count" ]] \
        || die "$file must contain '$text' exactly $expected_count time(s); found $actual_count"
}

require_text_min_count() {
    local file=$1
    local text=$2
    local minimum_count=$3
    local actual_count
    actual_count=$(grep -Fc -- "$text" "$file" || true)
    [[ "$actual_count" -ge "$minimum_count" ]] \
        || die "$file must contain '$text' at least $minimum_count time(s); found $actual_count"
}

reject_text() {
    local file=$1
    local text=$2
    if grep -Fq -- "$text" "$file"; then
        die "$file contains forbidden text: $text"
    fi
}

reject_pattern() {
    local file=$1
    local pattern=$2
    if grep -Eq -- "$pattern" "$file"; then
        die "$file contains forbidden pattern: $pattern"
    fi
}

require_single_numeric_assignment() {
    local file=$1
    local key=$2
    local expected=$3
    local values
    values=$(awk -v key="$key" '
        $1 == key && $2 == "=" {
            value = $3
            sub(/\r$/, "", value)
            print value
        }
    ' "$file")
    [[ "$values" == "$expected" ]] \
        || die "$file must assign $key = $expected exactly once; found: ${values:-none}"
}

require_single_quoted_assignment() {
    local file=$1
    local key=$2
    local expected=$3
    local values
    values=$(awk -v key="$key" '
        $1 == key && $2 == "=" {
            value = $3
            sub(/\r$/, "", value)
            gsub(/^"|"$/, "", value)
            print value
        }
    ' "$file")
    [[ "$values" == "$expected" ]] \
        || die "$file must assign $key = \"$expected\" exactly once; found: ${values:-none}"
}

require_lf_bytes() {
    local file=$1
    if LC_ALL=C grep -q $'\r' "$file"; then
        die "$file must contain LF line endings only"
    fi
}

require_crlf_lines() {
    local file=$1
    local bytes
    local without_crlf
    bytes=$(LC_ALL=C od -An -v -t x1 "$file" | tr -d '[:space:]')
    [[ -n "$bytes" && "$bytes" == *0d0a* ]] \
        || die "$file must contain CRLF line endings"
    without_crlf=${bytes//0d0a/}
    [[ "$without_crlf" != *0a* && "$without_crlf" != *0d* ]] \
        || die "$file must contain CRLF line endings"
}

for required_file in \
    "$ROOT_BUILD" \
    "$SETTINGS" \
    "$GRADLE_PROPERTIES" \
    "$APP_BUILD" \
    "$CORE_BUILD" \
    "$ENGINE_BUILD" \
    "$NATIVE_LOCK" \
    "$ANDROID_ROOT/gradlew" \
    "$ANDROID_ROOT/gradlew.bat" \
    "$WRAPPER_PROPERTIES" \
    "$WRAPPER_JAR" \
    "$WRAPPER_JAR_CHECKSUM" \
    "$MACHINE_GATE" \
    "$WINDOWS_MACHINE_GATE" \
    "$WINDOWS_NATIVE_FETCH" \
    "$NATIVE_FETCH" \
    "$APK_GATE" \
    "$INSTRUMENTED_TEST" \
    "$PACKAGE_JSON" \
    "$GIT_ATTRIBUTES"; do
    [[ -f "$required_file" ]] || die "missing required file: $required_file"
done

# Several native identities are hashes of exact bytes. These attributes and working-tree
# checks prevent a Windows checkout from silently rewriting those inputs before Gradle
# verifies or packages them.
require_text_count "$GIT_ATTRIBUTES" '/engine/patches/** text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/engine/variants.ini text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/engine/native/*.properties text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/engine/native/*.txt text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/scripts/*.sh text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/scripts/*.ps1 text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/android/gradlew text eol=lf' 1
require_text_count "$GIT_ATTRIBUTES" '/android/gradlew.bat text eol=crlf' 1
while IFS= read -r byte_locked_file; do
    require_lf_bytes "$byte_locked_file"
done < <(
    find "$REPOSITORY_ROOT/engine/patches" -type f -print
    printf '%s\n' "$REPOSITORY_ROOT/engine/variants.ini"
    find "$REPOSITORY_ROOT/engine/native" -maxdepth 1 -type f \
        \( -name '*.properties' -o -name '*.txt' \) -print
    find "$SCRIPT_DIR" -maxdepth 1 -type f \
        \( -name '*.sh' -o -name '*.ps1' \) -print
    printf '%s\n' "$ANDROID_ROOT/gradlew"
)
require_crlf_lines "$ANDROID_ROOT/gradlew.bat"

# Windows-native source preparation is deliberately explicit and fail-closed. It must
# preserve the byte identities and Git tree identities used by the Android native gate.
require_text_count "$WINDOWS_NATIVE_FETCH" '#requires -Version 7.0' 1
require_text "$WINDOWS_NATIVE_FETCH" 'Set-StrictMode -Version Latest'
require_text "$WINDOWS_NATIVE_FETCH" '$ErrorActionPreference = '\''Stop'\'''
require_text_count "$WINDOWS_NATIVE_FETCH" 'https://github.com/fairy-stockfish/Fairy-Stockfish.git' 1
require_text "$WINDOWS_NATIVE_FETCH" "Get-LockedProperty -Path \$lockFile -Name revision"
require_text "$WINDOWS_NATIVE_FETCH" "Get-LockedProperty -Path \$lockFile -Name tree"
require_text "$WINDOWS_NATIVE_FETCH" "Get-LockedProperty -Path \$lockFile -Name patchedTree"
require_text "$WINDOWS_NATIVE_FETCH" "Get-LockedProperty -Path \$lockFile -Name patchSeriesSha256"
require_text "$WINDOWS_NATIVE_FETCH" "'core.autocrlf', 'false'"
require_text "$WINDOWS_NATIVE_FETCH" "'core.filemode', 'false'"
require_text "$WINDOWS_NATIVE_FETCH" 'destination already exists; validate it or remove it deliberately'
require_text "$WINDOWS_NATIVE_FETCH" 'fetched revision mismatch'
require_text "$WINDOWS_NATIVE_FETCH" 'fetched tree mismatch'
require_text "$WINDOWS_NATIVE_FETCH" 'patch series SHA-256 mismatch'
require_text "$WINDOWS_NATIVE_FETCH" 'patched tree mismatch'
require_text "$WINDOWS_NATIVE_FETCH" "'fetch', '--quiet', '--depth', '1', '--no-tags', 'origin', \$revision"
require_text "$WINDOWS_NATIVE_FETCH" "'apply', '--check', '--index', \$patchFile"
require_text "$WINDOWS_NATIVE_FETCH" "'apply', '--index', \$patchFile"

# A source checkout prepared on Unix may later be packaged for Windows. Keep its local Git
# behavior platform-neutral so Gradle/CMake's direct Git calls cannot report false drift.
require_text "$NATIVE_FETCH" 'git -C "$TEMP_CHECKOUT" config core.autocrlf false'
require_text "$NATIVE_FETCH" 'git -C "$TEMP_CHECKOUT" config core.eol lf'
require_text "$NATIVE_FETCH" 'git -C "$TEMP_CHECKOUT" config core.filemode false'
require_text "$WINDOWS_NATIVE_FETCH" 'Get-Command git.exe -CommandType Application'
require_text "$WINDOWS_NATIVE_FETCH" 'Get-Command git -CommandType Application'
require_text "$WINDOWS_NATIVE_FETCH" '$gitExecutable = $gitCommand.Source'
require_text_min_count "$WINDOWS_NATIVE_FETCH" 'Invoke-Native -Executable $gitExecutable' 10
reject_pattern "$WINDOWS_NATIVE_FETCH" 'Invoke-Native -Executable git([[:space:]]|$)'
require_text "$WINDOWS_NATIVE_FETCH" 'destination appeared before publication'
require_text "$WINDOWS_NATIVE_FETCH" '[System.IO.Directory]::Move($temporaryCheckout, $destinationPath)'
require_text "$WINDOWS_NATIVE_FETCH" 'could not atomically publish source'
reject_text "$WINDOWS_NATIVE_FETCH" 'Move-Item -LiteralPath $temporaryCheckout -Destination $destinationPath'

[[ -x "$ANDROID_ROOT/gradlew" ]] || die "android/gradlew must retain its executable bit"
[[ -x "$MACHINE_GATE" ]] || die "scripts/android-machine-verify.sh must retain its executable bit"
[[ -x "$APK_GATE" ]] || die "scripts/native-verify-apk.sh must retain its executable bit"
[[ -s "$WRAPPER_JAR" ]] || die "Gradle wrapper JAR is empty"
require_text "$ANDROID_ROOT/gradlew" 'gradle/wrapper/gradle-wrapper.jar'
require_text "$ANDROID_ROOT/gradlew.bat" 'gradle\wrapper\gradle-wrapper.jar'

ACTUAL_WRAPPER_JAR_SHA256=$(hash_file "$WRAPPER_JAR")
[[ "$ACTUAL_WRAPPER_JAR_SHA256" == "$EXPECTED_WRAPPER_JAR_SHA256" ]] \
    || die "Gradle wrapper JAR checksum drifted: $ACTUAL_WRAPPER_JAR_SHA256"
read -r DECLARED_WRAPPER_JAR_SHA256 DECLARED_WRAPPER_JAR_NAME DECLARED_WRAPPER_JAR_EXTRA \
    < "$WRAPPER_JAR_CHECKSUM" || die "could not parse Gradle wrapper JAR checksum file"
[[ "$DECLARED_WRAPPER_JAR_SHA256" == "$EXPECTED_WRAPPER_JAR_SHA256" ]] \
    || die "Gradle wrapper JAR checksum sidecar does not match the locked checksum"
[[ "$DECLARED_WRAPPER_JAR_NAME" == gradle-wrapper.jar ]] \
    || die "Gradle wrapper JAR checksum sidecar names the wrong file"
[[ -z "${DECLARED_WRAPPER_JAR_EXTRA:-}" ]] \
    || die "Gradle wrapper JAR checksum sidecar contains unexpected fields"

EXPECTED_DISTRIBUTION_URL="https\\://services.gradle.org/distributions/gradle-${EXPECTED_GRADLE_VERSION}-bin.zip"
require_property "$WRAPPER_PROPERTIES" distributionBase GRADLE_USER_HOME
require_property "$WRAPPER_PROPERTIES" distributionPath wrapper/dists
require_property "$WRAPPER_PROPERTIES" distributionUrl "$EXPECTED_DISTRIBUTION_URL"
require_property "$WRAPPER_PROPERTIES" distributionSha256Sum "$EXPECTED_DISTRIBUTION_SHA256"
require_property "$WRAPPER_PROPERTIES" validateDistributionUrl true
require_property "$WRAPPER_PROPERTIES" zipStoreBase GRADLE_USER_HOME
require_property "$WRAPPER_PROPERTIES" zipStorePath wrapper/dists
WRAPPER_NETWORK_TIMEOUT=$(property_from "$WRAPPER_PROPERTIES" networkTimeout)
require_property "$WRAPPER_PROPERTIES" networkTimeout "$WRAPPER_NETWORK_TIMEOUT"
[[ "$WRAPPER_NETWORK_TIMEOUT" =~ ^[0-9]+$ && "$WRAPPER_NETWORK_TIMEOUT" -ge 10000 ]] \
    || die "Gradle wrapper networkTimeout must be at least 10000 milliseconds"

require_text_count "$ROOT_BUILD" "id(\"com.android.application\") version \"$EXPECTED_AGP_VERSION\" apply false" 1
require_text_count "$ROOT_BUILD" "id(\"com.android.library\") version \"$EXPECTED_AGP_VERSION\" apply false" 1
require_text_count "$ROOT_BUILD" "id(\"org.jetbrains.kotlin.plugin.compose\") version \"$EXPECTED_COMPOSE_PLUGIN_VERSION\" apply false" 1
reject_pattern "$ROOT_BUILD" 'buildscript[[:space:]]*\{'
reject_text "$ROOT_BUILD" 'org.jetbrains.kotlin:kotlin-gradle-plugin'
reject_text "$GRADLE_PROPERTIES" 'android.builtInKotlin=false'
reject_text "$GRADLE_PROPERTIES" 'android.newDsl=false'
require_text "$GRADLE_PROPERTIES" 'android.useAndroidX=true'
for module_build in "$APP_BUILD" "$CORE_BUILD" "$ENGINE_BUILD"; do
    reject_text "$module_build" 'org.jetbrains.kotlin.android'
    reject_text "$module_build" 'kotlin-android'
    reject_text "$module_build" 'kotlin("android")'
    reject_text "$module_build" 'compileSdkPreview'
    reject_text "$module_build" 'targetSdkPreview'
    require_single_numeric_assignment "$module_build" compileSdk "$EXPECTED_COMPILE_SDK"
    require_single_quoted_assignment "$module_build" buildToolsVersion "$EXPECTED_BUILD_TOOLS_VERSION"
    require_text_count "$module_build" 'sourceCompatibility = JavaVersion.VERSION_17' 1
    require_text_count "$module_build" 'targetCompatibility = JavaVersion.VERSION_17' 1
    reject_text "$module_build" 'sourceCompatibility = JavaVersion.VERSION_21'
    reject_text "$module_build" 'targetCompatibility = JavaVersion.VERSION_21'
done

require_single_numeric_assignment "$APP_BUILD" minSdk "$EXPECTED_MIN_SDK"
require_single_numeric_assignment "$APP_BUILD" targetSdk "$EXPECTED_TARGET_SDK"
require_text "$APP_BUILD" 'abiFilters += listOf("arm64-v8a", "x86_64")'
require_text "$APP_BUILD" 'ndkVersion = nativePin("androidNdkVersion")'
require_single_numeric_assignment "$CORE_BUILD" minSdk "$EXPECTED_MIN_SDK"
require_text "$ENGINE_BUILD" 'minSdk = nativePin("androidMinSdk").toInt()'
[[ "$(property_from "$NATIVE_LOCK" androidMinSdk)" == "$EXPECTED_MIN_SDK" ]] \
    || die "native minimum SDK drifted from $EXPECTED_MIN_SDK"
[[ "$(property_from "$NATIVE_LOCK" androidNdkVersion)" == "$EXPECTED_NDK_VERSION" ]] \
    || die "native NDK pin drifted from $EXPECTED_NDK_VERSION"
require_property "$NATIVE_LOCK" cmakeVersion "$EXPECTED_CMAKE_VERSION"
require_property "$NATIVE_LOCK" cmakeExecutableVersion "$EXPECTED_CMAKE_EXECUTABLE_VERSION"
require_text "$ENGINE_BUILD" 'ndkVersion = nativePin("androidNdkVersion")'
require_text "$ENGINE_BUILD" 'version = nativePin("cmakeVersion")'

require_text "$ENGINE_BUILD" 'abstract class GenerateFairyLegalAssetsTask : Sync()'
require_text "$ENGINE_BUILD" 'abstract val outputDirectory: DirectoryProperty'
require_text "$ENGINE_BUILD" 'outputDirectory.set(legalAssetsDirectory)'
require_text "$ENGINE_BUILD" 'androidComponents {'
require_text "$ENGINE_BUILD" 'assets.addGeneratedSourceDirectory('
require_text "$ENGINE_BUILD" 'GenerateFairyLegalAssetsTask::outputDirectory'
reject_text "$ENGINE_BUILD" 'sourceSets.getByName('
reject_text "$ENGINE_BUILD" 'sourceSets.named('
reject_text "$ENGINE_BUILD" 'assets.srcDir(legalAssetsDirectory)'
reject_text "$ENGINE_BUILD" 'tasks.named("preBuild")'
reject_text "$GRADLE_PROPERTIES" 'android.sourceset.disallowProvider=false'

require_text "$SETTINGS" 'google()'
require_text "$SETTINGS" 'mavenCentral()'
require_text "$SETTINGS" 'include(":app", ":core", ":engine")'
require_text "$APP_BUILD" 'id("org.jetbrains.kotlin.plugin.compose")'
require_text "$APP_BUILD" 'compose = true'

# The SDK-free gate must retain a real Android test and a machine lane that executes it.
require_text "$ENGINE_BUILD" 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"'
require_text "$ENGINE_BUILD" 'androidTestImplementation("androidx.test:core:1.7.0")'
require_text "$ENGINE_BUILD" 'androidTestImplementation("androidx.test:runner:1.7.0")'
require_text "$ENGINE_BUILD" 'androidTestImplementation("androidx.test.ext:junit:1.3.0")'
require_text "$INSTRUMENTED_TEST" '@RunWith(AndroidJUnit4::class)'
require_text "$INSTRUMENTED_TEST" '@Test'
require_text "$INSTRUMENTED_TEST" 'fun forcedRepetitionSearchClosesAndRestartsSequentially()'
require_text "$INSTRUMENTED_TEST" 'val first = factory.create()'
require_text "$INSTRUMENTED_TEST" 'val second = factory.create()'
require_text "$INSTRUMENTED_TEST" 'h8g8'

require_text "$APK_GATE" 'Usage: scripts/native-verify-apk.sh APP_DEBUG.apk APP_RELEASE.apk ENGINE_TEST.apk ENGINE_DEBUG.aar ENGINE_RELEASE.aar TEST_ABI [MANIFEST.json]'
require_text "$APK_GATE" 'ENGINE_DEBUG_AAR=$4'
require_text "$APK_GATE" 'ENGINE_RELEASE_AAR=$5'
require_text_count "$APK_GATE" 'bash "$SCRIPT_DIR/native-verify-aar.sh"' 2
require_text "$APK_GATE" 'compare_library "$APP_DEBUG" "$app_path" "$ENGINE_DEBUG_AAR"'
require_text "$APK_GATE" 'compare_library "$APP_RELEASE" "$app_path" "$ENGINE_RELEASE_AAR"'
require_text "$APK_GATE" 'libandroidx.graphics.path.so'

require_text "$MACHINE_GATE" "platforms;android-$EXPECTED_COMPILE_SDK"
require_text "$MACHINE_GATE" "build-tools;$EXPECTED_BUILD_TOOLS_VERSION"
require_text "$MACHINE_GATE" ':engine:connectedDebugAndroidTest'
require_text "$MACHINE_GATE" ':engine:assembleRelease'
require_text "$MACHINE_GATE" ':app:assembleDebug'
require_text "$MACHINE_GATE" 'native-verify-aar.sh'
require_text "$MACHINE_GATE" 'native-verify-apk.sh'
require_text "$MACHINE_GATE" 'engine-debug.aar'
require_text "$MACHINE_GATE" 'engine-release.aar'

# The Bash lane resolves one complete GA JDK and permits exactly the two Gradle-runtime
# majors supported by this checkpoint. Android source/target compatibility remains 17.
require_text_count "$MACHINE_GATE" 'PROJECT_JAVA_COMPATIBILITY=17' 1
require_text_count "$MACHINE_GATE" 'SUPPORTED_JAVA_MAJORS=(17 21)' 1
require_text_count "$MACHINE_GATE" 'SUPPORTED_JAVA_MAJORS_CSV=17,21' 1
reject_text "$MACHINE_GATE" 'EXPECTED_JAVA_MAJOR'
require_text "$MACHINE_GATE" 'java_major_from_ga_version() {'
require_text "$MACHINE_GATE" 'if [[ "$version" =~ ^([0-9]+)(\.[0-9]+){0,3}$ ]]'
require_text "$MACHINE_GATE" 'is_supported_java_major() {'
require_text "$MACHINE_GATE" 'resolve_build_jdk() {'
require_text "$MACHINE_GATE" 'bootstrap_java=$(type -P java || true)'
require_text "$MACHINE_GATE" '"$bootstrap_java" -XshowSettings:properties -version'
require_text "$MACHINE_GATE" 'RESOLVED_JAVA_HOME=$candidate'
require_text "$MACHINE_GATE" 'JAVA_EXECUTABLE="$RESOLVED_JAVA_HOME/bin/java"'
require_text "$MACHINE_GATE" 'JAVAC_EXECUTABLE="$RESOLVED_JAVA_HOME/bin/javac"'
require_text "$MACHINE_GATE" '"$JAVA_EXECUTABLE" -XshowSettings:properties -version'
require_text "$MACHINE_GATE" '"$JAVAC_EXECUTABLE" -version'
require_text "$MACHINE_GATE" '[[ "$JAVA_REPORTED_HOME" == "$RESOLVED_JAVA_HOME" ]]'
require_text "$MACHINE_GATE" '[[ "$JAVA_VERSION" == "$JAVAC_VERSION" ]]'
require_text "$MACHINE_GATE" '[[ "$JAVA_MAJOR" == "$javac_major" ]]'
require_text "$MACHINE_GATE" 'is_supported_java_major "$JAVA_MAJOR"'
require_text "$MACHINE_GATE" 'java is not an exact GA version'
require_text "$MACHINE_GATE" 'javac is not an exact GA version'
require_text "$MACHINE_GATE" 'build JDK major must be 17 or 21'
require_text "$MACHINE_GATE" 'export JAVA_HOME="$RESOLVED_JAVA_HOME"'
require_text "$MACHINE_GATE" 'PATH="$RESOLVED_JAVA_HOME/bin${PATH:+:$PATH}"'
reject_pattern "$MACHINE_GATE" 'JAVA_VERSION=\$\(java[[:space:]]'
reject_pattern "$MACHINE_GATE" 'JAVAC_VERSION=\$\(javac[[:space:]]'

# JAVA_HOME and org.gradle.java.home are forced on all three wrapper invocations. The
# launcher-reported exact version and major must match before preflight can pass.
require_text "$MACHINE_GATE" 'GRADLE_JAVA_HOME_ARGUMENT="-Dorg.gradle.java.home=$RESOLVED_JAVA_HOME"'
require_text "$MACHINE_GATE" '"$ANDROID_ROOT/gradlew" "$GRADLE_JAVA_HOME_ARGUMENT" --version'
require_text "$MACHINE_GATE" 'GRADLE_FLAGS=("$GRADLE_JAVA_HOME_ARGUMENT"'
require_text_count "$MACHINE_GATE" '"${GRADLE_FLAGS[@]}"' 2
require_text "$MACHINE_GATE" 'Launcher JVM[[:space:]]*$/'
require_text "$MACHINE_GATE" 'GRADLE_LAUNCHER_JAVA_MAJOR=$(java_major_from_ga_version "$GRADLE_LAUNCHER_JAVA_VERSION")'
require_text "$MACHINE_GATE" '[[ "$GRADLE_LAUNCHER_JAVA_VERSION" == "$JAVA_VERSION" ]]'
require_text "$MACHINE_GATE" '[[ "$GRADLE_LAUNCHER_JAVA_MAJOR" == "$JAVA_MAJOR" ]]'
require_text "$MACHINE_GATE" 'Gradle Launcher JVM version'
require_text "$MACHINE_GATE" 'Gradle Launcher JVM major'
require_text "$MACHINE_GATE" '"supportedJavaMajors":[17,21]'
require_text "$MACHINE_GATE" '"androidSourceCompatibility":%s,"androidTargetCompatibility":%s'
require_text "$MACHINE_GATE" '"gradleLauncherJavaVersion":%s,"gradleLauncherJavaMajor":%s'
require_text "$MACHINE_GATE" '"gradleJavaHome":%s,"gradleJavaHomeForced":%s'
require_text_count "$MACHINE_GATE" 'GRADLE_JAVA_HOME_FORCED=false' 1
require_text_count "$MACHINE_GATE" 'GRADLE_JAVA_HOME_FORCED=true' 1
require_text_count "$MACHINE_GATE" '"finishedAt"' 1
require_text "$MACHINE_GATE" '"cmakePackageRevision":%s,"cmakePackagePath":%s,"cmakeExecutableVersion":%s'
require_text "$MACHINE_GATE" '"$CMAKE_ROOT/source.properties"'
require_text "$MACHINE_GATE" 'CMAKE_PACKAGE_REVISION=$(awk'
require_text "$MACHINE_GATE" 'CMAKE_PACKAGE_PATH=$(awk'
require_text "$MACHINE_GATE" 'CMAKE_EXECUTABLE_VERSION=$("$CMAKE_ROOT/bin/cmake" --version'
require_text "$MACHINE_GATE" '[[ "$CMAKE_PACKAGE_REVISION" == "$(property cmakeVersion)" ]]'
require_text "$MACHINE_GATE" '[[ "$CMAKE_PACKAGE_PATH" == "cmake;$(property cmakeVersion)" ]]'
require_text "$MACHINE_GATE" '[[ "$CMAKE_EXECUTABLE_VERSION" == "$(property cmakeExecutableVersion)" ]]'
reject_text "$MACHINE_GATE" '[[ "$CMAKE_ACTUAL" == "$(property cmakeVersion)" ]]'
require_text_count "$MACHINE_GATE" 'selected JDK did not report complete runtime identity' 1
BASH_LAUNCHER_ASSERT_LINE=$(grep -nF '[[ "$GRADLE_LAUNCHER_JAVA_MAJOR" == "$JAVA_MAJOR" ]]' "$MACHINE_GATE" | cut -d: -f1)
BASH_PREFLIGHT_EXIT_LINE=$(grep -nF 'if [[ "$PREFLIGHT_ONLY" == true ]]; then' "$MACHINE_GATE" | cut -d: -f1)
BASH_FORCE_HOME_LINE=$(grep -nF 'GRADLE_JAVA_HOME_FORCED=true' "$MACHINE_GATE" | cut -d: -f1)
BASH_FIRST_WRAPPER_LINE=$(grep -nF '"$ANDROID_ROOT/gradlew" "$GRADLE_JAVA_HOME_ARGUMENT" --version' "$MACHINE_GATE" | cut -d: -f1)
[[ "$BASH_LAUNCHER_ASSERT_LINE" =~ ^[0-9]+$ && "$BASH_PREFLIGHT_EXIT_LINE" =~ ^[0-9]+$ && \
   "$BASH_FORCE_HOME_LINE" =~ ^[0-9]+$ && "$BASH_FIRST_WRAPPER_LINE" =~ ^[0-9]+$ && \
   "$BASH_FORCE_HOME_LINE" -lt "$BASH_FIRST_WRAPPER_LINE" && \
   "$BASH_LAUNCHER_ASSERT_LINE" -lt "$BASH_PREFLIGHT_EXIT_LINE" ]] \
    || die "$MACHINE_GATE must assert the Gradle Launcher JVM before preflight succeeds"

# This check intentionally runs without PowerShell so Linux CI can catch Windows-lane
# contract drift. Counts above one keep operational uses from being satisfied solely by
# the PowerShell gate's own source-structure assertions.
require_text_count "$WINDOWS_MACHINE_GATE" '#requires -Version 7.0' 1
require_text "$WINDOWS_MACHINE_GATE" 'Set-StrictMode -Version Latest'
require_text "$WINDOWS_MACHINE_GATE" '$ErrorActionPreference = '\''Stop'\'''
require_text "$WINDOWS_MACHINE_GATE" "[ValidateSet('arm64-v8a', 'x86_64')]"
require_text "$WINDOWS_MACHINE_GATE" '[ValidateRange(1, 8)]'
require_text "$WINDOWS_MACHINE_GATE" '[switch]$AllowPhysicalDevice'
require_text "$WINDOWS_MACHINE_GATE" '[switch]$PreflightOnly'
require_text_count "$WINDOWS_MACHINE_GATE" '$ProjectJavaCompatibility = 17' 1
reject_text "$WINDOWS_MACHINE_GATE" '$ExpectedJavaMajor'
require_text_count "$WINDOWS_MACHINE_GATE" '$SupportedJavaMajors = @(17, 21)' 1
require_text "$WINDOWS_MACHINE_GATE" "if ((\$SupportedJavaMajors -join ',') -ne '17,21')"
require_text "$WINDOWS_MACHINE_GATE" 'Windows build-JDK policy must allow exactly Java 17 and 21'
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedGradleVersion = '\''9.4.1'\''' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedAgpVersion = '\''9.2.1'\''' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedComposePluginVersion = '\''2.3.10'\''' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedPlatform = 36' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedBuildTools = '\''36.0.0'\''' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$ExpectedMinApi = 26' 1
require_text_count "$WINDOWS_MACHINE_GATE" '$InstrumentationTimeoutSeconds = 240' 1
require_text "$WINDOWS_MACHINE_GATE" '[System.Runtime.InteropServices.OSPlatform]::Windows'
require_text "$WINDOWS_MACHINE_GATE" 'this companion gate requires Windows and PowerShell 7'

# PowerShell/.NET and pinned Windows executables are the only implementation lane. The
# cmd.exe exception is scoped to launching gradlew.bat; its /C payload stays in separate
# ArgumentList entries so .NET cannot turn embedded quotes into literal backslash-quotes.
reject_text "$WINDOWS_MACHINE_GATE" "'bash.exe'"
reject_text "$WINDOWS_MACHINE_GATE" "'wsl.exe'"
reject_text "$WINDOWS_MACHINE_GATE" 'Invoke-Expression'
reject_text "$WINDOWS_MACHINE_GATE" 'Start-Process'
reject_text "$WINDOWS_MACHINE_GATE" 'Invoke-WebRequest'
reject_text "$WINDOWS_MACHINE_GATE" 'Start-BitsTransfer'
reject_text "$WINDOWS_MACHINE_GATE" "'sdkmanager'"
reject_text "$WINDOWS_MACHINE_GATE" "'installDebug'"
reject_text "$WINDOWS_MACHINE_GATE" "'bundleRelease'"
require_text "$WINDOWS_MACHINE_GATE" '[System.Diagnostics.ProcessStartInfo]::new()'
require_text "$WINDOWS_MACHINE_GATE" '$startInfo.UseShellExecute = $false'
require_text "$WINDOWS_MACHINE_GATE" '$startInfo.ArgumentList.Add([string]$argument)'
require_text "$WINDOWS_MACHINE_GATE" '$process.Kill($true)'
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$AndroidRoot 'gradlew.bat'"

# Toolchain and source identity must be fully established before either build mode.
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$jdkHome 'bin/java.exe'"
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$jdkHome 'bin/javac.exe'"
require_text "$WINDOWS_MACHINE_GATE" "'Android/Android Studio/jbr'"
require_text "$WINDOWS_MACHINE_GATE" 'function Get-JavaMajor {'
require_text "$WINDOWS_MACHINE_GATE" '[System.IO.Path]::TrimEndingDirectorySeparator('
require_text "$WINDOWS_MACHINE_GATE" '$javaMajor = Get-JavaMajor $javaVersion'
require_text "$WINDOWS_MACHINE_GATE" '$javacMajor = Get-JavaMajor $javacVersion'
require_text "$WINDOWS_MACHINE_GATE" '$javaVersion -cne $javaMatch.Groups[1].Value'
require_text "$WINDOWS_MACHINE_GATE" '$javaVersion -cne $javacVersion'
require_text "$WINDOWS_MACHINE_GATE" '$javaVersion -notmatch '\''^\d+(?:\.\d+)*$'\'''
require_text "$WINDOWS_MACHINE_GATE" '$javaMajor -ne $javacMajor'
require_text "$WINDOWS_MACHINE_GATE" '$javaMajor -notin $SupportedJavaMajors'
require_text "$WINDOWS_MACHINE_GATE" '$reportedHome.Equals($jdkHome, [StringComparison]::OrdinalIgnoreCase)'
require_text "$WINDOWS_MACHINE_GATE" 'complete build JDK with matching java.exe/javac.exe and major 17 or 21'
require_text "$WINDOWS_MACHINE_GATE" 'a complete build JDK 17 or 21 is required'
require_text "$WINDOWS_MACHINE_GATE" 'javaMajor = $script:State.JavaMajor'
require_text "$WINDOWS_MACHINE_GATE" 'supportedJavaMajors = [object[]]$SupportedJavaMajors'
require_text "$WINDOWS_MACHINE_GATE" 'androidSourceCompatibility = $ProjectJavaCompatibility'
require_text "$WINDOWS_MACHINE_GATE" 'androidTargetCompatibility = $ProjectJavaCompatibility'
require_text "$WINDOWS_MACHINE_GATE" 'gradleLauncherJavaVersion = $script:State.GradleLauncherJavaVersion'
require_text "$WINDOWS_MACHINE_GATE" 'gradleLauncherJavaMajor = $script:State.GradleLauncherJavaMajor'
require_text "$WINDOWS_MACHINE_GATE" 'gradleJavaHome = $script:State.JavaHome'
require_text "$WINDOWS_MACHINE_GATE" 'gradleJavaHomeForced = $script:State.GradleJavaHomeForced'
require_text "$WINDOWS_MACHINE_GATE" 'function Assert-CmdArgumentSafe {'
require_text "$WINDOWS_MACHINE_GATE" '$gradleJavaHomeArgument = "-Dorg.gradle.java.home=$($script:State.JavaHome)"'
require_text "$WINDOWS_MACHINE_GATE" "foreach (\$cmdOption in @('/D', '/V:OFF', '/S', '/C', 'call'))"
require_text "$WINDOWS_MACHINE_GATE" 'Assert-CmdArgumentSafe $cmdArgument'
require_text "$WINDOWS_MACHINE_GATE" '[void]$cmdArguments.Add([string]$cmdArgument)'
require_text "$WINDOWS_MACHINE_GATE" '-Arguments ([string[]]$cmdArguments)'
reject_text "$WINDOWS_MACHINE_GATE" '$commandLine = $tokens -join ''' ''''
require_text "$WINDOWS_MACHINE_GATE" '$script:State.GradleJavaHomeForced = $true'
require_text_count "$WINDOWS_MACHINE_GATE" 'Invoke-Gradle' 4
require_text "$WINDOWS_MACHINE_GATE" '$launcherVersion -cne $script:State.JavaVersion'
require_text "$WINDOWS_MACHINE_GATE" '$launcherMajor -ne $script:State.JavaMajor'
require_text "$WINDOWS_MACHINE_GATE" '$daemonJavaHome.Equals('
require_text "$WINDOWS_MACHINE_GATE" '$script:State.JavaHome, [StringComparison]::OrdinalIgnoreCase'
require_text "$WINDOWS_MACHINE_GATE" 'gradleDaemonJvm = $script:State.GradleDaemonJvm'
require_text "$WINDOWS_MACHINE_GATE" 'gradleDaemonJavaHome = $script:State.GradleDaemonJavaHome'
require_text "$WINDOWS_MACHINE_GATE" 'Set-GradleJvmIdentity $gradle.Combined'
reject_text "$WINDOWS_MACHINE_GATE" 'Set-ExecutionPolicy'
reject_text "$WINDOWS_NATIVE_FETCH" 'Set-ExecutionPolicy'
require_text "$WINDOWS_MACHINE_GATE" "platforms/android-\$ExpectedPlatform/android.jar"
require_text "$WINDOWS_MACHINE_GATE" "build-tools/\$ExpectedBuildTools/aapt2.exe"
require_text "$WINDOWS_MACHINE_GATE" "ndk/\$ndkVersion"
require_text "$WINDOWS_MACHINE_GATE" "cmake/\$cmakeVersion"
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$cmakeRoot 'source.properties'"
require_text "$WINDOWS_MACHINE_GATE" "Get-NativeProperty 'cmakeExecutableVersion'"
require_text "$WINDOWS_MACHINE_GATE" "Get-NativeProperty 'cmakeExecutableVersion') -cne '3.22.1-g37088a8-dirty'"
require_text "$WINDOWS_MACHINE_GATE" "ContainsKey('Pkg.Revision')"
require_text "$WINDOWS_MACHINE_GATE" "ContainsKey('Pkg.Path')"
require_text "$WINDOWS_MACHINE_GATE" '$cmakePackageRevision -cne $cmakeVersion'
require_text "$WINDOWS_MACHINE_GATE" '$cmakePackagePath -cne "cmake;$cmakeVersion"'
require_text "$WINDOWS_MACHINE_GATE" '$cmakeExecutableVersion -cne $expectedCmakeExecutableVersion'
require_text "$WINDOWS_MACHINE_GATE" 'CMake package revision mismatch'
require_text "$WINDOWS_MACHINE_GATE" 'CMake package path mismatch'
require_text "$WINDOWS_MACHINE_GATE" 'CMake executable version mismatch'
require_text "$WINDOWS_MACHINE_GATE" 'cmakePackageRevision = $script:State.CmakePackageRevision'
require_text "$WINDOWS_MACHINE_GATE" 'cmakePackagePath = $script:State.CmakePackagePath'
require_text "$WINDOWS_MACHINE_GATE" 'cmakeExecutableVersion = $script:State.CmakeExecutableVersion'
reject_text "$WINDOWS_MACHINE_GATE" '$cmakeMatch.Groups[1].Value -ne $cmakeVersion'
require_text "$WINDOWS_MACHINE_GATE" "'windows-x86_64'"
require_text "$WINDOWS_MACHINE_GATE" "'bin/llvm-readelf.exe'"
require_text "$WINDOWS_MACHINE_GATE" "'bin/llvm-strings.exe'"
require_text "$WINDOWS_MACHINE_GATE" 'Test-NativeStructure -RequireSource'
require_text "$WINDOWS_MACHINE_GATE" 'run pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/native-fetch-fairy.ps1'
require_text "$WINDOWS_MACHINE_GATE" "'core.filemode=false'"
require_text "$WINDOWS_MACHINE_GATE" "'write-tree'"
require_text "$WINDOWS_MACHINE_GATE" 'patched source tree does not match the exact ordered patch series and lock'
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$script:State.TempRoot 'expected-native-index'"
require_text "$WINDOWS_MACHINE_GATE" '$indexLock = "$temporaryIndex.lock"'

# A full result requires one device, an explicit physical-device opt-in, both production
# ABIs in packages, byte parity with the verified AARs, and one bounded native test.
require_text "$WINDOWS_MACHINE_GATE" 'if ($readyDevices.Count -ne 1)'
require_text "$WINDOWS_MACHINE_GATE" 'physical-device execution requires -AllowPhysicalDevice'
require_text "$WINDOWS_MACHINE_GATE" '$script:State.DeviceSerialHash = Get-Sha256Text $selectedSerial'
require_text "$WINDOWS_MACHINE_GATE" "@('arm64-v8a', 'x86_64')"
require_text "$WINDOWS_MACHINE_GATE" "'clean', ':engine:assembleDebug', ':engine:assembleRelease'"
require_text "$WINDOWS_MACHINE_GATE" "':app:assembleDebug', ':app:assembleRelease', ':engine:assembleDebugAndroidTest'"
require_text "$WINDOWS_MACHINE_GATE" "@('--no-daemon', '--no-parallel', '--console=plain', '--stacktrace', \"--max-workers=\$Workers\")"
require_text_min_count "$WINDOWS_MACHINE_GATE" ':engine:connectedDebugAndroidTest' 2
require_text_min_count "$WINDOWS_MACHINE_GATE" 'engine-debug.aar' 2
require_text_min_count "$WINDOWS_MACHINE_GATE" 'engine-release.aar' 2
require_text_count "$WINDOWS_MACHINE_GATE" 'function Test-NativeApkSet {' 1
require_text_min_count "$WINDOWS_MACHINE_GATE" 'Test-NativeApkSet' 2
require_text_min_count "$WINDOWS_MACHINE_GATE" 'Test-NativeAar' 3
require_text "$WINDOWS_MACHINE_GATE" '[System.IO.Compression.ZipFile]::OpenRead($ArchivePath)'
require_text "$WINDOWS_MACHINE_GATE" 'function Get-ZipVerifierType {'
require_text "$WINDOWS_MACHINE_GATE" 'ZIP EOCD is missing'
require_text "$WINDOWS_MACHINE_GATE" 'central directory size does not match its entries'
require_text "$WINDOWS_MACHINE_GATE" 'CRC mismatch for '
require_text "$WINDOWS_MACHINE_GATE" 'Assert-OnlyNativeLibraries'
require_text "$WINDOWS_MACHINE_GATE" 'Assert-ByteIdentical'
require_text "$WINDOWS_MACHINE_GATE" "@('--wide', '--dyn-syms', \$LibraryPath)"
require_text "$WINDOWS_MACHINE_GATE" '-RequireFullJniContract'
require_text "$WINDOWS_MACHINE_GATE" 'JNI_OnLoad'
require_text "$WINDOWS_MACHINE_GATE" 'Java_* symbols instead of the locked RegisterNatives ABI'
require_text "$WINDOWS_MACHINE_GATE" '-TimeoutSeconds $InstrumentationTimeoutSeconds'
require_text "$WINDOWS_MACHINE_GATE" "'-Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000'"
require_text "$WINDOWS_MACHINE_GATE" '$_.LastWriteTimeUtc -gt $reportStarted'
require_text "$WINDOWS_MACHINE_GATE" '$tests -ne 1 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0'
require_text "$WINDOWS_MACHINE_GATE" '(Get-ArtifactSetHash) -ne $script:State.VerifiedArtifactSetSha'
require_text "$WINDOWS_MACHINE_GATE" "'logcat', '-d', '*:F'"

# Evidence is new-path-only, failure-safe, checksum sealed, and cannot authorize release.
require_text "$WINDOWS_MACHINE_GATE" '[System.IO.FileMode]::CreateNew'
require_text "$WINDOWS_MACHINE_GATE" '[System.IO.FileShare]::None'
require_text "$WINDOWS_MACHINE_GATE" 'evidence path already exists'
require_text "$WINDOWS_MACHINE_GATE" 'privateTestOnly = $true'
require_text "$WINDOWS_MACHINE_GATE" 'distributionAuthorized = $false'
require_text "$WINDOWS_MACHINE_GATE" "'powershell-native'"
require_text "$WINDOWS_MACHINE_GATE" 'requiresUnixShell = $false'
require_text "$WINDOWS_MACHINE_GATE" 'runtimeVerifiedAbis'
require_text "$WINDOWS_MACHINE_GATE" "Join-Path \$script:State.OutputPath 'SHA256SUMS'"
require_text_min_count "$WINDOWS_MACHINE_GATE" 'Finalize-Evidence' 2

# All functions must be defined before the executable main block, and the explicit exit
# must remain the final non-empty statement. This catches a valid-looking but inert port.
WINDOWS_NATIVE_FUNCTION_LINE=$(grep -n '^function Test-NativeStructure {' "$WINDOWS_MACHINE_GATE" | cut -d: -f1)
WINDOWS_MAIN_LINE=$(grep -n '^try {$' "$WINDOWS_MACHINE_GATE" | tail -1 | cut -d: -f1)
WINDOWS_EXIT_LINE=$(grep -n '^exit \$script:State.ExitCode$' "$WINDOWS_MACHINE_GATE" | cut -d: -f1)
WINDOWS_LAST_CONTENT_LINE=$(awk 'NF { line=NR } END { print line + 0 }' "$WINDOWS_MACHINE_GATE")
[[ "$WINDOWS_NATIVE_FUNCTION_LINE" =~ ^[0-9]+$ ]] \
    || die "$WINDOWS_MACHINE_GATE must define Test-NativeStructure exactly once"
[[ "$WINDOWS_MAIN_LINE" =~ ^[0-9]+$ && "$WINDOWS_EXIT_LINE" =~ ^[0-9]+$ ]] \
    || die "$WINDOWS_MACHINE_GATE is missing its executable main/exit block"
[[ "$WINDOWS_NATIVE_FUNCTION_LINE" -lt "$WINDOWS_MAIN_LINE" && \
   "$WINDOWS_MAIN_LINE" -lt "$WINDOWS_EXIT_LINE" && \
   "$WINDOWS_EXIT_LINE" -eq "$WINDOWS_LAST_CONTENT_LINE" ]] \
    || die "$WINDOWS_MACHINE_GATE must define every function before main and end with its exit code"

require_text "$PACKAGE_JSON" '"test:android-structure": "bash scripts/android-validate-structure.sh"'
require_text "$PACKAGE_JSON" '"test:android-machine": "bash scripts/android-machine-verify.sh"'
require_text "$PACKAGE_JSON" '"test:android-machine:windows": "pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1"'
require_text "$PACKAGE_JSON" 'npm run test:android-structure'

printf 'Android structure PASS.\n'
printf '  AGP / Gradle / Compose plugin: %s / %s / %s\n' \
    "$EXPECTED_AGP_VERSION" "$EXPECTED_GRADLE_VERSION" "$EXPECTED_COMPOSE_PLUGIN_VERSION"
printf '  stable compile / target / minimum SDK: %s / %s / %s\n' \
    "$EXPECTED_COMPILE_SDK" "$EXPECTED_TARGET_SDK" "$EXPECTED_MIN_SDK"
printf '  SDK build tools / NDK / CMake package / executable: %s / %s / %s / %s\n' \
    "$EXPECTED_BUILD_TOOLS_VERSION" "$EXPECTED_NDK_VERSION" \
    "$EXPECTED_CMAKE_VERSION" "$EXPECTED_CMAKE_EXECUTABLE_VERSION"
printf '  wrapper, Bash/PowerShell machine gates, and native instrumentation contract present\n'
