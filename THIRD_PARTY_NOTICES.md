# Third-party notices

This file records the third-party material in the Drawless Chess 0.3.0 release
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

The chess pieces and launcher icon are vector/code-native original project work
licensed with Drawless Chess under GPL-3.0-or-later. Sampled audio is separately
identified below. See `docs/AUDIO_PROVENANCE.md`.

## Sampled chess audio — CC0-1.0

Move, capture, and castling effects derive from:

- “Chess Pieces Move (Close)” by JJTaynos:
  <https://freesound.org/people/JJTaynos/sounds/733927/>
- “chess_move_on_alabaster.wav” by mh2o:
  <https://freesound.org/people/mh2o/sounds/351518/>

Victory fireworks derive from genuine pyrotechnic recordings by Rudmer_Rotteveel:

- “2 Firework pops”: <https://freesound.org/people/Rudmer_Rotteveel/sounds/334042/>
- “Whistle and Explosion Single_Firework”:
  <https://freesound.org/people/Rudmer_Rotteveel/sounds/336008/>

All four source pages designate the recordings Creative Commons Zero 1.0. CC0
does not require attribution, but the identities, retained HQ preview files and
hashes, original-download identities, and processing descriptions are preserved
under `docs/audio/`. The complete CC0 legal text is in
`docs/audio/licenses/CC0-1.0.txt` in the corresponding source distribution.

## ion.sound 3.0.7 — MIT License

Glass and reserved UI layers include modified recordings from ion.sound 3.0.7 by
Denis Ineshin. The retained recordings match upstream commit
`74d51c5bd14be428f06b3afb5e40125b8e407fbc`. Full provenance and hashes are in
`docs/audio/audio_manifest.json`.

The MIT License

Copyright © 2019 by Denis Ineshin (http://ionden.com)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the “Software”), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
