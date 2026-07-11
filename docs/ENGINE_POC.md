# Unpatched Fairy-Stockfish npm/WASM proof of concept

Status: retained as a fast configuration regression lane; not the production patch-v1
engine

## Goal

The original proof of concept established that an existing Fairy-Stockfish WASM package
could load Drawless and Escape as custom variants, treat stalemate as terminal, and avoid
a move that completes a losing third occurrence. It proved that result reversal cannot
live only in the UI: search itself needs variant-aware outcomes.

## POC identity

The npm lane is locked in `package-lock.json` to:

- `fairy-stockfish-nnue.wasm@1.1.11`
- `ffish-es6@0.7.9`

Those packages contain an older, prebuilt and unpatched WASM engine. It does not advertise
`Drawless Patch Version`, does not implement the forced-completer exception, and is not
source-identical to the pinned native patch baseline. The package versions and lockfile
integrities identify this regression lane; they must not be treated as a native source or
production binary identity.

The production source patch is independently pinned to:

- Upstream commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patched result tree: `090d26be47498b99a23fdb1b9ff7587740b95664`

Its manifest, checksum, ordered patch series, and native verifier are under
`engine/patches/`.

## Custom configuration covered by WASM

`engine/variants.ini` inherits ordinary chess and configures:

- `stalemateValue = loss` for Drawless Chess.
- `stalemateValue = win` for Escape Chess.
- `nFoldRule = 3` and relative `nFoldValue = win`, so the side to move wins after
  its opponent voluntarily completes occurrence three.
- `nMoveRule = 0`, leaving the selectable 50-move behavior to app adjudication.

The patch-only `drawlessForcedRepetition` attribute is ignored by the older engine; its
presence does not add the forced branch to WASM search.

## Current POC coverage

`npm run test:engine` runs the unpatched WASM worker and currently verifies:

- Drawless stalemate loss
- Escape stalemate win
- Ordinary checkmate
- Black avoiding `f6g8`, which would voluntarily complete occurrence three
- White avoiding `g1f3`, the color-mirrored voluntary completion

It deliberately skips both forced fixtures marked `drawless-patch-v1`. Passing this suite
therefore does not prove forced-repetition parity.

## Boundary discovered and resolved

Built-in n-fold configuration can score an avoidable completing move as a loss, but it
cannot inspect every legal sibling at the parent. The two forced fixtures require source-
level search behavior:

- Black has only `h8g8` and must receive `score mate 1`.
- White has only `a1b1` and must receive `score mate 1`.

At the pinned unmodified native baseline, the Black fixture is `score mate -1`. Patch v1
changes the polarity to `mate 1`, covers both colors, and uses a conservative no-TT-bound
policy. `engine/patches/verify-patch.sh` proves that behavior with a native Linux x86-64
build. The app rules engine remains authoritative for recorded results, but it no longer
serves as a substitute for correct production search semantics.

## Scope boundary

The WASM POC remains useful only for quick configuration and fixture regression tests; it
does not identify the production binary. The production native path is now separately
proved through Android NDK compilation, ABI packaging, ART JNI loading, APK assembly, and
forced-search runs on x86-64 and ARM64. That Android build uses classical evaluation rather
than an embedded NNUE asset. Signed-release, performance, and resilience evidence remain
outside this POC.

## Licensing

The npm packages, Fairy-Stockfish, and the derived native patch are GPL-3.0 family code.
Drawless Chess now licenses the combined application under GPL-3.0-or-later. Public Android
distribution still requires exact whole-project corresponding source, notices/SBOM, and the
matching controls in `docs/RELEASE_LICENSING.md`.
