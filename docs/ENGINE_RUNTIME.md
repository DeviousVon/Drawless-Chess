# Production engine-facing layer

Status: protocol, transport, in-process Android JNI endpoint, factory, and app wiring implemented;
the clean patch-v2 host verifier passed, while the exact Android candidate still requires
designated-device verification

## Scope completed

The Kotlin core has a platform-neutral Fairy-Stockfish boundary under `core/engine`, and
the `:engine` module now implements its private-test Android JNI endpoint. The protocol,
transport, and most endpoint lifecycle behavior can be tested on the JVM without an
Android SDK or native binary.

- Strict parsing for engine identity, options, readiness markers, `info`, scores,
  MultiPV, WDL, PV moves, and `bestmove`.
- Forward-compatible handling of unknown lines and unknown `info` tokens.
- Explicit startup, configuration, search, cancellation-drain, failure, and close states.
- Handshake, readiness, search, and cancellation-drain timeouts.
- One active request plus one queued request while a cancelled command is draining.
- Exact `RulesContractV1` preset, dead-position, 50-move, and bare-king configuration, plus
  strength, MultiPV, analysis mode, and tablebase 50-move configuration.
- Runtime verification of the engine's Drawless patch version.
- Conversion from UCI output to the tagged `EngineResponse` used by `GameCoordinator`.
- A byte-oriented `NativeEnginePort` contract that can be implemented by a JNI-backed
  endpoint or a separately controlled process without changing core game code.
- Strict incremental UTF-8 line framing with LF/CRLF support, bounded input, and
  rejection of malformed text, NUL, and bare carriage returns.
- `SerializedNativeUciTransport`, which queues bounded startup commands, permits only
  one native write in flight, preserves FIFO order, separates stderr diagnostics from
  UCI stdout, and has explicit close/crash behavior.
- ABI manifest and artifact models with device-preference selection, minimum-API checks,
  safe library basenames, sizes, and SHA-256 verification.
- `NativeFairyEngineSession`, which composes the byte transport and strict UCI session
  behind the existing `ChessEngine` interface.
- `JniFairyEnginePort`, an in-process Android implementation of `NativeEnginePort` with
  explicit open, running, closing, failed, and closed states.
- `AndroidUciTimeoutScheduler`, owned and closed by one Android engine session.
- `VariantConfigInstaller`, which copies the packaged variant configuration to versioned
  private no-backup storage, enforces path containment and a size limit, verifies the
  build-locked SHA-256, and protects the installed file before native startup.
- `AndroidFairyEngineFactory`, which owns installation, JNI-port construction, timeout
  scheduling, build/patch identity, and session cleanup.
- `IsolatedReviewEngine`, which accepts review requests only and binds through Android
  `Messenger` IPC to `ReviewEngineService` in the dedicated `:review_engine` app process. Client
  request serialization and replies run on a private `HandlerThread`, not the gameplay UI looper.

The `:engine` module pins the upstream and patched source identity, declares the
`drawless_fairy` CMake target for `arm64-v8a` and `x86_64`, stages legal/source material,
and exposes a six-operation JNI ABI: create, start, write, stdout read, stderr read, and
close. JNI methods are registered from `JNI_OnLoad`; native code never calls back into
Kotlin. UI and game-law code never receive raw UCI text or native handles.

`:app` now depends on `:engine` and selects `AndroidFairyEngineFactory` by default for gameplay and
hints. Review work uses the same verified factory inside `ReviewEngineService`; the service's
separate Android process owns different Fairy process globals, and `GameCoordinator` gives review
calls a different launch gate. The development bot can be selected only for an explicit debug build with
`-Pdrawless.useDevelopmentEngine=true`; release hardcodes that selection off. A native
startup/linkage failure is logged, displayed through the existing controller/bot-error
path, and represented by a non-playing failed engine. It never silently changes opponents.

## Lifecycle

1. Send `uci`; collect identity and every advertised option.
2. Require `uciok`, then send `isready` and require `readyok`.
3. For a request, validate every immutable contract-v1 invariant, the selected policy values,
   MultiPV, Elo/skill range, patch identity, and exact option declarations.
4. Set `UCI_Variant`, `Drawless Dead Position`, `Drawless Fifty Move`, `Drawless Bare King`,
   `MultiPV`, analysis mode, tablebase policy, and strength.
5. Send `ucinewgame` only when the game identity changes, followed by `isready`.
6. Send the initial FEN plus the complete move history, then `go movetime`.
7. Retain the deepest line for each MultiPV rank and produce one tagged response.
8. On cancellation, send `stop` and drain the old `bestmove` before starting queued work.

Complete history is required: the engine cannot evaluate third occurrences correctly
from the current FEN alone.

`0004-preserve-drawless-deeper-search-boundaries.patch` protects contract-v1 results after normal
root handling. Main search and quiescence preserve terminal-creating last-piece captures, quiet
bishop/knight underpromotions, 50-move boundary moves, and mixed immediate terminal sets across
null-move, ProbCut, futility, history, move-count, SEE, and capture-only pruning. Synthetic null
moves do not change the Drawless halfmove or last-capturer history; repetition keys include only
legally capturable en-passant targets. Terminal-child ponder extraction is suppressed and Syzygy
root ranking is limited to orthodox chess.

At the lower boundary, `SerializedNativeUciTransport` may accept commands while its port
is starting. Once `NativeEnginePort.onStarted` arrives, it writes newline-framed commands
in order and waits for each completion before writing the next. Native stdout is framed
into complete UCI lines and passed to `FairyUciEngine`; native stderr is diagnostic-only.
An explicit app close is distinct from an unexpected zero exit, signal, malformed line,
write failure, or native contract violation.

For JNI, create/start and FIFO writes run on one managed command executor. Two separate
managed reader threads perform blocking stdout and stderr reads, while timeouts use a
session-owned scheduled executor. Kotlin calls native blocking functions only from these
workers; it does not rely on thread interruption to stop them. `close()` invokes the
native close primitive directly, which injects stop/quit, closes the bounded native byte
pipes, joins the Fairy worker, and wakes blocked native reads or writes before the managed
executors are shut down. The native bridge permits one in-process engine session per Android
process and separates the bounded stdin, stdout, and stderr channels. Production may therefore own
one gameplay session in the main app process and one review-only session in `:review_engine` without
sharing either native singleton.

## Failure policy

- Malformed known protocol lines fail and terminate the session.
- Unknown extension lines are ignored for compatibility.
- A transport exception or timeout fails outstanding work and terminates the session.
- A missing option, unsupported value, or wrong patch fails that request but leaves a
  healthy session available.
- `bestmove (none)` and `bestmove 0000` parse as terminal output. They are rejected when
  a live-position request expects a legal move.
- Consumer callback exceptions are isolated from the engine session.
- Command-count and byte-count limits reject excess queued work synchronously without a
  partial enqueue.
- Native open, write, framing, or close failures terminate the byte transport and are
  propagated through `NativeFairyEngineSession` to outstanding analysis.
- Explicit transport close is idempotent; late callbacks cannot revive a closed or
  crashed session.
- JNI startup failure closes the acquired handle and reports one termination. Unexpected
  stream EOF or either reader failure closes the native worker, and simultaneous failures
  are collapsed to a single termination.
- Factory-time installation, security, and JNI linkage failures are visible in Logcat and
  the game UI. Later handshake/compatibility failures surface with the first bot request;
  the current session API has no separate startup-failure observer.

The Android owner must create a fresh native session after a terminal session failure.
It must not silently retry a timed-out move after the game position has changed.

## Difficulty and analysis

Version 1 exposes all three agreed difficulty paths:

- Seven named levels using the beginner-focused target ladder: Learner 550, Casual 800,
  Challenger 1000, Club 1300, Expert 1675, Master 2100, and Grandmaster 2550.
- A custom approximate Elo from 500 through 2850.
- Adaptive difficulty targeting the relevant offline player rating.

The engine adapter maps approximate Elo to `UCI_LimitStrength` plus `UCI_Elo`, and maps
raw skill to Fairy-Stockfish's `Skill Level`. Values are checked against the options
reported by the actual binary rather than assumed.

The app presents Adaptive as Vesper, a distinct opponent identity. Vesper begins near 800,
freezes the current adaptive rating as the engine target for the entire game, and updates the
installation-local rating only after a completed game without hints, undo, pauses, or threat
indication. The first ten qualifying games use the provisional Elo update factor; subsequent
updates become progressively steadier. Rating-before and rating-after snapshots are appended
atomically with completed-game history, while every Vesper game remains grouped under the stable
`bot:adaptive` identity even though its exact engine Elo changes.

New checkpoints persist the named or adaptive level ID separately from its target Elo. Checkpoints
from the previous 600/900/1200/1500/1850/2200/2600 ladder infer identity only from an
exact historical value, preserving both the original opponent persona and the saved
engine strength. In particular, legacy Club 1500 remains Club after the current ladder moves
Expert to 1675. A present null ID remains a custom opponent and is never legacy-inferred.

These numbers are target/estimated Elo values, not hardware-independent measurements;
the production adapter sends the exact target through `UCI_LimitStrength` and `UCI_Elo`
with a 350 ms move budget. The patched engine uses floor-based stochastic rounding for
negative fractional skill levels, removing the prior systematic low-Elo strength bias.

For `BOT_MOVE`, the adapter returns Fairy-Stockfish's terminal UCI `bestmove` and optional
`ponder` exactly as emitted. Limited-strength Fairy may deliberately choose a weaker move than the
rank-one MultiPV line retained from the same search, so that analysis snapshot must never replace
the gameplay choice. Hint and review consumers may use their coherent rank-one analysis, but this
raw-move invariant applies to every named, adaptive, custom, historical-Elo, and raw-Skill gameplay
path and must be preserved by any port.

Hints are casual-only, full-strength MultiPV requests. `GameCoordinator` owns hint and bot
requests in one serialized gameplay slot because the native bridge permits one Fairy session per
process. Review uses a separate process and invocation lock, so a slow review bind, request
serialization, or cancellation publication cannot occupy the gameplay slot or block a move, hint,
or undo. While a hint runs the board enters `HINT_THINKING`; pause, undo, resign, timeout,
or runtime close cancels it, and tagged results are discarded if the position changed.
Hint failures return to the human turn without poisoning bot UI state. The app presents
the engine-ranked best move in SAN and, when available, up to two lower-ranked MultiPV
alternatives. Game review first validates the complete history, then builds full-strength roots
only for decisions made by the player. Opponent plies remain canonical, selectable board context
but are not graded or summarized. Each player-root search requests three candidate lines and
enables UCI WDL output when the engine advertises it. If the played move is outside those three
lines, the runner dynamically adds one adjacent-position helper rather than analyzing every
opponent decision. The preliminary
in-memory evidence schema (schema 1) preserves line rank, score bound, depth, WDL-derived expected
points, explicit best/played-line origin, separate analysis and grading-policy identities, and
exact native `RulesContractV1` fidelity. It is a foundation for the planned Review Evidence V2
contract, not that contract itself. A played move found in the same root MultiPV is compared there;
otherwise review falls back to the following position and normalizes that score to the mover. Missing, bounded,
contradictory, or unsafe line evidence is not given a confident grade.

Review retains one coherent MultiPV snapshot: every selected rank comes from the same completed
depth/reporting cycle and its primary move must match `bestmove`. Every retained PV is replayed
from its exact app position and history through `ChessRules` and `GameSession`; an illegal move or
a continuation beyond an app-authoritative terminal result fails the review instead of becoming a
recommendation. Effective best and played lines retain whether they came from root MultiPV,
adjacent-position normalization, an authoritative terminal fact, or a sole legal move.

The runner submits one 350 ms search at a time, assigns
Best/Good/Inaccuracy/Mistake/Blunder from expected-point loss, streams completed player decisions,
and supports cancellation, safe retry identities, exact seeded-root reuse, progressive results,
and a cached completed result. Natural terminal moves use the authoritative app outcome instead
of attempting to search a terminal position. A coordinator-owned prefetch first warms the current
player root while the visible game is idle on the player's turn. It constructs that root from the
coordinator's already-validated position instead of replaying the entire history on every turn. If
the root finishes and an earlier played move was outside its retained MultiPV, at most one
historical adjacent fallback is attempted in that player-position revision; it is recreated from
the completed root's stable key rather than another full replay. Moving, pausing, undoing,
resigning, timing out, backgrounding the app, or closing the runtime cancels stale speculative
work without making gameplay wait for review startup. Reuse requires the exact game history,
chosen move, resulting position, rules,
engine-analysis profile, and position identity. When a foreground game becomes terminal,
`GameRuntime` begins any remaining review work behind the result presentation instead of waiting
for the Review action. It owns active and partial review state, so opening Review or recreating the
activity attaches to the same work without cancelling or duplicating it. Only the player's grade
summary is derived for presentation; the app intentionally does not display an accuracy percentage
until a separate formula is calibrated and versioned.

This first review remains deliberately labeled Beta even though patch v2 now evaluates the exact
`RulesContractV1` throughout native search. Rule parity removes one correctness blocker; it does
not supply constrained-root Evidence V2, calibrated accuracy, durable review/history storage, or
the complete retry, process-death, accessibility, localization, and device acceptance matrix.
The app must not present a time-limited local engine review as a tablebase-like verdict.

## Offline ratings

Only rated results can update ratings. A rating book maintains:

- One overall offline rating.
- Separate ratings for Drawless/Escape crossed with untimed, blitz, rapid, and classical
  pools.

The initial implementation is deterministic Elo with a larger provisional K-factor.
There are no draws in the result model. Bot Elo is an approximate matchmaking control,
not a claim of calibration across all devices and time controls; private-test telemetry
should calibrate the labels before public release.

## Verification boundary

The current Kotlin core harness passes 357 tests covering core, engine, and endpoint contracts.
Its native bridge tests cover
split UTF-8/CRLF framing, malformed and oversized input, bounded FIFO
writes, synchronous and asynchronous completions, backpressure, stdout/stderr separation,
consumer isolation, open/write/close failures, duplicate completion, explicit and
unexpected termination, ABI selection, SHA-256 verification, end-to-end UCI composition,
and propagation of an endpoint crash to an outstanding request. The broader JVM suite
also covers protocol parsing, exact patch-v2 option negotiation and rule mapping, rejection of
non-v1 contracts, policy/precedence acceptance fixtures, cancellation draining, timeout shutdown,
MultiPV conversion, difficulty, ratings, hints, and review.

The permanent strength regression coverage includes all seven named Elos, Vesper at its initial and
boundary adaptive values, custom and historical Elo paths, raw legacy Skill Level samples,
divergent PV-versus-bestmove preservation, mixed strength changes in one reused session, and exact
coordinator forwarding for new and restored games. A temporary same-search JNI harness additionally
ran 332 games and 3,320 decisions on the x86-64 emulator and R6 ARM64 tablet with zero native
bestmove, ponder, legality, or strength/configuration mismatches. That is adapter-fidelity evidence,
not an Elo measurement or exact-release-artifact proof.

The clean native verifier passed after compiling a direct `Position` state harness and exercising
the full UCI acceptance matrix.
That harness distinguishes null-history and legal-only en-passant key correctness from a merely
matching final score; its node counter also proves the speculative state probes are neutral. The
UCI fixtures cover deeper main/quiescence pruning, mixed terminal intersections, and quiet
stalemates beyond the sparse material frontier.

Eight JNI-port lifecycle tests use an injected fake native API to cover the canonical
variant path, queued startup writes, independent blocking stdout/stderr readers,
idempotent close, close during startup, startup failure cleanup, unexpected EOF, and
deduplication of simultaneous stream termination. A ninth test reflection-checks the six
static native method names, parameter/return types, and modifiers expected by
`RegisterNatives`. These tests execute the real managed port code but do not load an
Android shared library. The SDK-less Compose structure gate also
passes with the production factory selected and release fallback prohibition checked.

The patch-v2 `AndroidFairyEngineInstrumentedTest` gate runs independently on x86-64 and ARM64.
Each run uses the production factory and packaged asset, asserts the exact option surface and
policy-discriminating search result, closes the session, then creates and searches through a
second session. A pass proves ART JNI loading, the packaged rules asset, native search, shutdown,
and sequential reuse for that exact build and runtime ABI.

The checked-in machine gate locks the SDK/JDK/Gradle/NDK/CMake inputs, audits debug and
release AAR/APK native bytes, runs exactly one bounded native test on the explicitly selected
device, and retains failure-safe evidence. The historical 2026-07-14 x86-64 and ARM64 manifests
both report `result: passed`, both packaged ABIs, patch-v1 tree
`80208e5f35549b88505df983e4bc0f7621083fd4`, and the same app artifacts: debug APK
17,709,024 bytes with SHA-256
`25a252a21b65a768c19b74e1dfecdb4ee7af2093ee0761c9fa06e3c85d0b87ff`, and unsigned
release APK 12,736,428 bytes with SHA-256
`e4b2215919e220d9e6e21159c6987b16ea0f7f3049b5659bdb0dffcf77e71bda`. The companion
app-test APK used for acceptance is 1,355,590 bytes with SHA-256
`79d308e03b858b7ae500574ace19287336aef98fdf801037b9e8c7ccb9c75d0b`.
The later opt-in store-screenshot harness changes only the non-shipping app-test APK; that
current test APK is 1,470,750 bytes with SHA-256
`41e14c71596c5946aa9e2bb073e31823efcf86f4478cda758059e1ba652aae0c`, and its deterministic
capture flow passes on the emulator and tablet. The complete 51-test suite also passes once
against this current test-only pair on both devices, without replacing the exact three-device
acceptance pair above.

Those retained hashes document the older runtime baseline only. They do not verify patch-v2 tree
`bf58452cf6bb2254050e7aa442d2b23f3664aaec`; the current candidate needs new artifact hashes and
fresh x86-64/ARM64 machine results.

The app instrumentation suite now contains 51 tests and passes twice from fresh processes against
that exact clean APK pair on the API-33 ARM64 tablet, API-36 x86-64 emulator, and Pixel 9 Pro XL.
The targeted forfeit flow also passes independently on all three. It covers confirmed-forfeit
durability and stable named-opponent identity across legacy/current ladder changes as well as
current-ladder checkpoint round trips. In particular, its
native-hint acceptance case publishes a full-strength MultiPV hint and then completes a bot
move through the same process-global session; rapid game replacement also completes a real
bot move without reproducing the former second-game session failure. The completion and audio
tests lock the two-second-plus finish timelines, exactly-once cue ordering, reduced-motion
collapse behavior, and the 103-resource sampled-audio catalog/platform-loading contract.

This evidence does not cover sustained performance, low-memory/native-crash resilience,
every form factor, a signed release, or an App Bundle. The licensing decision is complete:
the combined app is GPL-3.0-or-later, and JNI is not treated as a copyleft workaround.
Public release remains blocked on immutable project source identity, a public source URL,
complete notices/SBOM, signing, and matching release evidence.

Primary protocol reference:

- https://official-stockfish.github.io/docs/stockfish-wiki/UCI-%26-Commands.html
- https://github.com/fairy-stockfish/Fairy-Stockfish/blob/master/src/uci.cpp
- https://github.com/fairy-stockfish/Fairy-Stockfish/blob/master/src/ucioption.cpp
