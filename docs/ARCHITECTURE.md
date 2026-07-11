# Drawless Chess production architecture

Status: accepted for the Android foundation checkpoint
Architecture version: 1

## Objectives

- Offline play must remain fully functional without a network connection.
- Game law must be deterministic, versioned, and independent from Android UI code.
- The bot must evaluate Drawless outcomes inside search, not reverse results afterward.
- Phone and tablet layouts must consume the same immutable game state.
- Saved games must remain replayable after rules or engine upgrades.
- Advertising, billing, and analytics must never be dependencies of the game loop.

## Module boundaries

| Module | Responsibility | May depend on |
| --- | --- | --- |
| `core:model` | Squares, pieces, moves, clocks, immutable game state | Nothing platform-specific |
| `core:rules` | Terminal adjudication and versioned rulesets | `core:model` |
| `core:chess` | Legal moves, check state, canonical repetition keys, dead-position facts | `core:model` |
| `engine:api` | Asynchronous engine request/result contract | `core:model`, `core:rules` |
| `engine:fairy` | Strict UCI session, JVM-neutral native byte transport, composition, and variant configuration | `engine:api` |
| `engine:android` | Provisional JNI `NativeEnginePort`, native package, asset verification, and timeout scheduling | `engine:fairy`, Android runtime APIs |
| `data` | Saved games, ratings, statistics, preferences | Core contracts |
| `app` | Compose UI, navigation, state holders, Android lifecycle | All public module APIs |
| `monetization` | Billing, ad eligibility, cosmetic entitlements | Entitlement API only |

The current Gradle scaffold has `:app`, `:core`, and `:engine`. `:core` contains the
platform-neutral game law, chess implementation, UCI protocol, `NativeEnginePort`,
`SerializedNativeUciTransport`, and `NativeFairyEngineSession`. `:engine` contains the
pinned-source build, legal assets, CMake/JNI bridge, verified variant installer, managed
`JniFairyEnginePort`, scheduler, and factory. `:app` depends on both modules and selects
the native factory by default; its development engine is explicit debug-only. Split smaller logical
areas into Gradle modules only when build times or ownership justify it, while preserving
the dependency directions above.

## Runtime flow

1. A UI event requests a move.
2. The game-session state holder asks `core:chess` to validate and apply it.
3. `core:chess` emits engine-neutral `PositionFacts`, including repetition history.
4. `core:rules` produces either an ongoing state or one decisive `GameOutcome`.
5. The immutable state is persisted and exposed to Compose.
6. If it is the bot's turn, `engine:api` receives a snapshot plus an exact rules contract.
7. Engine responses are rejected if their session or position identifier is stale.

For the production engine path, `NativeFairyEngineSession` presents `ChessEngine` to the
coordinator and composes two lower layers. `FairyUciEngine` owns UCI state, request
correlation, timeouts, and Drawless patch compatibility. `SerializedNativeUciTransport`
owns bounded line/byte I/O, FIFO write serialization, and terminal transport state.
`JniFairyEnginePort` supplies bytes through the accepted in-process private-test bridge.
The core seam remains neutral so a separately controlled process can replace JNI for
future reliability or isolation work; the whole application is GPL-3.0-or-later, so this
seam is not treated as a licensing workaround.

This is unidirectional data flow: state flows to UI; user events flow to a state holder.

## Concurrency invariants

- Exactly one authoritative game session mutates a game at a time.
- Engine work is cancellable and tagged with `gameId`, `positionId`, and `requestId`.
- A move is committed only if the response still matches the current `positionId`.
- Clock time is derived from a monotonic time source, not accumulated UI callbacks.
- Lifecycle pauses do not pause rated games.
- Database writes occur after every committed move, never only when leaving the board.

## Persistence

- Store structured data locally; no sign-in is required in version 1.
- Persist UCI moves plus the initial FEN rather than serialized internal objects.
- Persist the full rules contract and its schema version with every game.
- Persist engine identity and settings for reproducible analysis, not as game law.
- Store elapsed clock values at each ply for recovery and dispute-free result screens.
- Add database migrations; never destructively recreate a production database.

## UI architecture

- Compose screens are stateless renderers of immutable screen state.
- A screen state holder translates domain states into display states.
- Board geometry is calculated from available constraints, not fixed device dimensions.
- Phone and tablet layouts share board and game-control components.
- Themes supply semantic colors and piece assets; rules never reference theme data.
- Ads can render only on home or post-game destinations and cannot occupy the game tree.

## Testing layers

1. Contract tests: schema and saved-game compatibility.
2. Rules tests: every outcome and precedence combination.
3. Chess adapter tests: legal moves, repetition keys, dead positions, FEN/PGN replay.
4. Engine parity tests: shallow tactical fixtures and Drawless-specific terminal fixtures.
5. State-holder tests: cancellation, clocks, stale engine responses, recovery.
6. Compose tests: board interaction, adaptive layouts, accessibility.
7. Device tests: supported ABIs, process death, low-memory and tablet behavior.

The JVM gate includes 25 native-transport tests, including two composition tests
through `NativeFairyEngineSession`. They prove the port contract, framing, serialization,
failure propagation, artifact metadata, and strict UCI integration. Nine further tests
exercise the managed JNI port and exact static-native ABI contract. A host-native gate
executes the full bridge core and patched engine through rules, search, close, and restart.
Those host/JVM layers do not prove Android behavior by themselves. The separate machine
gate now supplies Android JNI load, AAR/APK packaging, and x86-64/ARM64 runtime evidence;
low-memory/native-crash, sustained-performance, and broader form-factor gates remain open.

## Source references

- Android architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Compose unidirectional data flow: https://developer.android.com/develop/ui/compose/architecture
- Android offline-first guidance: https://developer.android.com/topic/architecture/data-layer/offline-first
