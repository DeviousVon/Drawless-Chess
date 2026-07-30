# ADR-001: Fairy-Stockfish integration and Drawless search semantics

Status: accepted; protocol, pinned patch v2, Android native runtime, and private-test packaging
implemented; clean patch-v2 host verification passed, with exact Android artifact/device proof
still pending

## Decision

Use Fairy-Stockfish behind an engine-neutral asynchronous interface. Maintain a small,
reviewable and versioned Drawless patch set rather than correcting variant outcomes only
after the engine returns a move.

The app-level rules engine remains authoritative for the recorded result. The search
engine must nevertheless model the same outcomes so the bot does not deliberately enter
a line that the app will later score as its loss.

## Why Fairy-Stockfish

- It loads custom chess variants.
- `stalemateValue` models Drawless and Escape stalemate rules.
- `nFoldRule` and `nFoldValue` make an avoidable third repetition losing.
- UCI provides a narrow, testable protocol boundary.
- Search strength can be limited for named and approximate-Elo bot levels.

## Forced-repetition exception

Configuration alone cannot express this rule:

> The player completing the third occurrence loses unless every legal move available
> would complete it; then the opponent who forced the cycle loses.

The first ordered Drawless patch, introduced with interface v1, classifies the full legal sibling
set at the parent before TT cutoffs and
search pruning. A mixed set retains the configured loss for a completing move. If every
legal move completes occurrence three, the mover receives a variant-aware mate-distance
win. The implementation covers root, PV, non-PV, and quiescence search and preserves the
exact third occurrence rather than Fairy-Stockfish's ordinary after-root cycle shortcut.

The custom `drawlessForcedRepetition = true` attribute activates this behavior on the
Drawless base variant, so Escape inherits the same repetition law.

## Full RulesContractV1 search parity

Interface v2 carries the complete contract-v1 policy surface into every search request:

- `UCI_Variant=drawless|escape` selects the preset and its opposing stalemate outcome;
- `Drawless Dead Position` selects material victory or terminal-mover victory, serialized as
  `final-capture-victory` for compatibility;
- `Drawless Fifty Move` selects disabled, completing-player loss, forced-move exception, or
  material victory; and
- `Drawless Bare King` selects continuation or bare-king loss.

The patch models the core's conservative known-dead detector, complete legal sibling set for the
50-move forced exception, material and last-capturer tie breakers, and the exact outcome order:
no legal move, repetition, bare king, known dead position, then 50 moves. Contract v1 itself fixes
the repetition threshold and polarity, forced-repetition exception, and standard 1/3/3/5/9
material weights. Kotlin rejects any request outside those immutable v1 invariants.

The terminal-mover policy normally awards the final capturer. Standard chess has one quiet edge:
a bishop or knight underpromotion can itself create a known-dead position. Contract v1 awards that
promoting mover as the deterministic fallback; the app and native search use the same rule.

The ordered `0004-preserve-drawless-deeper-search-boundaries.patch` carries those outcomes through
selective main search and quiescence. It exempts an exact terminal-creating move from null-move,
ProbCut, futility, history, move-count, SEE, and capture-only filtering; quiescence also evaluates
mixed immediate terminal sets and every no-legal child before stand-pat. These speculative probes
do not count as searched nodes. Synthetic null moves do not advance the Drawless
halfmove clock or last-capturer history, and repetition keys retain en-passant only when a legal
capture exists. Terminal children cannot seed a ponder move, and Syzygy root ranking remains
orthodox-chess-only.

## Source and binary identity

Production Drawless interface version 2 is tied to these immutable source identities:

- Upstream commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patched result tree: `bf58452cf6bb2254050e7aa442d2b23f3664aaec`
- Fourth-patch SHA-256:
  `22c8327ed64a2d7711695d372d817a6e037bca5fdbfc24ad812c1e869e59bedd`
- Ordered patch-series SHA-256:
  `7501f6322ee73b9d737c387e32f1c45cb6de7d0cb3b67648601e0236fca799ed`
- Variant configuration SHA-256:
  `0570d4805f915c2c77228babc31e127c4155413dba4d335ccb527dc2b974d28f`

The patch, ordered `series`, checksums, manifest, and verifier live in
`engine/patches/`. The binary advertises:

```text
option name Drawless Patch Version type spin default 2 min 2 max 2
```

The current option surface, identities, fixture matrix, and reproducible verification procedure
are recorded in `engine/patches/README.md`. `docs/FORCED_REPETITION_PATCH.md` retains the
historical interface-v1 design record.

## Transposition-table decision

Repetition outcomes depend on the move history, but upstream TT keys identify the board state.
The v1-v2 patch series therefore never trusts or stores TT bound scores while the Drawless rule is
enabled. It may use a TT move for ordering and retain a bound-free static board
evaluation. This policy is broader than the minimum immediate forced position, but it
prevents a deeper history-derived score from leaking through an ancestor.

Adding reusable bound scores requires a verified repetition-history signature and a
new review of binary compatibility and patch versioning. Correctness takes priority over
this optimization for interface version 2.

## Verification scope

The clean Linux x86-64 verifier passed against the identities above and established:

- Unpatched forced baseline is `mate -1`; patched forced result is `mate 1`.
- Black and White forced completers both win.
- Black and White avoidable completers both evade their losing repeat.
- Hash-size changes and identical-board/different-history searches do not leak scores.
- `ucinewgame`, UCI `stop`, and a subsequent search remain isolated.
- The advertised patch option matches the Kotlin engine contract exactly.
- Every selectable dead-position, 50-move, and bare-king value is exercised for both colors.
- Material, last-capturer, and forced-move tie breakers are policy discriminators rather than
  result-text-only checks.
- Both-color precedence conflicts cover stalemate over lower policies, repetition over lower
  policies, bare king over dead position and 50 moves, and dead position over 50 moves.
- A halfmove-98 quiet branch protects the deeper quiescence/search boundary before a future
  50-move terminal.
- Both-color quiet stalemates beyond the sparse material frontier survive selective pruning under
  Drawless, while Escape avoids the losing stalemate continuation.
- Mixed repetition/dead-position reply sets, last-piece captures, and quiet bishop/knight
  underpromotions survive both main-search and quiescence pruning.
- A direct native `Position` state harness verifies null-history ownership, legal-only
  en-passant repetition keys, and node-neutral speculative probes without relying on an
  ambiguous final UCI score.
- Terminal-child ponder extraction is suppressed, and non-orthodox variants never enter Syzygy
  root ranking.
- Mandatory Drawless terminals at the UCI root publish no legal root moves and
  `bestmove (none)`, preventing a caller from searching beyond an app-terminal position.

The exact Android candidate must still pass the packaged native/instrumentation matrix and be
installed, launched, and engine-verified on both designated physical devices. A clean host pass
does not transfer to different APK bytes.

The older npm/WASM experiment remains intentionally unpatched and covers only the
configuration-supported stalemate and avoidable-repetition branches. Its package version
is not a substitute for the pinned native source identity.

## Engine API

Each request includes:

- Request, game, and position identifiers
- FEN plus complete UCI move history from the saved-game root
- Rules contract version and exact policies
- Difficulty/Elo controls and time budget
- Optional analysis constraints such as MultiPV

Each response includes:

- Matching identifiers
- Best move, ponder move, score, depth, nodes, and principal variations
- Engine build identifier and Drawless patch version
- Terminal classification when applicable

The JVM protocol and transport contracts are testable without Android. Passing those
tests does not prove native loading or process behavior on an Android device.

## Android packaging

The private-test adapter uses a narrow in-process JNI boundary; the core interface still
permits a controlled native worker if future isolation requirements change. UI code does
not see native handles, raw UCI text, or engine threads. The intended first ABIs are
`arm64-v8a` for devices and `x86_64` for emulators; additional ABIs require explicit
device justification and testing. `docs/ADR-003-ANDROID-ENGINE-RUNTIME.md` records the
accepted runtime decision and licensing gate.

The implementation evidence now includes Android NDK builds, both packaged ABIs, ART JNI
load/search/close/restart, APK assembly, and independent x86-64 emulator and ARM64 physical-
device runs. Signed distribution and resilience/performance evidence remain separate gates.

## Licensing checkpoint

Fairy-Stockfish and the derived patch are GPL-3.0-or-later. Drawless Chess has selected
GPL-3.0-or-later for the combined application, so the engine interface is not relied on as
a licensing workaround. No public APK should ship until the exact whole-project
corresponding source, required notices/SBOM, public source URL, signing setup, and release
evidence satisfy `docs/RELEASE_LICENSING.md`.

Official project and license:

- https://github.com/fairy-stockfish/Fairy-Stockfish
- https://github.com/fairy-stockfish/Fairy-Stockfish/blob/master/Copying.txt

## Rejected alternatives

- **Reverse standard draw results in UI:** strategically incorrect during search.
- **Write a chess engine from scratch:** unnecessary risk and a much weaker initial bot.
- **Use remote engine service:** violates offline-first product requirements.
- **Fork without an adapter boundary:** couples saved games and UI to engine implementation.
