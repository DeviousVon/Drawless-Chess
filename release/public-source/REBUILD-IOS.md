# Rebuild Drawless Chess for iOS

This archive is the preferred source form for Drawless Chess iOS 1.0.2 (build
2), prepared for public tag `ios-v1.0.2-build-2`. It contains the exact staged
Fairy-Stockfish source selected by the public native lock. No source fetch is
required for the engine.

## Verify the archive first

From the extracted archive root on macOS:

```bash
shasum -a 256 -c SOURCE-MANIFEST.sha256
test "$(shasum -a 256 SOURCE-MANIFEST.sha256 | awk '{print $1}')" = \
  "$(tr -d '\r\n' < SOURCE-MANIFEST.sha256.digest)"
python3 scripts/source-bundle.py --verify \
  ../drawless-chess-ios-1.0.2-build-2-source.tar.gz
```

The verifier rejects unsafe archive entry types and names, path collisions,
unmanifested files, forbidden project paths, private-only content tokens, and
non-normalized metadata.

## Toolchain

Use macOS on Apple silicon with Xcode 26.6 build 17F113 selected and its iOS
26.5 SDK, plus XcodeGen, Python 3, Node.js/npm, PowerShell 7 and FFmpeg for the
sampled-audio gate, and a complete JDK 17 or 21. The checked-in Gradle wrapper
supplies Gradle. Apple SDKs, JDKs, Node.js, XcodeGen, PowerShell, and FFmpeg are
independent system components and are not included in corresponding source.

## Validate the source

```bash
scripts/native-validate-structure.sh --require-source
npm ci
python3 scripts/test-source-bundle.py
npm test
npm run test:audio
npm run test:kotlin
npm run test:kmp
npm run test:kmp:apple
npm run test:license
npm run test:native-patch
```

`test:native-patch` replays the ordered Drawless patch series against the pinned
upstream revision and runs its native acceptance harness. The prepared source
tree is also checked directly against `engine/native/upstream.properties` and
its complete native byte manifest.

In the clean release repository, `scripts/source-bundle.sh` first runs
`python3 scripts/test-source-bundle.py --release-gate`. That fail-closed gate
runs the source-policy tests and the validation commands above, proves XcodeGen
idempotence, rebuilds the Apple engine slices, creates fresh unsigned Release
simulator and device products, and inspects both before archive creation is
allowed. The lower-level Python publisher refuses ordinary archive-creation
invocations. Every archive-creation call in the Python publisher itself runs the
gate and stops before archive emission if any gate command fails; there is no
caller-supplied gate-completion flag.

## Regenerate and build without signing

Regenerate the checked-in Xcode project and rebuild all Apple engine slices:

```bash
scripts/generate-ios-project.sh
git diff --exit-code -- iosApp/DrawlessChess.xcodeproj
scripts/build-ios-engine.sh
```

Build the app-store-core Release product for both Apple target families without
requesting or using signing material:

```bash
xcodebuild -project iosApp/DrawlessChess.xcodeproj \
  -scheme DrawlessChess -configuration Release \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/public-rebuild-simulator \
  ARCHS=arm64 CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

scripts/inspect-ios-release-app.sh --unsigned-rebuild \
  'build/public-rebuild-simulator/Build/Products/Release-iphonesimulator/Drawless Chess.app'

xcodebuild -project iosApp/DrawlessChess.xcodeproj \
  -scheme DrawlessChess -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/public-rebuild-device \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

scripts/inspect-ios-release-app.sh --unsigned-rebuild \
  'build/public-rebuild-device/Build/Products/Release-iphoneos/Drawless Chess.app'
```

The explicit unsigned mode still validates the bundle identity, version/build,
app-store-core release marker, absence of private-review identifiers and test
seams, legal and privacy resources, localizations, audio, portraits, variants,
Mach-O platform, system-library boundary, and privacy declarations. It also
requires the rebuilt product to contain no provisioning profile or valid code
signature. Omitting `--unsigned-rebuild` preserves the stricter signed-release
inspection, including team, certificate, profile, entitlement, archive, and
dSYM checks. Signed inspection deliberately stores no project-specific Apple
identity in public source: the caller must provide `DRAWLESS_EXPECTED_APPLE_TEAM_ID`,
and development-signed inspection additionally requires
`DRAWLESS_EXPECTED_APPLE_DEVELOPMENT_CERTIFICATE_SHA1`.

On optimized ARM64 Release products, channel validation first requires a thin
arm64 Mach-O, then parses only instruction-addressed `otool -tvV` output. It
reconstructs each Swift small-string word from an exact same-register
`mov`/`movk` 16/32/48 sequence. The `app-store-core` pair must occur with the
exact `releaseChannel.marker` literal-pool reference inside one contiguous,
bounded region containing no `ret`; the `private-review` pair is independently
rejected in every such region. Function and Objective-C call symbol names are
not evidence. Raw strings, paths, debug metadata, and appended bytes cannot
prove the release channel. The release gate applies the same scanner to each
exact rebuilt executable and to a disposable `strip -Sx` copy before strict app
inspection.

Signing identities, certificates, profiles, credentials, and App Store upload
access are not part of corresponding source. Building or installing a modified
copy may also require platform installation information and an Apple-supported
local signing method.
