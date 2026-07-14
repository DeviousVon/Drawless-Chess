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
    docs/audio/audio_manifest.json docs/audio/licenses/CC0-1.0.txt \
    docs/audio/licenses/ion-sound-MIT.txt \
    release/reports/release-runtime-dependencies.txt \
    release/reports/release-sbom.cdx.json scripts/generate-release-sbom.ps1 \
    scripts/verify-sampled-audio.ps1 scripts/audio/rebuild_curated_foley.sh \
    scripts/audio/rebuild_curated_previews.sh scripts/audio/update_curated_audio_manifest.ps1 \
    scripts/source-bundle.sh scripts/native-source-bundle.sh; do
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
require_text "$REPOSITORY_ROOT/NOTICE" 'scripts/source-bundle.sh'
require_text "$REPOSITORY_ROOT/NOTICE" 'ion.sound 3.0.7'
require_text "$REPOSITORY_ROOT/NOTICE" 'No trademark registration is claimed'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" '115 external Maven modules'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'com.google.guava:guava-parent:26.0-android'
require_text "$REPOSITORY_ROOT/THIRD_PARTY_NOTICES.md" 'Copyright © 2019 by Denis Ineshin'
require_text "$REPOSITORY_ROOT/docs/AUDIO_PROVENANCE.md" '104 mono, 48 kHz Ogg/Vorbis resources'
require_text "$REPOSITORY_ROOT/docs/audio/audio_manifest.json" '74d51c5bd14be428f06b3afb5e40125b8e407fbc'
require_text "$REPOSITORY_ROOT/package.json" '"test:audio": "pwsh -NoProfile -NonInteractive -File scripts/verify-sampled-audio.ps1 -RequireDecode"'
require_text "$REPOSITORY_ROOT/package.json" 'npm test && npm run test:audio && npm run test:kotlin'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'duplicate decoded audio content'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'b25ed214614f9a71c7995193ba48317d5991b19fc9ae0a297d728dda69ab6bd8'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'Get-GitBlobSha1'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'sweep-like energy distribution'
require_text "$REPOSITORY_ROOT/scripts/verify-sampled-audio.ps1" 'approved real firework-pop recording'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" '"bomFormat": "CycloneDX"'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" '"specVersion": "1.5"'
require_text "$REPOSITORY_ROOT/release/reports/release-sbom.cdx.json" 'Inherited from Maven parent POM com.google.guava:guava-parent:26.0-android'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'distributionAuthorized'
require_text "$REPOSITORY_ROOT/scripts/native-source-bundle.sh" 'exec "$SCRIPT_DIR/source-bundle.sh" "$@"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'git -C "$REPOSITORY_ROOT" -c core.autocrlf=false'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'archive --format=tar "$SOURCE_COMMIT"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'archive --format=tar "$ACTUAL_NATIVE_PATCHED_TREE"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'native-validate-structure.sh" --require-source'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'generated binary reached the staging tree'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'repository must be clean before creating exact release source'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'SOURCE-COMMIT'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'archive-fairy-source.sha256'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'native administrative Git data reached staging'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'SOURCE-MANIFEST.sha256.digest'
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
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'base/assets/release/SOURCE-COMMIT'
require_text "$REPOSITORY_ROOT/scripts/verify-play-aab.ps1" 'canonicalManifestMatched = $true'
require_text "$REPOSITORY_ROOT/android/app/build.gradle.kts" 'Bundled SOURCE-COMMIT'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'distributionAuthorized = $false'

bash -n "$REPOSITORY_ROOT/scripts/source-bundle.sh"
bash -n "$REPOSITORY_ROOT/scripts/native-source-bundle.sh"
bash -n "$REPOSITORY_ROOT/scripts/audio/rebuild_curated_foley.sh"
bash -n "$REPOSITORY_ROOT/scripts/audio/rebuild_curated_previews.sh"

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
