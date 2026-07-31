# ADR-003: Android engine runtime boundary

Status: accepted; whole application selected as GPL-3.0-or-later; release evidence pending

## Decision

Use the narrow JNI bridge to drive the pinned Fairy-Stockfish shared library inside each Android
process. Gameplay and hints own the main app process's engine session. Speculative and post-game
review bind through Android `Messenger` IPC to `ReviewEngineService` in the dedicated
`:review_engine` app process, whose own JNI session has separate Fairy process globals. Keep the
existing `ChessEngine` and `NativeEnginePort` boundaries so chess law, UCI protocol, and UI code do
not depend on which Android process owns the native session.

The bridge follows these constraints:

- Kotlin owns startup/write workers, the two blocking output readers, timeout scheduling,
  and all callbacks into the protocol layer.
- Native code never calls Kotlin asynchronously. Its six operations are create, start,
  write, stdout read, stderr read, and close.
- `JNI_OnLoad` registers the exact static native methods up front; symbol visibility is
  restricted to that entry point and immutable build-identity exports.
- Fairy-Stockfish runs on a native worker behind bounded input/stdout/stderr pipes.
- Close injects `stop` and `quit`, wakes blocked I/O, waits for engine/search threads, and
  reaches EOF deterministically.
- Fairy uses process-global state, so the native registry permits exactly one live engine
  session per app process. Sequential sessions receive new handles and are supported.
- `IsolatedReviewEngine` accepts `REVIEW` requests only. Its client serialization and reply handling
  run on a private worker looper, and the coordinator gives review calls an invocation gate
  independent from gameplay.

This follows Android's guidance to keep JNI narrow, minimize marshalling, keep asynchronous
coordination in managed code, load packaged shared libraries with `System.loadLibrary`,
and register methods from `JNI_OnLoad`.

## Rules and identity before handshake

`AndroidFairyEngineFactory` copies `assets/engine/drawless-variants.ini` to versioned
private storage, caps its size, verifies the locked SHA-256, syncs it, and makes it
read-only. `nativeCreate` reads that absolute path; `nativeStart` rejects any change
between create and initialization.

Before the first `uci` command, native initialization loads the configuration and verifies both
Drawless and Escape, relative third-occurrence scoring, forced repetition, their opposing
stalemate outcomes, and disabled native n-move adjudication. The UCI layer then requires
`Drawless Patch Version` 2 and the exact combo declarations for `Drawless Dead Position`,
`Drawless Fifty Move`, and `Drawless Bare King` before launching a search.

For each request, Kotlin validates all immutable `RulesContractV1` invariants and sends the
preset plus those three policies before `isready`. The native search applies the same conservative
dead-position detector, material and last-capturer tie breakers, full-legal-set forced exceptions,
and terminal precedence as `GameSession`; it does not infer a configurable policy from the
installed variant defaults.

The fourth ordered patch, `0004-preserve-drawless-deeper-search-boundaries.patch`, keeps that
contract intact below the root. Immediate bare-king, known-dead, and 50-move outcomes bypass the
main-search and quiescence filters that would otherwise omit them; this includes mixed terminal
reply sets and quiet bishop/knight underpromotion. Search null moves cannot alter the app-visible
clock or last-capturer history, and pseudo-only en-passant targets are removed from repetition keys.

## Failure behavior

- Synchronous asset, construction, or library-linkage failure becomes a visible,
  non-playing bot error.
- Asynchronous startup, framing, protocol, timeout, EOF, and native I/O failures terminate
  the session and fail outstanding work.
- No failure selects the development bot.
- The old legal-move bot is available only through
  `-Pdrawless.useDevelopmentEngine=true` on a debug build. Release hardcodes the flag off.
- A native memory error or abort in the gameplay session can still terminate the main app process
  because JNI shares that process. The same failure in `:review_engine` terminates or disconnects
  the review service instead; the client fails outstanding review work closed and must not replace
  or poison gameplay. Device crash and low-memory testing remain mandatory before release.

## Licensing consequence

Fairy-Stockfish is GPL-3.0-or-later. The Free Software Foundation's guidance treats modules
designed to share one address space as a combined program. That is guidance rather than a
project-specific legal ruling, but it makes an in-process commercial distribution a
deliberate whole-app licensing decision.

The project has selected the whole-application option: Drawless Chess and the combined
Android work are GPL-3.0-or-later. JNI is not treated as a way around the engine's
copyleft. Paid distribution, ads, and purchases are not by themselves incompatible with
GPL, but source and recipient rights cannot be withheld.

That decision resolves the project-license incompatibility; it does not authorize a
particular binary. Every public APK/AAB still requires the exact whole-project source
archive, immutable release identity, public source URL, notices/SBOM, signing setup, and
matching release evidence defined in `docs/RELEASE_LICENSING.md`.

## Verification

The non-Android gates prove:

- exact Kotlin static-native method names and types;
- managed port startup, queued writes, stdout/stderr, close, startup interruption,
  failures, EOF, and termination deduplication;
- exact four-patch replay, locked source identity, both-ABI Android compilation, and host JNI
  harness compilation;
- a direct native-state harness for null-history ownership and legal-only en-passant keys; and
- production app selection and release prohibition of the development bot.

The current patch-v2 clean verifier has passed its exact four-patch replay, direct native-state
harness, and full UCI acceptance matrix, including the beyond-frontier quiet-stalemate cases and
node-neutral speculative-probe assertion.

On a GNU host, the optional native harness additionally covers the bridge core, rules
advertisement, policy-discriminating searches, singleton rejection, clean close, and restart.
The UCI acceptance matrix additionally guards deeper main/quiescence pruning, terminal-child
ponder suppression, and the orthodox-only Syzygy root boundary.
Without that toolchain, those behavioral claims must come from the exact Android libraries rather
than from Java compilation alone.

Patch-v2 Android instrumentation must pass independently on x86-64 and ARM64. It verifies the
packaged asset identity, ART JNI load, exact option surface, policy-discriminating search, close,
and sequential restart. Each machine manifest records both packaged ABIs and the runtime ABI
actually exercised; an older patch-v1 manifest is not evidence for the current native bytes.

The isolated review-engine class has passed its bind, request, result, cancellation, close, and
distinct-process checks on the API-36 x86-64 emulator and R6 ARM64 tablet. Core concurrency tests
also prove that a blocked review startup cannot block a gameplay launch or undo, while background
disable and runtime close still drain unpublished review launches deterministically.

The historical patch-v1 51-test app suite passes twice from fresh processes against its exact
clean APK pair on the API-33 ARM64 tablet, API-36 x86-64 emulator, and Pixel 9 Pro XL. The targeted forfeit
flow also passes independently on all three. Coverage includes stable opponent identity across
ladder changes, confirmed-forfeit durability, rapid runtime replacement,
a full-strength hint followed by a bot move through the same native session, deterministic
finish timing, and full sampled-audio catalog/platform-loading coverage. Separate
physical acceptance has covered Room-backed force-stop/relaunch/Resume. These checks do
not establish low-memory/native-crash resilience, sustained performance, every form factor,
or signed public-release behavior.

## References

- Android JNI guidance: https://developer.android.com/ndk/guides/jni-tips
- Android native-code build guidance: https://developer.android.com/studio/projects/add-native-code
- Android C++ runtime guidance: https://developer.android.com/ndk/guides/cpp-support
- GNU GPL FAQ on aggregates and combined programs:
  https://www.gnu.org/licenses/gpl-faq.html#MereAggregation
