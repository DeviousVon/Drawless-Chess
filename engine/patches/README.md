# Fairy-Stockfish Drawless patch set

This directory is the reproducible, GPL-3.0 engine patch boundary for Drawless
Chess. `series` is the ordered patch list; `manifest.json` pins the only upstream
revision accepted by the current patch set. The Drawless variant interface remains version 1.

## Apply and verify

From the project root:

```sh
engine/patches/verify-patch.sh
```

To avoid a network fetch, provide an existing Fairy-Stockfish clone:

```sh
engine/patches/verify-patch.sh --source /path/to/Fairy-Stockfish
```

The verifier clones into a temporary directory, checks out the immutable pin,
checks the artifact checksums, compiles and asserts the unpatched `mate -1`
baseline, applies `series`, rebuilds, and runs the patched UCI acceptance suite.
It never modifies the supplied clone.

## Variant activation

The patch adds this custom-variant attribute:

```ini
drawlessForcedRepetition = true
```

Enable it on the shared `drawless` base variant; `escape` inherits the behavior.
The attribute normalizes the v1 contract to third occurrence, a win for the side
to move after an avoidable completion, and color-neutral scoring. In the forced
branch, the parent mover instead receives the win.

## Search and TT policy

Before any TT cutoff, null-move search, futility pruning, LMR, or move ordering,
the patch enumerates the full legal move list when the reversible history could
reach a third occurrence. If every move completes occurrence three, it returns
the variant-aware mate-distance win for the forced mover. Mixed move sets use
the ordinary configured n-fold terminal, so each completing move loses.

Patch v1 does not trust or store TT bound scores anywhere in a variant with this
rule enabled. It may retain a board-valid TT move for ordering and may store a
static board evaluation. This intentionally broad policy prevents identical
boards with different histories from sharing a decisive result. Optimization
requires a separately reviewed history signature and a patch-version bump.

## Strength calibration correction

The second ordered patch corrects Fairy-Stockfish's stochastic rounding for negative
fractional skill levels. Low `UCI_Elo` values now alternate between the adjacent integer
levels with the intended probability instead of always truncating toward the stronger
level. This changes general strength calibration, not the Drawless variant interface, so
the advertised `Drawless Patch Version` remains 1; the patched-tree and series hashes pin
the exact corrected binary source. `verify-elo-rounding.mjs` locks the patched source
contract and exhaustively checks all 1024 random residues for representative negative,
exact, and positive fractional levels.

## Baseline evidence

At the pinned unmodified revision, the exact forced fixture returns `h8g8` with
`score mate -1`. The patched engine returns the same only legal move with
`score mate 1`. `verify-engine.mjs` asserts the patched polarity, both color
directions, both avoidable-repeat evasions, history isolation, two hash sizes,
and stop/follow-up isolation.
