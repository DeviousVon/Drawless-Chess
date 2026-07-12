# Third-party notices

This file records the third-party material in the Drawless Chess 0.1.0 release
checkpoint. The exact resolved Android runtime inventory is generated from
Gradle and recorded in `release/reports/release-sbom.cdx.json`.

## Fairy-Stockfish and Stockfish

- Component: Fairy-Stockfish, derived from Stockfish
- License: GPL-3.0-or-later
- Upstream: <https://github.com/fairy-stockfish/Fairy-Stockfish>
- Exact native revision and tree: `engine/native/upstream.properties`
- Drawless modifications: `engine/patches/series`
- License and authors in a prepared tree: `Copying.txt` and `AUTHORS`
- Packaged source notice: `engine/native/SOURCE_NOTICE.txt`

The Android application links the modified engine in process. Drawless Chess has
therefore adopted GPL-3.0-or-later for the combined application; it does not rely
on the JNI boundary to avoid the engine's copyleft terms.

## JavaScript engine proof of concept

The repository's non-Android experiment directly depends on
`fairy-stockfish-nnue.wasm` 1.1.11 and `ffish-es6` 0.7.9. The npm lockfile reports
GPL-3.0 for both. These packages do not define the native Android source identity.

## Android release runtime

The resolved `app:releaseRuntimeClasspath` contains 115 external Maven modules.
`scripts/generate-release-sbom.ps1` recreates the Gradle dependency report and a
deterministic CycloneDX 1.5 SBOM, then fails if any resolved module lacks locally
cached Maven POM license evidence or uses an unreviewed license.

All 115 modules currently resolve to Apache-2.0. For 114 modules, the license is
declared directly in the module POM. `com.google.guava:listenablefuture:1.0`
inherits Apache-2.0 from its declared parent POM,
`com.google.guava:guava-parent:26.0-android`; both POM hashes and the inheritance
evidence are preserved on that SBOM component. The complete Apache-2.0 text is
in `APACHE-2.0.txt` and is packaged with the SBOM in the Android artifact.

Regenerate and review this evidence whenever dependencies change:

```powershell
pwsh -NoProfile -File scripts/generate-release-sbom.ps1
```

Apache-2.0 is compatible with GPLv3. Nothing in this file relicenses third-party
material; each component remains under its own license, and upstream notices
remain authoritative.

## Original UI assets

The chess pieces and launcher icon are vector/code-native original project work,
and move/capture plus firework/glass-crack sounds are generated procedurally at runtime. They are not
third-party dependencies and are licensed with Drawless Chess under
GPL-3.0-or-later. This statement must be revisited if packaged imagery, fonts, or
sampled audio are introduced.
