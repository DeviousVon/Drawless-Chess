# Native Fairy-Stockfish checkpoint

Status: Drawless interface v2, platform-neutral transport, in-process JNI bridge, Android factory,
app wiring, and packaging implemented. Clean patch-v2 host verification passed; exact Android
artifact/device verification remains the current private-test gate.

## What is pinned

The production native source is locked independently from the older npm/WASM
proof of concept:

- Fairy-Stockfish commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patched tree: `bf58452cf6bb2254050e7aa442d2b23f3664aaec`
- Fourth-patch SHA-256:
  `22c8327ed64a2d7711695d372d817a6e037bca5fdbfc24ad812c1e869e59bedd`
- Ordered patch-series SHA-256:
  `7501f6322ee73b9d737c387e32f1c45cb6de7d0cb3b67648601e0236fca799ed`
- Variant configuration SHA-256:
  `0570d4805f915c2c77228babc31e127c4155413dba4d335ccb527dc2b974d28f`
- Drawless patch interface: version 2
- Android targets: `arm64-v8a` and baseline `x86_64`, minimum API 26
- Build pins: NDK `29.0.14206865`, Android SDK CMake package `3.22.1`, and exact CMake
  executable build `3.22.1-g37088a8-dirty`

`engine/native/upstream.properties` is the single production lock. Fetch,
Gradle, CMake, source-bundle, and AAR-verification scripts fail when the source,
patch series, variant configuration, or ABI set drifts from that lock.

## Patch-v2 verification contract

`engine/patches/verify-patch.sh` rebuilt a clean copy of the pinned source and passed the native
x86-64 state/UCI acceptance suite. The patch-v2 gate covers:

- the canonical unpatched forced fixture scores `mate -1`;
- patched forced repetition scores `mate +1` for either completing color;
- either color avoids an optional losing third occurrence;
- identical boards with different repetition histories do not share decisive
  transposition-table scores;
- hash sizes 1 and 64 and a stopped-search follow-up preserve the result;
- exact option declarations for `Drawless Patch Version` 2, `Drawless Dead Position`,
  `Drawless Fifty Move`, and `Drawless Bare King`;
- Drawless and Escape stalemate outcomes, both bare-king choices, both known-dead-position
  choices (including quiet bishop/knight underpromotion), and all four 50-move choices for both
  colors;
- material advantage, last-capturer and full-legal-set forced tie breakers;
- the v1 terminal precedence order at searched nodes: no legal move, repetition, bare king,
  known dead position, then 50 moves. Explicit both-color conflicts protect bare king over dead
  position, bare king over 50 moves, and dead position over 50 moves;
- deeper halfmove-boundary search, last-piece captures, quiet bishop/knight underpromotions,
  mixed immediate terminal sets, and both-color quiet stalemates beyond the sparse material
  frontier in main search and quiescence;
- direct native-state checks that search null moves cannot alter the Drawless halfmove clock or
  last-capturer history and that repetition keys retain only legally capturable en-passant;
- terminal-child ponder suppression plus an orthodox-chess-only Syzygy root boundary; and
- mandatory Drawless terminals already present at the UCI root expose no legal root moves and
  return `bestmove (none)` rather than continuing ordinary search.

Those deeper boundaries are isolated in the ordered
`0004-preserve-drawless-deeper-search-boundaries.patch`. Sparse material-policy probes and a
node-neutral full legal child-terminal pass in quiescence keep contract outcomes ahead of
null-move, ProbCut, futility, history, move-count, SEE, capture-only, and stand-pat decisions.

The ordered second patch also corrects negative fractional-skill rounding so low
`UCI_Elo` targets are not systematically rounded toward a stronger skill level.

The v1-v2 patch series deliberately disables transposition-table bound reads and writes for
Drawless variants while retaining a legal TT move for ordering. This is a broad,
correctness-first history-isolation policy. Performance must be measured before
release; a narrower history signature would require separate review and a patch
version bump.

For every request, Kotlin validates the immutable `RulesContractV1` invariants, selects
`UCI_Variant=drawless|escape`, and sets the three policy options above. Contract v1 fixes
third occurrence, completing-player repetition loss, the forced exception, and material weights
at 1/3/3/5/9; custom weights are rejected by the contract rather than approximated by search.

The Kotlin core also has a tested, Android-framework-neutral boundary:

- `NativeEnginePort` owns byte I/O and endpoint lifetime;
- `SerializedNativeUciTransport` provides strict UTF-8 line framing, bounded
  startup/backpressure, FIFO writes, diagnostics, and deterministic failure;
- `NativeFairyEngineSession` composes that transport with the strict UCI session;
- `NativeEngineManifest` validates API/ABI selection, sizes, and SHA-256 values.

The `:engine` module now supplies the private-test Android side of that boundary:

- `FairyNativeBindings` loads `libdrawless_fairy.so` and exposes create, start, blocking
  write, blocking stdout/stderr read, and close operations registered by `JNI_OnLoad`;
- `JniFairyEnginePort` runs create/start and FIFO writes on one managed command executor,
  and uses one managed blocking reader each for stdout and stderr;
- native code never calls into Kotlin, and close calls the native shutdown primitive
  directly instead of expecting thread interruption to unblock JNI;
- bounded native byte pipes connect the embedded UCI loop without changing process-wide
  file descriptors, and the bridge permits only one live engine session;
- `AndroidUciTimeoutScheduler` and `AndroidFairyEngineSession` make timeout, protocol,
  port, and thread ownership explicit and idempotently close the whole session; and
- `VariantConfigInstaller` installs the packaged rules in private no-backup storage only
  after size, containment, and locked SHA-256 checks.

The JVM suite uses fake byte and native APIs, so that suite alone does not load an Android
shared library. Patch-v2 Android machine runs on both x86-64 and ARM64 are required before a
particular APK can claim the new native identity; older patch-v1 machine manifests prove the
runtime boundary but not the current engine bytes.

## Android packaging and app integration

The included `:engine` Android library module is configured to compile the pinned
translation-unit list into `libdrawless_fairy.so`, package the variant
configuration and license/source-identity material, and target only
`arm64-v8a` and `x86_64`.
It now compiles `native_bridge.cpp` as well as the immutable C build-identity functions,
restricts exports with a version script, and exposes the JNI UCI command channel.

`AndroidFairyEngineFactory` verifies and installs the packaged variant file, constructs
the JNI port and timeout scheduler, carries the locked build/patch identity into the UCI
session, and returns one owned `ChessEngine`. `:app` depends on `:engine` and uses that
factory by default. The simple development bot is available only when an explicit debug
build sets `-Pdrawless.useDevelopmentEngine=true`; release hardcodes the flag off.

Synchronous factory-time installation, construction, or linkage failures are logged and
displayed through a non-playing failed engine and the existing bot-error UI. Managed JNI
startup and UCI handshake failures propagate through the transport and reach the UI when
the first bot request is made. Neither path silently selects the development bot. Native
stderr is diagnostic-only and is sent to Logcat.

The installed Android toolchain has now run the checked-in wrapper and machine gate. The
commands remain the reproducible path for fresh evidence:

```bash
scripts/native-fetch-fairy.sh
npm run test:android-structure
npm run test:android-machine -- --preflight-only --sdk "$ANDROID_SDK_ROOT"
npm run test:android-machine -- \
  --sdk "$ANDROID_SDK_ROOT" --serial SERIAL --require-abi x86_64
```

The instrumentation tests load the packaged rules and native library, require patch v2 and its
three exact combo-option surfaces, exercise policy-discriminating searches, close the session,
and repeat search through a second in-process session. They must pass separately on x86-64 and
ARM64 for the candidate under review. See `docs/ANDROID_MACHINE_VERIFICATION.md` for
prerequisites, physical-device safeguards, artifact checks, and evidence semantics.

The repository also includes a host acceptance harness for the native bridge lifecycle,
engine identity/options, a forced-repetition search, close/EOF, singleton enforcement,
and a second sequential session:

```bash
npm run test:native-jni-host
```

With a full JDK plus the required GNU host toolchain, the host gate can build and load the exact
registered JNI methods and exercise registry, streams, initialization, rules, search, and teardown
through `DRAWLESS_HOST_BRIDGE_TEST`. The current patch-v2 checkpoint has compiled that Java/JNI
harness; packaged behavioral evidence comes from the Android runtime matrix. Historical patch-v1
host evidence is not silently reused as proof of the v2 engine bytes. The Kotlin suite separately
reflection-checks all six static native method signatures.

## Release and licensing gate

Fairy-Stockfish is GPL-3.0-or-later, and Drawless Chess has adopted
GPL-3.0-or-later for the whole combined Android application. No APK, App Bundle,
or AAR containing the engine should be distributed until complete corresponding
source is made available for the exact shipped binary. `scripts/source-bundle.sh`
creates the deterministic whole-project archive, including the prepared native Git
checkout; `scripts/native-source-bundle.sh` is only a compatibility alias. The
archive is required release material but is not by itself proof of compliance.

The implemented in-process JNI bridge remains subject to native crash/lifecycle review,
but its licensing direction is no longer provisional: the combined work is GPL. Changing
to a worker is not required as a licensing workaround and would not automatically remove
GPL obligations.

The current Kotlin core harness passes 344 tests. `npm run test:kotlin` exercises the JVM-neutral
native transport/composition, exact rule-option
mapping and rejection, acceptance fixtures for every contract-v1 policy and precedence branch,
the real managed JNI-port code, and the static-native signature contract. The Compose structure
gate also verifies that the app selects the factory and that release cannot select the
development bot. Android machine evidence remains artifact-specific; passing host gates never
turns a different APK into a verified or distribution-authorized release package.

## Reproduce the current gates

```bash
npm run test:all
npm run test:native-source
npm run test:native-patch
npm run test:native-jni-host
```

The first command is the normal offline checkpoint and does not rebuild the
upstream engine. `test:native-source` requires the fetched checkout.
`test:native-patch` performs a native host rebuild and fetches the pin unless a
local source is supplied directly to `verify-patch.sh`; it is intentionally
kept outside `test:all` because it is slow. `test:native-jni-host` is also outside
`test:all`; it uses the exact JNI lane when JDK headers are available and otherwise tests
the same native bridge core through its compile-time host C ABI. None of these host gates
replaces the Android SDK/NDK, AAR/APK, or device gates.
