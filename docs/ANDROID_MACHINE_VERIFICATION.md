# Android machine verification

Status: fail-closed Bash and Windows-native PowerShell gates implemented; real SDK/NDK,
x86-64 emulator, and ARM64 physical-device runs verified for private testing

## Locked toolchain

The first Android binary checkpoint deliberately targets the current stable platform
instead of the Android 17 preview:

- Android Gradle Plugin 9.2.1 with built-in Kotlin.
- Gradle 9.4.1 through the checked-in wrapper.
- Compose compiler plugin 2.3.10, matching AGP 9.2's Kotlin dependency.
- Java/Kotlin source and bytecode compatibility 17. Both machine gates may run Gradle with
  a complete stable JDK 17 or 21 whose Java and Javac versions match exactly.
- Compile and target SDK 36, Build Tools 36.0.0, minimum API 26.
- NDK 29.0.14206865 and Android SDK CMake package 3.22.1. Its executable build identity is
  locked separately as `3.22.1-g37088a8-dirty`.
- Packaged ABIs `arm64-v8a` and `x86_64`.

The wrapper locks the Gradle distribution SHA-256 and has a separately checked wrapper-JAR
SHA-256 sidecar. `npm run test:android-structure` rejects drift in these versions, built-in
Kotlin configuration, module SDKs, native pins, wrapper files, or machine-test contract.
Allowing two tested build-JDK majors is a compatibility policy, not a claim that the two
environments produce bit-for-bit identical artifacts. A later release CI lane must pin one
exact JDK vendor/build when reproducible release binaries become a goal.

API 37 is currently the Android 17 preview platform. Drawless Chess does not use API-37
features, so requiring that preview would add toolchain and behavior risk without a version-1
benefit. Moving to API 37 is a separate upgrade after the platform is stable.

Official compatibility references:

- https://developer.android.com/build/releases/agp-9-2-0-release-notes
- https://developer.android.com/build/jdks
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developer.android.com/about/versions/17/setup-sdk
- https://docs.gradle.org/current/userguide/compatibility.html
- https://docs.gradle.org/current/userguide/gradle_wrapper.html

## Install Android packages

Use Android Studio's SDK Manager or an already licensed `sdkmanager` installation. The
required package set is:

```text
platform-tools
platforms;android-36
build-tools;36.0.0
ndk;29.0.14206865
cmake;3.22.1
```

In Android Studio, open **Tools > SDK Manager**. Install Android SDK Platform 36 and Android
SDK Build-Tools 36.0.0 on the **SDK Platforms** and **SDK Tools** tabs. Enable **Show Package
Details** on the SDK Tools tab to select the exact NDK and CMake versions. The Android SDK
Location shown at the top is the path to pass to the gate.

Neither verification script installs packages, accepts Android SDK licenses, publishes an
artifact, or changes Android Studio's configuration.

## Windows-native prerequisites

Use 64-bit PowerShell 7 (`pwsh`), not Windows PowerShell 5.1 (`powershell.exe`). This lane
runs directly on Windows and does not require WSL, a Linux VM, or Hermes. It requires:

- a complete stable build JDK 17 or 21 whose `java.exe` and `javac.exe` report the same
  exact version;
- Git for Windows; and
- the Android SDK packages listed above.

Android Studio does not install PowerShell 7. If `pwsh --version` is not recognized, install
the current 64-bit PowerShell 7 release from Microsoft before continuing.

The repository-script commands below use `-ExecutionPolicy Bypass` only for that new
`pwsh` process, together with `-NoProfile` and `-NonInteractive`. They do not alter the
machine or user execution-policy settings. An organization-enforced Group Policy can still
block execution; if so, follow the organization's approved signing or policy workflow.

Android Studio is commonly installed in `C:\Program Files\Android\Android Studio`, and its
default SDK is commonly at `$env:LOCALAPPDATA\Android\Sdk`. The gate checks those locations
only as fallbacks when explicit paths and environment variables are absent. Use the locations
shown by Android Studio if yours differ.

Android Studio's bundled `jbr` is supported when it reports Java 17 or 21 and contains
`bin\javac.exe`; current JBR 21 installations can be passed directly with `-JavaHome`.
Early-access versions, mismatched Java/Javac versions, and other Java majors fail preflight.
This build-JDK choice does not change the app modules' Java/Kotlin source and bytecode
compatibility, which remains 17.

From the repository root in PowerShell 7:

```powershell
$env:ANDROID_SDK_ROOT = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"

pwsh --version
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:JAVA_HOME\bin\javac.exe" -version
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" version
git --version
```

To use a separate complete JDK 17 or 21 instead, assign its installation directory:

```powershell
$env:JAVA_HOME = 'C:\path\to\your\jdk-17-or-21'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:Path"
```

The environment assignments above affect only the current PowerShell process and child
processes. They do not change the machine-wide environment.

For the first native build, use a short local working path such as
`C:\src\Drawless-Chess`; avoid a network share or cloud-synchronized folder. Android Studio
does not need to remain open for preflight or compilation. It is needed only when using its
Device Manager to start an emulator.

## Linux and macOS prerequisites

The Bash lane requires Git, `zipinfo`, `unzip`, and either `timeout` (Linux) or `gtimeout`
(macOS coreutils) on `PATH`, plus a complete stable JDK 17 or 21. GNU `readelf`/`strings`
are optional because the gate can use the pinned NDK's LLVM equivalents.

## Prepare the pinned engine source when absent

The private-test bundle normally includes `engine/native/upstream/Fairy-Stockfish`. If that
directory is absent in a fresh checkout, fetch the exact locked revision and apply the
ordered Drawless patch series once.

On Windows:

```powershell
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/native-fetch-fairy.ps1
```

On Linux or macOS:

```bash
scripts/native-fetch-fairy.sh
npm run test:native-source
```

The fetch helpers verify the upstream commit and tree, the patched tree, and the ordered
patch-series SHA-256. They never replace an existing destination. The machine preflight
validates the prepared tree again before any Android build.

## Windows-native preflight without a device

Run this first. It does not build, start an emulator, select a device, or install an APK:

```powershell
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1 `
  -PreflightOnly `
  -Sdk $env:ANDROID_SDK_ROOT `
  -JavaHome $env:JAVA_HOME
```

PowerShell's backtick is a line-continuation character and must be the final character on
its line. The same command may be entered on one line if preferred.

## Bash preflight without a device

```bash
npm run test:android-machine -- \
  --preflight-only \
  --sdk "$ANDROID_SDK_ROOT"
```

Both preflight lanes verify their supported complete JDK, wrapper, SDK platform, Build Tools
package identity, NDK identity, and both the CMake package metadata and exact executable
build identity. They also verify Git access, native source tree, patches, and the variant
hash. Both lanes explicitly force Gradle's Java home to the selected JDK and require
Gradle's reported Launcher JVM exact version and major to match it. The Windows lane also
retains and validates the Daemon JVM identity in evidence.
Each creates an evidence directory even on failure.

The first wrapper invocation may download the checksum-locked Gradle 9.4.1 distribution.
That download is verified before use and is reused from Gradle's local cache afterward.

## Windows-native emulator run

Start an API-26-or-newer x86-64 emulator from Android Studio's Device Manager. Wait until
Android has booted, then confirm that the target device is listed as ready:

```powershell
& "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe" devices
```

Use that emulator serial, commonly `emulator-5554`:

```powershell
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1 `
  -Sdk $env:ANDROID_SDK_ROOT `
  -JavaHome $env:JAVA_HOME `
  -Serial emulator-5554 `
  -RequireAbi x86_64 `
  -Workers 2
```

When `-Serial` is supplied, the gate exports that exact value through `ANDROID_SERIAL`, and
both Gradle and every direct adb check remain scoped to it. Other ready phones or emulators
may stay connected. Without `-Serial`, the gate still fails closed if selection is ambiguous.

## Bash emulator run

Start an API-26-or-newer x86-64 emulator and select it explicitly; other ready adb devices
may remain connected:

```bash
npm run test:android-machine -- \
  --sdk "$ANDROID_SDK_ROOT" \
  --serial emulator-5554 \
  --require-abi x86_64 \
  --workers 2
```

## Windows-native ARM64 device run

Enable USB debugging, connect one authorized ARM64 Android device, and copy its serial from
`adb devices`. Physical-device execution requires the explicit switch below:

```powershell
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1 `
  -Sdk $env:ANDROID_SDK_ROOT `
  -JavaHome $env:JAVA_HOME `
  -Serial DEVICE_SERIAL `
  -RequireAbi arm64-v8a `
  -AllowPhysicalDevice `
  -Workers 2
```

## Bash ARM64 device run

Connect one authorized ARM64 device. Physical-device execution requires an explicit flag so
the gate cannot accidentally install test packages on a personal device:

```bash
npm run test:android-machine -- \
  --sdk "$ANDROID_SDK_ROOT" \
  --serial DEVICE_SERIAL \
  --require-abi arm64-v8a \
  --allow-physical-device \
  --workers 2
```

In both host lanes, the raw serial is used only for adb commands. The evidence manifest
stores its SHA-256, not the serial itself. Gradle runs with no persistent daemon, no parallel
project execution, and a bounded worker count so the gate can coexist with other work on the
machine.

## What one full run proves

The gate performs a clean build of:

- debug and release `:engine` AARs;
- debug and unsigned release app APKs; and
- the engine instrumentation APK.

It then verifies both AARs, rejects extra ABIs or unapproved native libraries, checks
legal/source assets and locked identities, and compares each APK's engine-native bytes with
the matching debug or release AAR. The app APK allowlist includes the Compose graphics-path
runtime library; the AARs and engine test APK remain engine-only. Finally it runs only
`AndroidFairyEngineInstrumentedTest`, which proves the
packaged asset, ART JNI load, forced-repetition `h8g8` mate-in-one result, patch identity,
an immediate close while asynchronous JNI startup may still be in flight, and two analyzed
native sessions created and closed sequentially.

The gate requires one fresh XML report with exactly one test, zero failures, zero errors,
and zero skips. It does not retry failures. A wall-clock timeout contains a native hang and
captures fatal Logcat output on failure.

## Evidence semantics

Every run writes logs, native manifests, the instrumentation XML report when available, a
top-level `manifest.json`, and `SHA256SUMS` under
`build/android-machine-verification/`. Evidence is retained on failure.

The manifest intentionally separates:

- `artifactAbis`: both ABIs compiled and packaged; from
- `runtimeVerifiedAbis`: only the ABI exercised by that device run.

Both host lanes record the selected Java major, full Java/Javac versions, vendor, runtime
and VM names, normalized JDK home, exact executable paths, forced Gradle Java home, and
Gradle Launcher JVM identity. Windows evidence also records and validates the Daemon JVM
home when Gradle reports it. This makes use of Android Studio's bundled JBR 21 explicit and
proves the wrapper launched with the selected Java version.

One emulator or device can never prove both runtime ABIs. The complete version-1 JNI matrix
requires one passing x86-64 emulator bundle and one passing ARM64 device bundle; both runs
have now passed for the private-test checkpoint.

All generated binaries are private-test artifacts. The manifest records
`distributionAuthorized: false`; successful compilation does not clear the GPL/release gate.

The separate seven-test app suite now passes on both the x86-64 emulator and ARM64 phone,
covering Room reopen/restore, rapid first-game-exit/second-game native behavior, same-session
hint then bot analysis, advanced setup, and rematch. Separate physical acceptance has covered
force-stop/relaunch/Resume. Folding the app suite into this immutable machine manifest,
low-memory/native-crash behavior, sustained performance, and broader UI coverage remain
later checkpoints.
