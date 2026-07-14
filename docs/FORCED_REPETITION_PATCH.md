# Drawless forced-repetition patch v1

Status: implemented and verified in pinned native host builds and packaged Android JNI
runs on x86-64 and ARM64

## Rule

The third occurrence is decisive without a claim:

- If the completing player had at least one legal move that did not complete a third
  occurrence, the completing player loses.
- If every legal move completed a third occurrence, the completing player was forced;
  the opponent who forced the cycle loses.

Fairy-Stockfish configuration models the first branch with `nFoldRule = 3` and
`nFoldValue = win`: after the move, the side to move wins, so an avoidable completer
loses. Configuration cannot classify all alternatives at the parent node, which is the
reason for patch v1.

## Pinned source identity

The reproducible patch set is under `engine/patches/`:

- Upstream repository: `https://github.com/fairy-stockfish/Fairy-Stockfish.git`
- Upstream commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patch file: `0001-drawless-forced-repetition-v1.patch`
- Patch SHA-256: `5bf9ec8dbd1254ed48bc6d29c92741c56c776124e87ef39b36f4a5d14b416ca2`
- Patch commit recorded in the mail-format artifact:
  `b83e5c04029fb1c47ec277da585778c46fd19e51`
- Intermediate tree after this rule patch: `090d26be47498b99a23fdb1b9ff7587740b95664`
- Current ordered patch-set tree (including low-Elo rounding correction):
  `80208e5f35549b88505df983e4bc0f7621083fd4`

`engine/patches/manifest.json` is the machine-readable authority and `series` is the
ordered patch list. These identities describe the source proven by the native verifier;
they are not identities for the older npm/WASM proof of concept.

## Implemented behavior

The patch adds the custom-variant attribute:

```ini
drawlessForcedRepetition = true
```

It is enabled on `drawless`; `escape` inherits it. Enabling the attribute normalizes the
contract to occurrence three, a relative `win` result, and color-neutral scoring.

At a parent node, before pruning can discard siblings, patch v1:

1. Uses the existing reversible `StateInfo` history and repetition key.
2. Skips full classification when the history cannot yet produce occurrence three.
3. Generates the complete legal move list when a third occurrence is possible.
4. Makes and undoes each legal move and checks the resulting occurrence count.
5. Returns the variant-aware mate-distance win for the mover when every legal move
   completes occurrence three.
6. Leaves mixed move sets to ordinary n-fold handling, where each avoidable completing
   move loses for its mover.

The check runs at root, PV, non-PV, and quiescence entry points before TT cutoffs,
null-move pruning, futility pruning, late-move reductions, or move ordering. It counts
legal rather than pseudo-legal moves. Patch v1 also disables Fairy-Stockfish's ordinary
after-root shortcut to occurrence two for this variant, preserving the exact third-
occurrence rule.

The key remains Fairy-Stockfish's game-law key: board, side to move, castling rights,
and effective en-passant state, excluding move counters. UCI must provide the complete
`position ... moves ...` history. Selectable 50-move and dead-position policies remain
outside this engine classification.

## Transposition-table policy

The result is history-dependent, while the upstream TT key is board-based. Patch v1
therefore does not read or write TT bound scores anywhere in a variant with
`drawlessForcedRepetition` enabled. This applies even before a repetition is imminent.

An existing board-valid TT move may still be used for ordering, and a static board
evaluation may be stored without a bound. No TT score can produce a cutoff or be reused
as an exact, lower, or upper result. This intentionally conservative policy prevents
identical boards with different histories from sharing a decisive value. Any future
history signature or bound-score optimization requires separate proof and a patch-
version review.

## Binary identity

The patched engine advertises exactly:

```text
option name Drawless Patch Version type spin default 1 min 1 max 1
```

The platform-neutral Kotlin session validates this declaration and the packaged engine
metadata before accepting search. JVM coverage checks the contract, and the Android
machine gate has additionally built the JNI library/package and accepted the same patch
identity and forced search on both supported runtime ABIs.

## Both-color parity fixtures

`engine/parity-fixtures-v1.json` contains four repetition-search fixtures:

| Fixture | Side completing | Decisive move | Expected search behavior |
|---|---:|---|---|
| `avoidable-third-repetition` | Black | `f6g8` | Must choose another move; Black would lose |
| `avoidable-third-repetition-white` | White | `g1f3` | Must choose another move; White would lose |
| `forced-third-repetition-exception` | Black | `h8g8` only | `bestmove h8g8`, `score mate 1`; Black wins |
| `forced-third-repetition-exception-white` | White | `a1b1` only | `bestmove a1b1`, `score mate 1`; White wins |

For the Black forced fixture, the pinned unmodified native engine returns the same only
legal move with `score mate -1`. The patched engine returns `score mate 1`, proving that
the test checks winner polarity rather than merely move legality.

## Verified native gates

`engine/patches/verify-patch.sh` performs a clean, reproducible check:

- Verifies all committed checksums and the pinned upstream commit.
- Compiles the unmodified x86-64 Linux engine and asserts the `mate -1` baseline.
- Applies the ordered patch, recompiles, and checks the exact UCI patch declaration.
- Asserts both forced wins and both avoidable-repeat evasions at deterministic depth.
- Exercises Hash sizes 1 and 64; Drawless bound-score use remains disabled at both.
- Searches identical boards with short and long histories without clearing the table and
  proves that a forced result does not leak.
- Exercises `ucinewgame`, UCI `stop`, and a following search to prove request isolation.

The host build uses the upstream Linux x86-64 make target with classical evaluation. The
separate Android gate now establishes NDK compilation, JNI loading, ABI packaging, and
forced-search behavior in the app's engine package on an API-36 x86-64 emulator and API-33
ARM64 tablet. The Android build deliberately uses classical evaluation (`NNUE_EMBEDDING_OFF`),
so no NNUE asset claim is made. Sustained device performance and low-memory/native-crash
resilience remain release gates.
