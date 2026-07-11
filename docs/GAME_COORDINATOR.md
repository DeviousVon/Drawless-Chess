# Game coordinator checkpoint

Status: compiled and deterministically verified on JVM

## Responsibilities

The coordinator is the single authority that combines chess law, Drawless rules, bot
requests, clocks, assistance restrictions, and durable checkpoints. UI code will submit
events and render immutable snapshots; it will not mutate a board or declare a result.

Implemented behaviors:

- Human-versus-bot turn enforcement.
- Engine requests tagged with request, game, and position identities.
- Stale, duplicate, mismatched, illegal, failed, and synchronously throwing engine results.
- Cancellation-safe pause and undo.
- Rated prohibition of pause, hints, and undo.
- Casual hint, pause, resume, and undo accounting.
- Human resignation.
- Timed and untimed games with increment.
- Timeout adjudication for either human or bot.
- Checkpoint persistence after every committed state change.
- Replay-verified recovery after process death.
- Checkpoint tamper and consistency checks.

## Clock model

The Android adapter must provide both:

- Monotonic time, using `SystemClock.elapsedRealtime()`.
- Wall time, using `System.currentTimeMillis()`.

During ordinary execution, elapsed time is derived from the monotonic source. If that
source moves backward after a device reboot, recovery falls back to non-negative wall
elapsed time. Rated games never acquire pause semantics merely because the process dies.

UI clock ticks do not accumulate time. They ask for a projection of the authoritative
remaining duration, which prevents timer-callback drift.

## Engine callback safety

An engine response commits only when all three identities still match:

- Request ID
- Game ID
- Position ID

Undo or pause clears the active request identity before cancellation. A late callback is
therefore harmless even if the native engine cannot stop immediately. Duplicate callbacks
also cannot commit twice. A callback delivered synchronously from `analyze()` is supported.

## Undo behavior

Casual undo removes the most recent human move and every bot move that followed it. For a
human playing Black, the bot's preceding White move is retained. The coordinator then:

1. Replays the retained UCI history from the initial FEN.
2. Rebuilds occurrence history and all rules-derived state.
3. Restores the matching clock snapshot.
4. Starts the correct side's clock.
5. Persists the replacement checkpoint.

No internal board object is rolled backward in place.

## Persistence adapter requirement

`CheckpointSink` receives immutable, monotonically revisioned checkpoints. The Android
implementation serializes writes on one FIFO executor and applies each update in a Room
transaction. The active sink is generation-bound, so callbacks from a replaced game are
discarded, and the DAO refuses an older or equal revision for the same game. A different game
atomically replaces the active slot only when its first checkpoint is written, retaining the
previous good save if construction or the first write fails.

The Room row carries query/guard columns plus a versioned JSON encoding of the complete
`CoordinatorCheckpoint`. Reads use the same executor, validate duplicated row metadata, decode
strict enum discriminators, and then pass through `GameCoordinator.restore` for replay, FEN,
clock, result, and assistance validation.

## Remaining Android integration

- Add the first explicit Room migration and `MigrationTestHelper` coverage when schema version
  2 is introduced; version 1 is exported and never uses destructive fallback.
- Add the app instrumentation lane to the fail-closed machine evidence scripts.
- Continue sustained, low-memory, and forced native-crash recovery testing.
