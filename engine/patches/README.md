# Fairy-Stockfish Drawless patch set

This directory is the reproducible, GPL-3.0 engine patch boundary for Drawless
Chess. `series` is the ordered patch list; `manifest.json` pins the only upstream
revision accepted by the current patch set. The native Drawless interface is version 2
and implements the complete app `RulesContractV1` terminal contract inside search.

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

Patch v2 also advertises and validates these UCI combo options:

```text
Drawless Dead Position = material-victory | final-capture-victory
Drawless Fifty Move = disabled | completing-player-loses | forced-move-exception | material-victory
Drawless Bare King = continue | bare-king-loses
```

`UCI_Variant=drawless|escape` remains the authoritative preset/stalemate
selection. Patch identity v2 fixes the remaining schema-v1 invariants:
third occurrence, completing-player loss, the forced repetition exception,
and material weights 1/3/3/5/9. The app contract rejects custom weights.

At every searched node, terminal precedence is no legal move (checkmate or
configured stalemate), repetition, bare king, the app's conservative known-dead
detector, then 100 halfmoves. The 50-move forced exception classifies the full
legal parent move set before changing the board; material ties use last capturer,
then the same forced fallback.

The fourth patch preserves every terminal at deeper selective-search boundaries.
At halfmove 99, main search bypasses futility, null-move, ProbCut, and move
pruning, while quiescence searches one full legal ply. On a cheap, sparse
material-topology gate, both searches use exact do/undo classification so a
last-piece capture or quiet bishop/knight underpromotion cannot be lost to
move-count, history, futility, SEE, or capture-only filtering. Quiescence also
classifies the full legal child set for immediate checkmate or stalemate, even
outside that material gate, and treats an all-terminal move set as authoritative
instead of allowing stand-pat. Every speculative classifier is node-count-neutral,
so `go nodes`, reported nodes, and NPS reflect searched nodes only.

The same patch keeps synthetic null moves out of the Drawless halfmove clock and
last-capturer history, normalizes repetition keys to legally capturable
en-passant targets, and uses an early-exit legal-move query for ordinary
stalemate checks. It also treats an already-terminal Drawless UCI root as
mandatory, suppresses terminal-child ponder moves, and limits Syzygy root
ranking to orthodox chess.

## Search and TT policy

Before any TT cutoff, null-move search, futility pruning, LMR, or move ordering,
the patch enumerates the full legal move list when the reversible history could
reach a third occurrence. If every move completes occurrence three, it returns
the variant-aware mate-distance win for the forced mover. Mixed move sets use
the ordinary configured n-fold terminal, so each completing move loses.

Patches v1-v2 do not trust or store TT bound scores anywhere in a variant with this
rule enabled. It may retain a board-valid TT move for ordering and may store a
static board evaluation. This intentionally broad policy prevents identical
boards with different histories from sharing a decisive result. Optimization
requires a separately reviewed history signature and a patch-version bump.

## Strength calibration correction

The second ordered patch corrects Fairy-Stockfish's stochastic rounding for negative
fractional skill levels. Low `UCI_Elo` values now alternate between the adjacent integer
levels with the intended probability instead of always truncating toward the stronger
level. This changes general strength calibration, not the Drawless variant interface, so
that patch did not itself bump the then-current interface version; the patched-tree and
series hashes pin the exact corrected binary source. `verify-elo-rounding.mjs` locks the patched source
contract and exhaustively checks all 1024 random residues for representative negative,
exact, and positive fractional levels.

## Baseline evidence

At the pinned unmodified revision, the exact forced fixture returns `h8g8` with
`score mate -1`. The patched engine returns the same only legal move with
`score mate 1`. `verify-engine.mjs` asserts the patched polarity, both color
directions, both avoidable-repeat evasions, history isolation, two hash sizes,
and stop/follow-up isolation. Patch-v2 fixtures additionally cover both colors,
every selectable policy, last-capture and forced material ties, legal-only
en-passant repetition keys, bare/dead/underpromotion pruning frontiers, mixed
terminal intersections, null-state invariants, and terminal precedence at
deeper-than-root search nodes.
