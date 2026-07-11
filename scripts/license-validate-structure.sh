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
    LICENSE NOTICE THIRD_PARTY_NOTICES.md docs/RELEASE_LICENSING.md \
    scripts/source-bundle.sh scripts/native-source-bundle.sh; do
    [[ -f "$REPOSITORY_ROOT/$required_file" ]] || die "missing $required_file"
done

require_text "$REPOSITORY_ROOT/LICENSE" 'GNU GENERAL PUBLIC LICENSE'
require_text "$REPOSITORY_ROOT/LICENSE" 'Version 3, 29 June 2007'
require_text "$REPOSITORY_ROOT/LICENSE" '17. Interpretation of Sections 15 and 16.'
require_text "$REPOSITORY_ROOT/package.json" '"license": "GPL-3.0-or-later"'
require_text "$REPOSITORY_ROOT/package-lock.json" '"license": "GPL-3.0-or-later"'
require_text "$REPOSITORY_ROOT/NOTICE" 'scripts/source-bundle.sh'
require_text "$REPOSITORY_ROOT/NOTICE" 'no sampled or stock audio recording is embedded'
require_text "$REPOSITORY_ROOT/NOTICE" 'No trademark registration is claimed'
require_text "$REPOSITORY_ROOT/docs/RELEASE_LICENSING.md" 'distributionAuthorized'
require_text "$REPOSITORY_ROOT/scripts/native-source-bundle.sh" 'exec "$SCRIPT_DIR/source-bundle.sh" "$@"'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'SOURCE_DIRECTORIES=(android contracts docs engine scripts src)'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'native-validate-structure.sh" --require-source'
require_text "$REPOSITORY_ROOT/scripts/source-bundle.sh" 'generated binary reached the staging tree'
require_text "$REPOSITORY_ROOT/android/engine/build.gradle.kts" 'legal/drawless-chess'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'assets/legal/drawless-chess/LICENSE'
require_text "$REPOSITORY_ROOT/scripts/android-machine-verify.ps1" 'distributionAuthorized = $false'

bash -n "$REPOSITORY_ROOT/scripts/source-bundle.sh"
bash -n "$REPOSITORY_ROOT/scripts/native-source-bundle.sh"

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
