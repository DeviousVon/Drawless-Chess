# ADR-001: Fairy-Stockfish integration and Drawless search semantics

Status: accepted; protocol, pinned patch v1, Android native runtime, and private-test
packaging/runtime matrix implemented and verified

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

Patch v1 now classifies the full legal sibling set at the parent before TT cutoffs and
search pruning. A mixed set retains the configured loss for a completing move. If every
legal move completes occurrence three, the mover receives a variant-aware mate-distance
win. The implementation covers root, PV, non-PV, and quiescence search and preserves the
exact third occurrence rather than Fairy-Stockfish's ordinary after-root cycle shortcut.

The custom `drawlessForcedRepetition = true` attribute activates this behavior on the
Drawless base variant, so Escape inherits the same repetition law.

## Source and binary identity

Production patch version 1 is tied to these immutable source identities:

- Upstream commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patched result tree: `090d26be47498b99a23fdb1b9ff7587740b95664`
- Patch SHA-256: `5bf9ec8dbd1254ed48bc6d29c92741c56c776124e87ef39b36f4a5d14b416ca2`

The patch, ordered `series`, checksums, manifest, and verifier live in
`engine/patches/`. The binary advertises:

```text
option name Drawless Patch Version type spin default 1 min 1 max 1
```

The exact rule, identities, fixture positions, and reproducible verification procedure
are recorded in `docs/FORCED_REPETITION_PATCH.md`.

## Transposition-table decision

Repetition outcomes depend on the move history, but upstream TT keys identify the board
state. Patch v1 therefore never trusts or stores TT bound scores while the Drawless rule
is enabled. It may use a TT move for ordering and retain a bound-free static board
evaluation. This policy is broader than the minimum immediate forced position, but it
prevents a deeper history-derived score from leaking through an ancestor.

Adding reusable bound scores requires a verified repetition-history signature and a
new review of binary compatibility and patch versioning. Correctness takes priority over
this optimization for version 1.

## Verified scope

The clean native verifier establishes the following on Linux x86-64:

- Unpatched forced baseline is `mate -1`; patched forced result is `mate 1`.
- Black and White forced completers both win.
- Black and White avoidable completers both evade their losing repeat.
- Hash-size changes and identical-board/different-history searches do not leak scores.
- `ucinewgame`, UCI `stop`, and a subsequent search remain isolated.
- The advertised patch option matches the Kotlin engine contract exactly.

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
- **Write a chess engine from scratch:** unnecessary risk and much weaker version 1 bot.
- **Use remote engine service:** violates offline-first product requirements.
- **Fork without an adapter boundary:** couples saved games and UI to engine implementation.
