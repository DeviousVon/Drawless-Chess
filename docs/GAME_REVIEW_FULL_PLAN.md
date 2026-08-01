# Full game review roadmap

**Status:** active implementation plan

**Baseline:** the Android beta analyzes only the player's decisions from a completed live game,
streams finished move grades, shows a short suggested line and better-move arrow, keeps opponent
moves as neutral board context, and retains one completed result in `GameRuntime` memory. Exact
player roots and played-position fallbacks completed during safe foreground thinking time are
reused after the game, and any remainder begins behind the result presentation. Review work binds
to a dedicated `:review_engine` Android process instead of sharing gameplay's native singleton or
coordinator launch gate. Native patch v2 now receives and searches the exact stored
`RulesContractV1` policy surface.

**Target:** a trustworthy, useful, offline review that survives normal Android lifecycle events,
can be reopened from game history, and is explicit about the limits of its accuracy score.

This plan deliberately separates review evidence, rule correctness, derived scoring, presentation,
and persistence. A polished screen must not turn incomplete or policy-blind engine output into a
stronger claim than the evidence supports.

## Implemented foundation

The first preparation slice now supplies:

- a preliminary, versioned in-memory evidence schema (schema 1), separate analysis and
  grading-policy identities, explicit full contract-v1 native-rule fidelity, coherent same-depth MultiPV 3
  candidates, optional WDL/depth evidence, same-root played-line grading, and conservative
  handling of missing or bounded scores;
- explicit effective best/played lines with their evidence origins, full principal-variation
  replay through core chess and app adjudication, and fail-closed handling of illegal or
  post-terminal engine lines;
- a fix preventing an avoidable terminal loss from being labeled Best solely because the engine
  returned a very negative centipawn score;
- a player-only raw grade summary, localized **Review my mistakes** action, neutral opponent
  context, and a translucent orientation-aware better-move arrow, without presenting an
  uncalibrated accuracy percentage;
- sparse player-only work planning, dynamic adjacent helpers, immutable per-move streaming, exact
  seeded root/fallback reuse, coordinator-owned foreground pre-analysis in the isolated review
  process, current-position root preparation without repeated full-history replay, at most one
  historical fallback attempt per player-position revision, and result-screen finalization before
  the Review action;
- runtime-owned `StateFlow` analysis state, saved board orientation/selected ply, and lifecycle
  coverage so activity recreation does not cancel or restart an active review;
- polite TalkBack announcements when move selection changes; and
- a versioned patch-v2 Kotlin/UCI bridge plus native searched-node implementations for every
  selectable contract-v1 policy, fixed invariant validation, and the documented terminal order.
  Review analysis version 2 requires the exact native patch-v2 identity and full-rule fidelity on
  every response and seeded root; identity, build, request, or replay drift fails the review closed.

This is foundation work toward Review Evidence V2, not completion of Gate 0 or permission to
remove Beta. Constrained-root played evidence, the final fingerprinted and serializable evidence
contract, calibrated accuracy, durable storage/history, and the remaining acceptance fixtures are
still required below. Clean native proof now passes; exact designated-device proof will close Gate
1 only. Gates 0 and 2-5 remain open, so the Game Review UI remains Beta.

## Delivery order and effort

Estimates are engineering days for one developer starting from the current beta. They include
focused automated tests but exclude release-branch stabilization and waiting for physical-device
availability.

| Gate | Deliverable | Estimate/status | Hard exit condition |
| --- | --- | --- | --- |
| 0 | Evidence V2 | 2-4 days | Every graded move has validated, versioned best-move and played-move evidence. |
| 1 | Full app-rule parity | Host verified; exact optimized-device proof open | Search ranks lines under the exact stored rules contract, not a preset approximation. |
| 2 | Complete in-session experience | 2-4 days | Player summary, useful full-game context/navigation, and deterministic explanations work on phone and tablet. |
| 3 | Rotation-safe ownership | 1-2 days | Rotation does not cancel, duplicate, or restart analysis, and UI position is restored. |
| 4 | Complete-result persistence and history | 3-6 days | A finished review reopens after a new game or process restart; schema migration preserves existing data. |
| 5 | Beta exit verification | 2-4 days | Gates 0-4 and the full acceptance matrix pass on the exact candidate. |
| 6 | Optional background partial resume | 4-8 days | Interrupted partial work resumes safely without weakening cache or engine-session invariants. |

The native rule-parity implementation removes the largest search-correctness uncertainty from the
original estimate. A defensible non-Beta review still requires the independent evidence,
experience, persistence, and final-verification work below. Gate 6 is not required for the
initial full release.

## Current constraints

- `android/core/src/main/kotlin/com/drawlesschess/core/engine/GameReview.kt` now prefers same-root
  MultiPV evidence and explicitly records an adjacent-position fallback when needed. Every
  retained line is replayed through the matching `GameSession`. Evidence and grading are
  versioned, but played moves outside MultiPV still need constrained-root evidence and accuracy
  has not been calibrated or versioned.
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/FairyUciEngine.kt` requires patch v2,
  selects the Drawless or Escape preset, and sends dead-position, 50-move, and bare-king values on
  every request. The v1 contract fixes repetition at three, completing-player loss, the forced
  exception, and standard 1/3/3/5/9 material values; unsupported invariants fail before search.
- `android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt` owns active review state in a
  `StateFlow`, so configuration recreation can detach and reattach without restarting analysis.
  It still holds only one in-memory result; Home, Quick Play, Rematch, or process death loses that
  cache. Its `IsolatedReviewEngine` client sends review-only work to `ReviewEngineService` in the
  `:review_engine` process; gameplay and hints retain the main-process engine.
- `android/app/src/main/kotlin/com/drawlesschess/ui/GameReviewScreen.kt` shows player-only grade
  counts and mistake navigation, keeps opponent moves selectable without exposing their grades or
  evaluations, merges streamed player results into the full timeline, and draws the suggested
  move on the board. It deliberately shows no uncalibrated accuracy value.
- `GameCoordinator` may pre-analyze the current player root only while the game is visible and on
  the player's turn. It builds the root from the already-validated coordinator position rather than
  replaying the complete game on every turn. After that root completes, remaining idle time may
  warm at most one exact adjacent fallback for an earlier off-MultiPV player move in that position
  revision, reconstructed from the completed root's stable key. Gameplay and review have separate
  invocation gates; lifecycle/game mutation still cancels stale review work, and `GameRuntime`
  reuses evidence only through exact versioned root plus played-move/resulting-position keys.
  Foreground terminal games start any remainder during result presentation. This is foreground
  work, not WorkManager or unrestricted background execution.
- Room schema 2 already stores canonical completed-game inputs in `CompletedGameEntity`: initial
  FEN, ordered UCI moves, the exact rules snapshot, outcome, player side, and opponent identity.
  There is no public history/review repository or persisted review row.

## Gate 0: Evidence V2

Evidence V2 is a versioned domain and serialization contract, not the Room database version. It
must be stable before accuracy, explanations, or persistence are built on top of it.

### Contract

Introduce `ReviewEvidenceV2`, `MoveEvidenceV2`, `CandidateEvidence`, and
`ReviewAnalysisProfile` in a new
`android/core/src/main/kotlin/com/drawlesschess/core/engine/GameReviewEvidence.kt`. A complete
record contains:

- a canonical completed-game fingerprint and exact rules fingerprint;
- evidence schema version `2`, engine identity/build/Drawless patch identity, and the complete
  evaluation-affecting analysis profile;
- for each ply: mover, played UCI move, a stable position/repetition key, authoritative app outcome
  after the move, and an explicit completeness state;
- best and played candidates normalized once to the mover's perspective, each with root move,
  evaluation, legal principal variation, score bound, depth, and nodes;
- coded terminal/adjudication facts suitable for later explanation, never localized prose.

For a non-best played move, obtain played evidence from a constrained root search under the same
profile, for example by adding an optional `searchMoves` field to `EngineRequest` and emitting UCI
`go ... searchmoves <playedMove>`. Reuse best evidence only when the played move is the proven best
root move. Do not silently treat a missing scored `info` line, an upper/lower-bound score, an
illegal PV, or a cancelled request as exact evidence.

`GameReviewRunner` may stream completed `MoveEvidenceV2` records, but a summary is complete only
after every required work unit succeeds. Cancellation must produce no completed
`ReviewEvidenceV2`; a partial record has a distinct type/state and cannot enter the complete cache.

### Primary implementation files

- `android/core/src/main/kotlin/com/drawlesschess/core/EngineApi.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/UciProtocol.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/FairyUciEngine.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/AnalysisRequests.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/GameReview.kt`
- new `android/core/src/main/kotlin/com/drawlesschess/core/engine/GameReviewEvidence.kt`

### Acceptance tests

Add focused cases to `android/core/src/test/kotlin/com/drawlesschess/core/EngineLayerTests.kt` and,
where UCI syntax is involved, `NativeBridgeTests.kt`:

- played-best reuse and non-best constrained-root analysis;
- mover-perspective normalization for both colors, centipawns, mate, and terminal values;
- exact versus upper/lower-bound scores and missing-score failure;
- illegal best move or PV rejection with the exact failing ply;
- cancellation, stale response, retry identity, and progress work-unit accounting;
- identical input/profile produces an identical fingerprint; every evaluation-affecting change
  changes it;
- complete evidence round-trips without localized text or duplicated canonical game history.

## Gate 1: full app-rule parity

**Status:** patch-v2 implementation and clean native verification complete; Gate 1 remains open
until the exact clean, optimized Android engine candidate passes on the designated Pixel phone and
R6 tablet. The owner-accepted earlier debug APK and emulator/R6 adapter harness are valuable scoped
evidence, but neither verifies the later 357-test worktree as that exact candidate.

The Beta label had to remain while search could recommend or score a line under rules different
from the recorded game. Patch v2 corrects that inside searched nodes rather than changing only the
final played move after search.

Search must evaluate the full immutable `RulesContractV1`, including:

- Drawless versus Escape stalemate;
- third-occurrence loss and its forced-move exception;
- `bareKing` continue versus loss;
- both dead-position policies, including terminal-mover victory for a quiet bishop/knight
  underpromotion that creates a known-dead position;
- every configured 50-move policy, material comparison, last-capturing-side tie break, and
  forced-move exception;
- fixed contract-v1 material values and the documented outcome precedence.

The implemented interface requires native Drawless patch v2, complete move history, the exact
Drawless/Escape preset, and explicit dead-position, 50-move, and bare-king options. It models the
known-dead detector, full-legal-set forced exceptions, material and last-capturer tie breakers, and
the `GameSession` terminal order at searched nodes. The Kotlin bridge rejects a schema or fixed
invariant the native interface cannot represent; it never falls back to preset-only search.

The ordered `0004-preserve-drawless-deeper-search-boundaries.patch` closes the selective-search
holes below that interface. It preserves last-piece captures, quiet bishop/knight underpromotion,
50-move boundary moves, mixed immediate terminal sets, and quiet stalemates beyond the sparse
material frontier across main-search and quiescence pruning. It also keeps synthetic null moves
out of Drawless clocks/last-capturer history, keys en-passant only when legally capturable, keeps
speculative probes node-neutral, suppresses ponder extraction after a terminal child, and keeps
Syzygy root ranking out of custom variants.

### Primary implementation files

- `engine/patches/` and its series, manifest, and checksums
- `engine/variants.ini`
- `android/core/src/main/kotlin/com/drawlesschess/core/engine/FairyUciEngine.kt`
- `android/engine/src/main/kotlin/com/drawlesschess/engine/AndroidFairyEngineFactory.kt`
- `android/engine/src/androidTest/kotlin/com/drawlesschess/engine/AndroidFairyEngineInstrumentedTest.kt`
- `android/engine/src/androidTest/kotlin/com/drawlesschess/engine/DrawlessSelfPlayInstrumentedTest.kt`
- patch verification scripts under `engine/patches/`

### Acceptance tests

- A fixture matrix compares native search outcomes/rankings with `GameSession` for every policy,
  precedence boundary, both colors, voluntary and forced alternatives, and legacy
  `bareKing = CONTINUE` histories.
- At least one fixture per policy proves that a policy-blind engine would choose the wrong move;
  this prevents a test suite that verifies only final result text.
- Best and played PVs are replayed through core chess and app adjudication without divergence.
- Hash/transposition sizes, stopped-search follow-up, and repeated runs do not reuse a score from a
  different history or rules fingerprint.
- A direct native-state harness proves null-history ownership and legal-only en-passant keys;
  deeper UCI fixtures cover main/quiescence pruning, mixed terminal intersections, and both-color
  quiet bishop/knight underpromotion.
- The clean source/patch verifier passes, both production ABI artifacts advertise the new identity,
  and the exact candidate is installed, launched, and engine-verified on the designated Pixel
  phone and R6 tablet.

## Gate 2: complete in-session experience

Build derived review models from Evidence V2 in a new
`android/core/src/main/kotlin/com/drawlesschess/core/engine/GameReviewSummary.kt`:

- `GameReviewSummary` and `SideReviewSummary`;
- player accuracy and Best/Good/Inaccuracy/Mistake/Blunder counts;
- issue plies, strongest move, worst move, and scored/unscored counts;
- a stable Player evaluation perspective, never an unexplained alternating sign;
- deterministic `ReviewExplanationFacts` for immediate win/loss, mate/check, material change,
  capture, forced/only move, and every Drawless-specific terminal reason.

Update `android/app/src/main/kotlin/com/drawlesschess/ui/GameReviewScreen.kt` to provide:

- a summary showing result, player side, player accuracy, and player grade counts;
- a clear **Review my mistakes** action that jumps to the first applicable player issue;
- Summary and Moves destinations, first/last move, adjacent move, previous/next issue, and a
  player-moves filter;
- grade-count and key-moment links that select the corresponding ply;
- localized factual explanation, better move, short legal line, and explicit evaluation
  perspective;
- retry for retryable engine/session failures, actionable non-retryable error copy, and no final
  accuracy while evidence is incomplete.

Pass player statistics and `OpponentProfiles.forLevel(runtime.opponentLevel)` from
`android/app/src/main/kotlin/com/drawlesschess/ui/DrawlessApp.kt`. Add every new string to
`values`, `values-de`, `values-fr`, `values-b+es+419`, and `values-pt-rBR`.

Suggested-line playback on the board is valuable but may follow the summary gate. If included, it
must be an explicit Played/Suggested mode; it must never make the suggested branch look like the
recorded game.

### Accuracy contract

Freeze the formula behind a named integer version such as `DRAWLESS_ACCURACY_V1`. Its inputs are
only complete, exact Evidence V2 expected-point losses. The formula and aggregation must be
deterministic, bounded to 0-100, monotonic as loss increases, color symmetric, and explicit about
how forced moves and unscored moves are handled. A side with no scorable moves displays
**Not enough analysis**, not 0 or 100. Formula calibration and golden fixtures happen before the
version is frozen; changing the curve later requires a new version.

The app may claim that the score:

- is Drawless Chess's local Game Review accuracy for this game and review version;
- summarizes expected-point loss under the identified offline engine, rules, and analysis profile;
- is presented only for the player; opponent analysis is not a product output.

The app must not claim that the score:

- uses, matches, or is interchangeable with Chess.com's proprietary accuracy formula;
- is an Elo, performance rating, objective proof, or tablebase-perfect verdict;
- is directly comparable across engine, patch, rule, analysis-profile, or accuracy-version changes;
- proves a time-limited engine choice is the unique objectively best move;
- includes moves lacking exact Evidence V2.

Keep concise limitations available from an information affordance. Removing **Beta** later does
not remove these permanent limits.

### Acceptance tests

- `EngineLayerTests.kt`: formula bounds/monotonicity, both-color symmetry, forced moves, mate and
  terminal values, empty/unscored sides, grade totals, issue ordering, and explanation facts for
  every app outcome.
- `GameReviewInstrumentedTest.kt`: correct Player/opponent side mapping, summary totals, accuracy
  unavailable while incomplete, Summary/Moves navigation, grade-count jump, player filter,
  previous/next issue, retry states, stable evaluation wording, TalkBack semantics, and 200% font.
- Existing compact phone and tablet layout tests continue to keep the board and review controls
  reachable in portrait and landscape.

## Gate 3: rotation-safe ownership

Move analysis ownership out of the route composable. Introduce an app-level
`GameReviewRuntime`/`GameReviewCoordinator` and immutable `GameReviewScreenState`; let
`DrawlessAppViewModel` retain the owner across configuration changes. `GameReviewScreen` remains a
parameter-only renderer.

The owner holds the attempt generation, cancellation, streamed evidence, complete result, and
retryable failure. Only lightweight UI choices such as selected ply, Summary/Moves destination,
filter, branch mode, and board orientation use `SavedStateHandle` or `rememberSaveable`. Never
parcel an engine, cancellation handle, or full review payload.

Back behavior must be explicit: leaving the screen may cancel an incomplete foreground review,
but configuration change must not. A retry creates fresh request identities and, after a terminal
engine failure, a fresh engine session where required.

### Primary implementation files

- new `android/app/src/main/kotlin/com/drawlesschess/ui/GameReviewRuntime.kt`
- `android/app/src/main/kotlin/com/drawlesschess/ui/GameRuntime.kt`
- `android/app/src/main/kotlin/com/drawlesschess/ui/DrawlessAppViewModel.kt`
- `android/app/src/main/kotlin/com/drawlesschess/ui/DrawlessApp.kt`
- `android/app/src/main/kotlin/com/drawlesschess/ui/GameReviewScreen.kt`

### Acceptance tests

- Activity recreation during analysis submits no duplicate request, preserves progress, and
  receives exactly one completion.
- Recreation after completion performs no new search and preserves selected ply, destination,
  filter, orientation, and branch mode.
- Back, cancel, retry, engine failure, and rapid repeated navigation have deterministic ownership
  and no stale callback can mutate the new attempt.
- `RepeatedGameLifecycleInstrumentedTest.kt` proves review state cannot leak into a rematch or
  Quick Play runtime.

## Gate 4: complete-result persistence and history

Persist complete review evidence independently of immutable completed-game facts. Add a
`GameReviewEntity` in a new
`android/app/src/main/kotlin/com/drawlesschess/persistence/GameReviewPersistence.kt`, keyed by
`game_id` and linked to `completed_game`. A practical row contains the canonical game fingerprint,
Evidence V2 payload, evidence cache key, engine/profile metadata, derived-review key and version
numbers, completed timestamp, and optionally a compact derived summary. Store UCI/evaluation data,
not FEN/SAN duplication or localized explanation strings.

Advance `DrawlessDatabase` from version 2 to 3 in
`android/app/src/main/kotlin/com/drawlesschess/persistence/RoomCheckpointStore.kt`, add and register
`MIGRATION_2_3`, and export schema 3. Saving a complete review is an atomic, idempotent operation.
Cancelled and failed attempts write no complete row.

Expose repository operations to list completed games, load one canonical history, load a matching
review, and save a complete review. Add a validated completed-game decoder for `initialFen`,
`movesJson`, `rulesJson`, outcome, side, and opponent identity. Historical analysis must provision
its own review engine owner rather than reconstruct a playable `GameRuntime`.

Add `HISTORY` and historical `REVIEW` state to `DrawlessAppViewModel`/`DrawlessApp`, plus a new
`android/app/src/main/kotlin/com/drawlesschess/ui/GameHistoryScreen.kt`. History rows show result,
opponent, date, sides, and review state; a matching completed review opens instantly, while a
missing or stale review offers analysis.

### Versioned cache keys

Use canonical, length-delimited encoding before SHA-256; never hash locale-rendered or
order-unstable JSON.

`EvidenceCacheKey` includes:

1. canonical game-history fingerprint: record format, normalized initial FEN, ordered UCI moves,
   exact rules contract, and recorded outcome;
2. evidence schema version;
3. engine ID, build, Drawless patch/parity identity;
4. every evaluation-affecting analysis setting, including move time or node/depth limit,
   MultiPV, constrained-root policy, and retained PV length.

`DerivedReviewKey` includes the Evidence V2 payload digest plus classifier, grade-threshold,
accuracy, summary, and explanation-fact versions. Locale and UI theme are excluded because copy is
rendered at read time.

An evidence-key mismatch makes the expensive cache stale and requires reanalysis. A derived-key
mismatch recomputes summary/accuracy/explanation from compatible evidence without rerunning the
engine. Unknown versions are ignored for display but preserved until a deliberate retention policy
removes them; they are never silently interpreted as current.

### Acceptance tests

Extend `RoomCheckpointStoreInstrumentedTest.kt` and add focused history tests:

- schema 2 to 3 migration preserves active checkpoints, profiles, every completed game, stats,
  settings, and adaptive rating history;
- complete review round-trip, database reopen, atomicity, idempotent exact retry, and conflicting
  payload rejection;
- evidence-key and derived-key invalidation paths behave differently as specified;
- malformed/unknown payload versions fail closed without deleting completed-game history;
- starting Quick Play or Rematch and process restart do not lose a completed review;
- history ordering, opponent/side mapping, reviewed/unreviewed state, instant reopen, and legal
  replay from a nonstandard initial FEN.

## Gate 5: removing Beta

Do not remove the Beta label merely because the summary is attractive. It can be removed only when:

- Gates 0-4 are complete;
- the full app-rule parity matrix passes against the exact production engine artifacts;
- accuracy and every persisted format/cache-key component have frozen version identifiers;
- every review PV replays legally and no app outcome conflicts with the engine's rule model;
- cancellation, retry, rotation, process restart, migration, history reopening, long games, and
  low-memory behavior pass;
- English, French, German, Latin American Spanish, and Brazilian Portuguese pass phone/tablet,
  portrait/landscape, 200%-font, and TalkBack checks;
- the exact test build is installed, launched, and reviewed on both designated devices: the Pixel
  phone and R6 tablet;
- Bob accepts the wording, accuracy presentation, and overall interaction on staging.

The implementation release must also update the existing architecture/runtime documentation and
release evidence for any new native patch. Those documentation changes happen with the code, not
as part of this planning file.

## Gate 6: optional background partial resume

Only after complete-result persistence is proven should partial work become durable. Store a
separate partial Evidence V2 payload with its exact evidence cache key, validated completed work
units, and next work unit. Replace it atomically as work advances; promote it to a complete row only
after full validation.

Use WorkManager only after testing Android background CPU, battery, and process restrictions with
the native engine. The worker and foreground UI must share a single ownership/lease protocol so
they cannot search the same game concurrently. User cancellation removes or marks only the partial
attempt and never deletes a prior complete compatible review.

Acceptance requires kill/restart resume, stale-key rejection, truncated/corrupt partial recovery,
worker retry/backoff, battery/thermal constraints, foreground takeover, cancellation races, and
proof that exactly one engine session owns each attempt.

## Definition of done

A full Game Review is done when it is rule-correct, evidence-backed, understandable, lifecycle
safe, and durable. An evaluation graph, generated tactical prose, cloud synchronization, and
background partial resume are enhancements; none may substitute for Evidence V2 or exact app-rule
parity.
