#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

die() {
    printf 'license-validate-structure: %s\n' "$*" >&2
    exit 1
}

require_text() {
    local file=$1
    local text=$2
    grep -Fq -- "$text" "$file" || die "$file does not contain required text: $text"
}

for required_file in \
    LICENSE APACHE-2.0.txt NOTICE THIRD_PARTY_NOTICES.md \
    docs/RELEASE_LICENSING.md docs/AUDIO_PROVENANCE.md \
    artwork/all-hallows/README.md \
    artwork/celestial-observatory/README.md \
    docs/audio/audio_manifest.json docs/audio/licenses/CC0-1.0.txt \
    docs/audio/licenses/ion-sound-MIT.txt \
    release/reports/release-runtime-dependencies.txt \
    release/reports/ios-release-runtime-dependencies.txt \
    release/reports/release-sbom.cdx.json scripts/generate-release-sbom.ps1 \
    scripts/verify-sampled-audio.ps1 scripts/audio/rebuild_lossless_audio.ps1 \
    release/public-source/REBUILD-IOS.md release/public-source/REBUILD-ANDROID.md \
    release/public-source/include-paths.txt \
    release/public-source/forbidden-paths.txt \
    release/public-source/forbidden-content.sha256 \
    scripts/source-bundle.sh scripts/source-bundle.py \
    scripts/test-source-bundle.py scripts/native-source-bundle.sh \
    scripts/inspect-ios-release-app.sh \
    iosApp/project.yml; do
    [[ -f "$REPOSITORY_ROOT/$required_file" ]] || die "missing $required_file"
done

require_text "$REPOSITORY_ROOT/LICENSE" 'GNU GENERAL PUBLIC LICENSE'
require_text "$REPOSITORY_ROOT/LICENSE" 'Version 3, 29 June 2007'
require_text "$REPOSITORY_ROOT/LICENSE" '17. Interpretation of Sections 15 and 16.'
require_text "$REPOSITORY_ROOT/APACHE-2.0.txt" 'Apache License'
require_text "$REPOSITORY_ROOT/APACHE-2.0.txt" 'Version 2.0, January 2004'
require_text "$REPOSITORY_ROOT/APACHE-2.0.txt" 'END OF TERMS AND CONDITIONS'
require_text "$REPOSITORY_ROOT/package.json" '"license": "GPL-3.0-or-later"'
require_text "$REPOSITORY_ROOT/package-lock.json" '"license": "GPL-3.0-or-later"'
require_text "$REPOSITORY_ROOT/NOTICE" 'owner-controlled clean-room publisher'
require_text "$REPOSITORY_ROOT/NOTICE" 'inclusion-only export'
require_text "$REPOSITORY_ROOT/NOTICE" 'non-deterministic output'
require_text "$REPOSITORY_ROOT/NOTICE" 'ion.sound 3.0.7'
require_text "$REPOSITORY_ROOT/NOTICE" 'No trademark registration is claimed'
require_text "$REPOSITORY_ROOT/NOTICE" 'Android and iOS'
require_text "$REPOSITORY_ROOT/NOTICE" 'Android APK/AAB or iOS App Store binary release'
require_text "$REPOSITORY_ROOT/NOTICE" 'Kotlin/Native 2.4.10'
require_text "$REPOSITORY_ROOT/NOTICE" 'AAC/M4A resources'
require_text "$REPOSITORY_ROOT/NOTICE" 'All Hallows’ Court sculpture atlases'
require_text "$REPOSITORY_ROOT/artwork/all-hallows/README.md" 'Final generation prompts'
require_text "$REPOSITORY_ROOT/artwork/all-hallows/README.md" '8561a31a9bd5b1e880c8ea3b3a04d1c75af5d23b76214ab370c2ce8f91fccd48'
require_text "$REPOSITORY_ROOT/artwork/all-hallows/README.md" '7cd157222646e02904386ba7560a4a39626d769258d88e5963f2c0168a62eda5'
require_text "$REPOSITORY_ROOT/artwork/celestial-observatory/README.md" 'Final generation prompts'
require_text "$REPOSITORY_ROOT/artwork/celestial-observatory/README.md" '224f6aed0aa519ac7f89fb4023ea1faf584b942b7df10d419ceb47753ef06d66'
require_text "$REPOSITORY_ROOT/artwork/celestial-observatory/README.md" 'e86c2e74fe57673c5e147cd5fb0b86e8979b2149f20a70bb0ff0b770517ffa53'
require_text "$REPOSITORY_ROOT/engine/native/SOURCE_NOTICE.txt" 'static Apple bridge on iOS'
require_text "$REPOSITORY_ROOT/engine/native/SOURCE_NOTICE.txt" 'iOS archive, or'
require_text "$REPOSITORY_ROOT/engine/native/SOURCE_NOTICE.txt" 'inclusion-only public publisher'
require_text "$REPOSITORY_ROOT/engine/native/SOURCE_NOTICE.txt" 'complete byte manifest'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" '115 external Maven modules'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'com.google.guava:guava-parent:26.0-android'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'Copyright © 2019 by Denis Ineshin'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'Drawless Chess 1.0.2 release'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" '## iOS release runtime'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'Kotlin 2.4.10'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'kotlinx-serialization-json:1.11.0'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'kotlinx-serialization-core:1.11.0'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'ios-release-runtime-dependencies.txt'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'AAC/M4A'
IOS_RUNTIME_REPORT="$REPOSITORY_ROOT/release/reports/ios-release-runtime-dependencies.txt"
require_text "$IOS_RUNTIME_REPORT" 'Configuration: iosArm64CompileKlibraries'
require_text "$IOS_RUNTIME_REPORT" 'org.jetbrains.kotlin:kotlin-stdlib:2.4.10'
require_text "$IOS_RUNTIME_REPORT" 'org.jetbrains.kotlinx:kotlinx-serialization-json-iosarm64:1.11.0'
require_text "$IOS_RUNTIME_REPORT" 'org.jetbrains.kotlinx:kotlinx-serialization-core-iosarm64:1.11.0'
require_text "$IOS_RUNTIME_REPORT" 'b6bdde447bac2dc90e9d73a107aedb130cc4bd873227d87ce3009659434f9903'
require_text "$IOS_RUNTIME_REPORT" 'c4231813a585384c493947756ee1ffbbd4d3e0b76ac98d2ed8335909aa1316a2'
require_text "$REPOSITORY_ROOT/docs/AUDIO_PROVENANCE.md" '103 high-quality stereo, 48 kHz Ogg/Vorbis resources'
require_text "$REPOSITORY_ROOT/docs/audio/audio_manifest.json" '74d51c5bd14be428f06b3afb5e40125b8e407fbc'
require_text "$REPOSITORY_ROOT/package.json" '"test:audio": "pwsh -NoProfile -NonInteractive -File scripts/verify-sampled-audio.ps1 -RequireDecode"'
require_text "$REPOSITORY_ROOT/package.json" '"test:source-bundle": "python3 scripts/test-source-bundle.py"'
require_text "$REPOSITORY_ROOT/package.json" 'npm test && npm run test:audio && npm run test:kotlin'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'duplicate decoded audio content'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'Get-CanonicalTextSha256'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" '97d683a1a78507df479abe5c5137fa56e8d990c1269b7a51c889e38d684ba15a'
require_text "$REPOSITORY_ROOT/scripts/audio/rebuild_lossless_audio.ps1" "'-q:a', '8'"
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'Get-GitBlobSha1'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'sweep-like energy distribution'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'approved real firework-pop recording'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" '"bomFormat": "CycloneDX"'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" '"specVersion": "1.5"'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" 'Inherited from Maven parent POM com.google.guava:guava-parent:26.0-android'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'distributionAuthorized'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'Android APK/AAB or iOS application archive'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'iosApp/project.yml'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'App Store Connect build identity'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'ios-v1.0.2-build-2'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'different roots,'
require_text "$REPOSITORY_ROOT/scripts/native-source-bundle.sh" 'exec "$SCRIPT_DIR/source-bundle.sh" "$@"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'source-bundle.py'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'repository must be clean before creating exact public source'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'tracked paths are not classified by the reviewed policy'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'prepared native staged tree differs from the locked patched tree'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'engine/native/archive-fairy-source.sha256'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'independent source builds are not byte-identical'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'source archive contains a link or special file'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'SOURCE-MANIFEST.sha256.digest'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" 'str(gate), "--release-gate"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.py" '--manifest-digest'
require_text "$REPOSITORY_ROOT/release/public-source/REBUILD-IOS.md" 'CODE_SIGNING_ALLOWED=NO'
require_text "$REPOSITORY_ROOT/release/public-source/REBUILD-IOS.md" '--unsigned-rebuild'
require_text "$REPOSITORY_ROOT/release/public-source/REBUILD-ANDROID.md" 'verifyPublicReleaseSource'
require_text "$REPOSITORY_ROOT/android/engine/build.gradle.kts" 'legal/drawless-chess'
require_text "$REPOSITORY_ROOT/android/engine/build.gradle.kts" 'third_party/android-runtime'
require_text "$REPOSITORY_ROOT/android/engine/build.gradle.kts" 'release/reports/release-sbom.cdx.json'
require_text "$REPOSITORY_ROOT/android/engine/build.gradle.kts" 'generated/release-identity/SOURCE-COMMIT'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'assets/legal/drawless-chess/LICENSE'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'assets/third_party/android-runtime/APACHE-2.0.txt'
require_text "$REPOSITORY_ROOT/scripts/native-verify-aar.sh" 'assets/third_party/android-runtime/release-sbom.cdx.json'
require_text "$REPOSITORY_ROOT/scripts/native-verify-apk.sh" 'assets/third_party/android-runtime/APACHE-2.0.txt'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'base/assets/legal/drawless-chess/LICENSE'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'repository must be clean so the AAB and corresponding source have one exact identity'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'sourceArchiveMatched = $true'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'base/assets/release/SOURCE-IDENTITY'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'base/assets/release/SOURCE-MANIFEST.sha256.digest'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'bundle must not contain a private source-commit asset'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" '--manifest-digest'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'canonicalManifestMatched = $true'
require_text "$REPOSITORY_ROOT/android/app/build.gradle.kts" ':engine:verifyPublicReleaseSource'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'distributionAuthorized = $false'
require_text "$REPOSITORY_ROOT/iosApp/project.yml" 'path: ../LICENSE'
require_text "$REPOSITORY_ROOT/iosApp/project.yml" 'path: ../APACHE-2.0.txt'
require_text "$REPOSITORY_ROOT/iosApp/project.yml" 'path: ../NOTICE'
require_text "$REPOSITORY_ROOT/iosApp/project.yml" 'path: ../THIRD_PARTY_NOTICES.md'
require_text "$REPOSITORY_ROOT/iosApp/project.yml" 'path: ../engine/native/SOURCE_NOTICE.txt'
require_text "$REPOSITORY_ROOT/iosApp/DrawlessChess/ReleaseChannel.swift" 'static let marker = "app-store-core"'
require_text "$REPOSITORY_ROOT/iosApp/DrawlessChess/ReleaseChannel.swift" 'static let marker = "private-review"'
IOS_RELEASE_INSPECTOR="$REPOSITORY_ROOT/scripts/inspect-ios-release-app.sh"
require_text "$IOS_RELEASE_INSPECTOR" 'verify_bundled_resource LICENSE LICENSE'
require_text "$IOS_RELEASE_INSPECTOR" 'verify_bundled_resource APACHE-2.0.txt APACHE-2.0.txt'
require_text "$IOS_RELEASE_INSPECTOR" 'verify_bundled_resource NOTICE NOTICE'
require_text "$IOS_RELEASE_INSPECTOR" 'THIRD_PARTY_NOTICES.md THIRD_PARTY_NOTICES.md'
require_text "$IOS_RELEASE_INSPECTOR" 'engine/native/SOURCE_NOTICE.txt SOURCE_NOTICE.txt'
require_text "$IOS_RELEASE_INSPECTOR" 'legal_resources=5'
require_text "$IOS_RELEASE_INSPECTOR" 'release_channel=app-store-core'
require_text "$IOS_RELEASE_INSPECTOR" 'require_core_only_release_channel_marker'
require_text "$IOS_RELEASE_INSPECTOR" 'game.postGame.reviewGate'
require_text "$IOS_RELEASE_INSPECTOR" 'review.header'
require_text "$IOS_RELEASE_INSPECTOR" 'review.board'
require_text "$IOS_RELEASE_INSPECTOR" 'core_only_review_identifier_scan=PASS'
require_text "$IOS_RELEASE_INSPECTOR" 'signing_mode=unsigned-rebuild'
bash -n "$REPOSITORY_ROOT/scripts/source-bundle.sh"
bash -n "$REPOSITORY_ROOT/scripts/native-source-bundle.sh"
bash -n "$REPOSITORY_ROOT/scripts/inspect-ios-release-app.sh"
python3 -c 'import ast, pathlib, sys; [ast.parse(pathlib.Path(path).read_text()) for path in sys.argv[1:]]' \
    "$REPOSITORY_ROOT/scripts/source-bundle.py" \
    "$REPOSITORY_ROOT/scripts/test-source-bundle.py"

UPSTREAM_LICENSE="$REPOSITORY_ROOT/engine/native/upstream/Fairy-Stockfish/Copying.txt"
if [[ -f "$UPSTREAM_LICENSE" ]]; then
    TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/drawless-license-check.XXXXXX")
    cleanup() { rm -rf "$TEMP_ROOT"; }
    trap cleanup EXIT
    normalize() {
        tr -d '\r' < "$1" | awk '
            { lines[NR] = $0 }
            END {
                last = NR
                while (last > 0 && lines[last] == "") last--
                for (i = 1; i <= last; i++) print lines[i]
            }
        '
    }
    normalize "$REPOSITORY_ROOT/LICENSE" > "$TEMP_ROOT/project-license"
    normalize "$UPSTREAM_LICENSE" > "$TEMP_ROOT/upstream-license"
    cmp -s "$TEMP_ROOT/project-license" "$TEMP_ROOT/upstream-license" \
        || die "root LICENSE is not the full vendored GPLv3 text"
fi

printf 'GPL license/release structure PASS.\n'
