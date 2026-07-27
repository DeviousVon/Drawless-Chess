# Production engine-facing layer

Status: protocol, transport, in-process Android JNI endpoint, factory, app wiring, Android
artifacts, and both supported runtime ABIs verified for private testing

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
- Rule preset, strength, MultiPV, analysis mode, and tablebase 50-move configuration.
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

The `:engine` module pins the upstream and patched source identity, declares the
`drawless_fairy` CMake target for `arm64-v8a` and `x86_64`, stages legal/source material,
and exposes a six-operation JNI ABI: create, start, write, stdout read, stderr read, and
close. JNI methods are registered from `JNI_OnLoad`; native code never calls back into
Kotlin. UI and game-law code never receive raw UCI text or native handles.

`:app` now depends on `:engine` and selects `AndroidFairyEngineFactory` by default. The
development bot can be selected only for an explicit debug build with
`-Pdrawless.useDevelopmentEngine=true`; release hardcodes that selection off. A native
startup/linkage failure is logged, displayed through the existing controller/bot-error
path, and represented by a non-playing failed engine. It never silently changes opponents.

## Lifecycle

1. Send `uci`; collect identity and every advertised option.
2. Require `uciok`, then send `isready` and require `readyok`.
3. For a request, validate the selected variant, MultiPV, Elo/skill range, and patch.
4. Set `UCI_Variant`, `MultiPV`, analysis mode, tablebase policy, and strength.
5. Send `ucinewgame` only when the game identity changes, followed by `isready`.
6. Send the initial FEN plus the complete move history, then `go movetime`.
7. Retain the deepest line for each MultiPV rank and produce one tagged response.
8. On cancellation, send `stop` and drain the old `bestmove` before starting queued work.

Complete history is required: the engine cannot evaluate third occurrences correctly
from the current FEN alone.

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
executors are shut down. The native bridge permits one in-process engine session at a
time and separates the bounded stdin, stdout, and stderr channels.

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

Hints are casual-only, full-strength MultiPV requests. `GameCoordinator` owns hint and bot
requests in one serialized slot because the native bridge permits one Fairy session per
process. While a hint runs the board enters `HINT_THINKING`; pause, undo, resign, timeout,
or runtime close cancels it, and tagged results are discarded if the position changed.
Hint failures return to the human turn without poisoning bot UI state. The app presents
the engine-ranked best move in SAN and, when available, up to two lower-ranked MultiPV
alternatives. Game review builds one full-strength request for every position in which a
move was chosen and first validates the complete history. Each review search requests three
candidate lines and enables UCI WDL output when the engine advertises it. The preliminary
in-memory evidence schema (schema 1) preserves line rank, score bound, depth, WDL-derived expected
points, explicit best/played-line origin, separate analysis and grading-policy identities, and
partial-rule fidelity. It is a foundation for the planned Review Evidence V2 contract, not that
contract itself. A played move found in the same root MultiPV is compared there; otherwise review
falls back to the following position and normalizes that score to the mover. Missing, bounded,
contradictory, or unsafe line evidence is not given a confident grade.

Review retains one coherent MultiPV snapshot: every selected rank comes from the same completed
depth/reporting cycle and its primary move must match `bestmove`. Every retained PV is replayed
from its exact app position and history through `ChessRules` and `GameSession`; an illegal move or
a continuation beyond an app-authoritative terminal result fails the review instead of becoming a
recommendation. Effective best and played lines retain whether they came from root MultiPV,
adjacent-position normalization, an authoritative terminal fact, or a sole legal move.

The runner submits one 350 ms search at a time, appends a final live-position search for
resignation or timeout, assigns Best/Good/Inaccuracy/Mistake/Blunder from expected-point loss,
and supports cancellation, safe retry identities, progress, and a cached completed result.
Natural terminal moves use the authoritative app outcome instead of attempting to search a
terminal position. Per-side grade summaries are derived from the completed versioned result;
the app intentionally does not display an accuracy percentage until a separate formula is
calibrated and versioned. `GameRuntime` owns active review state, so activity recreation detaches
and reattaches without cancelling or duplicating the engine work.

This first review is deliberately labeled Beta. Fairy currently receives the Drawless/Escape
preset but not every app-side adjudication detail (notably bare-king and configurable
dead-position/50-move policies). The runner corrects the recorded terminal move with the app's
authoritative result, but an earlier suggested line near one of those boundaries can still need
refinement. The app must not present this beta as an exact tablebase-like verdict.

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

At this checkpoint, `npm run test:kotlin` passes 268 JVM/core-and-endpoint tests. Of those,
25 native bridge tests cover split UTF-8/CRLF framing, malformed and oversized input, bounded FIFO
writes, synchronous and asynchronous completions, backpressure, stdout/stderr separation,
consumer isolation, open/write/close failures, duplicate completion, explicit and
unexpected termination, ABI selection, SHA-256 verification, end-to-end UCI composition,
and propagation of an endpoint crash to an outstanding request. The broader JVM suite
also covers protocol parsing, option negotiation, patch verification, cancellation
draining, timeout shutdown, MultiPV conversion, difficulty, ratings, hints, and review.

Eight JNI-port lifecycle tests use an injected fake native API to cover the canonical
variant path, queued startup writes, independent blocking stdout/stderr readers,
idempotent close, close during startup, startup failure cleanup, unexpected EOF, and
deduplication of simultaneous stream termination. A ninth test reflection-checks the six
static native method names, parameter/return types, and modifiers expected by
`RegisterNatives`. These tests execute the real managed port code but do not load an
Android shared library. The SDK-less Compose structure gate also
passes with the production factory selected and release fallback prohibition checked.

`AndroidFairyEngineInstrumentedTest` now passes independently on an API-36 x86-64 emulator
and an API-33 ARM64 physical tablet. Each run uses the production factory and packaged asset,
asserts the forced-repetition `h8g8` mate-in-one result and patch identity, closes the
session, then creates and searches through a second session. This proves ART JNI loading,
the packaged rules asset, native search, shutdown, and sequential reuse on both supported
runtime ABIs.

The checked-in machine gate locks the SDK/JDK/Gradle/NDK/CMake inputs, audits debug and
release AAR/APK native bytes, runs exactly one bounded native test on the explicitly selected
device, and retains failure-safe evidence. Fresh 2026-07-14 x86-64 and ARM64 manifests both
report `result: passed`, both packaged ABIs, patched tree
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

The app instrumentation suite now contains 51 tests and passes twice from fresh processes against
that exact clean APK pair on the API-33 ARM64 tablet, API-36 x86-64 emulator, and Pixel 9 Pro XL.
The targeted forfeit flow also passes independently on all three. It covers confirmed-forfeit
durability and stable named-opponent identity across legacy/current ladder changes as well as
current-ladder checkpoint round trips. In particular, its
native-hint acceptance case publishes a full-strength MultiPV hint and then completes a bot
move through the same process-global session; rapid game replacement also completes a real
bot move without reproducing the former second-game session failure. The completion and audio
tests lock the two-second-plus finish timelines, exactly-once cue ordering, reduced-motion
collapse behavior, and the 101-resource sampled-audio catalog/platform-loading contract.

This evidence does not cover sustained performance, low-memory/native-crash resilience,
every form factor, a signed release, or an App Bundle. The licensing decision is complete:
the combined app is GPL-3.0-or-later, and JNI is not treated as a copyleft workaround.
Public release remains blocked on immutable project source identity, a public source URL,
complete notices/SBOM, signing, and matching release evidence.

Primary protocol reference:

- https://official-stockfish.github.io/docs/stockfish-wiki/UCI-%26-Commands.html
- https://github.com/fairy-stockfish/Fairy-Stockfish/blob/master/src/uci.cpp
- https://github.com/fairy-stockfish/Fairy-Stockfish/blob/master/src/ucioption.cpp
