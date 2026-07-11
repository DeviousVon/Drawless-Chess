# Third-party notices

This file records the direct dependency families known at the Drawless Chess
0.1.0 checkpoint. It is not a substitute for the notices inside the dependencies
or for a dependency/SBOM scan of the exact release build.

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

## Android and build ecosystem

The source uses AndroidX Activity, Jetpack Compose, Room, AndroidX Test, Espresso,
Kotlin, the Android Gradle Plugin, Gradle, KSP, and their transitive dependencies.
These ecosystems predominantly use Apache-2.0, but the release owner must derive
the exact list from the resolved release dependency graph and preserve every
license and NOTICE required by the exact versions that are shipped.

Apache-2.0 is compatible with GPLv3. Nothing in this file relicenses third-party
material; each component remains under its own license.

## Original UI assets

The chess pieces and launcher icon are vector/code-native original project work,
and move/capture plus firework/glass-crack sounds are generated procedurally at runtime. They are not
third-party dependencies and are licensed with Drawless Chess under
GPL-3.0-or-later. This statement must be revisited if packaged imagery, fonts, or
sampled audio are introduced.
