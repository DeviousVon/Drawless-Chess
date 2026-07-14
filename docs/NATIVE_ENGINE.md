# Native Fairy-Stockfish checkpoint

Status: Drawless interface v1, platform-neutral transport, in-process JNI bridge, Android factory, app
wiring, packaging, and both supported Android runtime ABIs verified for private testing.

## What is pinned

The production native source is locked independently from the older npm/WASM
proof of concept:

- Fairy-Stockfish commit: `fb78cb561aa01708338e35b3dc3b65a42149a3c4`
- Upstream tree: `dfe4b96037c10ab60e22613bf634452612fc2b04`
- Patched tree: `80208e5f35549b88505df983e4bc0f7621083fd4`
- Ordered patch-series SHA-256:
  `c44b63516728a3114378b7ee374e12bd8e092f3287b93fe6d2e80a24eac19fe7`
- Drawless patch interface: version 1
- Android targets: `arm64-v8a` and baseline `x86_64`, minimum API 26
- Build pins: NDK `29.0.14206865`, Android SDK CMake package `3.22.1`, and exact CMake
  executable build `3.22.1-g37088a8-dirty`

`engine/native/upstream.properties` is the single production lock. Fetch,
Gradle, CMake, source-bundle, and AAR-verification scripts fail when the source,
patch series, variant configuration, or ABI set drifts from that lock.

## What has been proved

`engine/patches/verify-patch.sh` was run against a clean copy of the pinned
source and passed native x86-64 compilation plus the UCI acceptance suite. The
suite proves:

- the canonical unpatched forced fixture scores `mate -1`;
- patched forced repetition scores `mate +1` for either completing color;
- either color avoids an optional losing third occurrence;
- identical boards with different repetition histories do not share decisive
  transposition-table scores;
- hash sizes 1 and 64 and a stopped-search follow-up preserve the result; and
- the engine advertises exactly `Drawless Patch Version` 1.

The ordered second patch also corrects negative fractional-skill rounding so low
`UCI_Elo` targets are not systematically rounded toward a stronger skill level.

Patch v1 deliberately disables transposition-table bound reads and writes for
Drawless variants while retaining a legal TT move for ordering. This is a broad,
correctness-first history-isolation policy. Performance must be measured before
release; a narrower history signature would require separate review and a patch
version bump.

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
shared library. The separate Android machine evidence now supplies JNI load/search/close
proof on both x86-64 and ARM64.

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

The instrumentation test loads the packaged rules and native library, proves the
forced-repetition `bestmove h8g8`/mate-in-one result, closes the session, and repeats the
same search through a second in-process session. It passes separately on an API-36 x86-64
emulator and API-33 ARM64 physical tablet. See `docs/ANDROID_MACHINE_VERIFICATION.md` for
prerequisites, physical-device safeguards, artifact checks, and evidence semantics.

The repository also includes a host acceptance harness for the native bridge lifecycle,
engine identity/options, a forced-repetition search, close/EOF, singleton enforcement,
and a second sequential session:

```bash
npm run test:native-jni-host
```

With a full JDK, the host gate can build and load the exact registered JNI methods. The
retained host-native evidence compiled the full patched engine with
`DRAWLESS_HOST_BRIDGE_TEST` and exercised the registry, streams, initialization, rules
checks, search, and teardown through its test-only C ABI. The Kotlin suite separately
reflection-checks all six static native method signatures, while the Android runs now
provide the actual packaged JNI-loading evidence.

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

At this checkpoint, `npm run test:kotlin` passes 223 tests. Twenty-five exercise the
JVM-neutral native transport/composition and nine more exercise the real managed JNI-port
code and exact static-native signature contract. The Compose structure gate also verifies that the
app selects the factory and that release cannot select the development bot. Beyond those
JVM checks, the machine gate has built and audited debug/release AARs and APKs and passed
the real-library Android instrumentation on both supported ABIs. Those binaries remain
private-test artifacts, not signed or distribution-authorized release packages.

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
