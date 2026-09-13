# Rebuild Drawless Chess for Android

The Android corresponding-source archive records its version, version code,
application ID, public tag, and pinned native engine identity in `SOURCE-IDENTITY`.
For version 1.0.3 (code 7), the tag is `v1.0.3` and the archive is
`drawless-chess-android-1.0.3-build-7-source.tar.gz`. Android release identity comes
from `android/app/build.gradle.kts`, independently of the Apple and npm versions.

The archive includes the complete pinned, patched Fairy-Stockfish source. No
native source fetch or Git repository is needed to rebuild it.

## Verify the archive

From the extracted archive root:

```bash
python3 scripts/source-bundle.py --platform android --verify \
  ../drawless-chess-android-1.0.3-build-7-source.tar.gz
shasum -a 256 -c SOURCE-MANIFEST.sha256
test "$(shasum -a 256 SOURCE-MANIFEST.sha256 | awk '{print $1}')" = \
  "$(tr -d '\r\n' < SOURCE-MANIFEST.sha256.digest)"
bash scripts/native-validate-structure.sh --require-source
```

The verifier checks the complete manifest, normalized archive metadata, safe
paths, private-content policy, and agreement between Android release metadata,
the native lock, the archive root, and `SOURCE-IDENTITY`. The native manifest
uses `./relative` paths; the complete source manifest uses plain relative paths.
Both inventories include every source file in their scope.

The release AAB contains `assets/release/SOURCE-IDENTITY` and
`assets/release/SOURCE-MANIFEST.sha256.digest`, copied from the source archive.
Their bytes must match the corresponding files at the archive root.

## Toolchain and local build

Use a JDK 17 or later supported by the checked-in Gradle wrapper, Python 3,
Bash, Git, and an Android SDK containing API 36. The exact native tool versions
are in `engine/native/upstream.properties`: Android NDK 29.0.14206865 and Android
SDK CMake 3.22.1. The checked-in Gradle wrapper and its distribution checksum
select Gradle. SDKs, dependency caches, and JDKs are separate system components.
Gradle may need network access to download the declared build dependencies.

Point `ANDROID_HOME` at the installed Android SDK, or use an untracked
`android/local.properties` with `sdk.dir` for local SDK routing. Then run:

```bash
cd android
./gradlew :engine:verifyPublicReleaseSource
./gradlew :app:assembleDebug
```

The debug task produces a locally debug-signed test APK and requires no official
upload-signing material. Its separate test application ID preserves an existing
production installation. Source modifications can change the release manifest;
the public release source check deliberately rejects drift from the published
inventory. Use an untouched extracted tree when checking the published release.

An official release bundle is built with `./gradlew :app:bundleRelease` from the
verified export, with the existing release-signing configuration supplied
externally. Upload signing keys and passwords are not included in corresponding
source. Release signature inspection, AAB validation, and device testing are
separate from the source archive gate.

## Source archive preparation

In the clean release repository with the native source prepared, the explicit
Android publisher command is:

```bash
scripts/source-bundle.sh --platform android \
  OUTPUT/drawless-chess-android-1.0.3-build-7-source.tar.gz
```

The publisher classifies all tracked paths, exports approved committed blobs,
checks the prepared native source against its lock, and runs
`python3 scripts/test-source-bundle.py --android-release-gate`. This gate runs
the source policy and identity tests, creates two byte-identical temporary
archives, verifies and extracts the result, and checks the extracted native
source inventory. It does not compile or sign an Android binary. Any failure
prevents final archive creation, and existing output files are never replaced.

The default publisher mode remains iOS with its Apple release rebuild gate.
