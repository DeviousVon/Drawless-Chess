# Android and Kotlin foundation checkpoint

Status: core contracts, Android/native packaging, and x86-64/ARM64 private-test runtimes verified

## Toolchain baseline

- Kotlin compiler: 2.4.0 for the local headless verification gate.
- Android Gradle Plugin scaffold: 9.2.1.
- Compile/target SDK: stable API 36 with Build Tools 36.0.0.
- Minimum SDK: 26.
- Java bytecode target: 17.
- Machine-gate Gradle build JDK: complete stable JDK/JBR 17 or 21; this does not change the
  bytecode target.
- Native scaffold declarations: Android NDK 29.0.14206865 and Android SDK CMake package
  3.22.1, with exact executable build `3.22.1-g37088a8-dirty`.
- Intended native ABIs: `arm64-v8a` and `x86_64`.

AGP 9.2 requires Gradle 9.4.1 and at least JDK 17. Both machine gates accept only complete,
stable build-JDK majors 17 and 21, including Android Studio's bundled JBR 21, while project
compatibility remains 17. The committed wrapper has run with Android Studio JBR 21,
SDK/Build Tools 36, NDK 29, and CMake 3.22.1. Debug/release Android packages were built and
audited, then exercised on an API-36 x86-64 emulator and API-33 ARM64 physical tablet. Device
runtime API is independent from the stable compile/target API 36 baseline.
The Android-free Kotlin core is still compiled and executed directly as a faster gate.

Official AGP release information:

- https://developer.android.com/build/releases/gradle-plugin

## Implemented core

- Immutable rules contract v1 with Drawless and Escape presets.
- Versioned saved-game model and rated assistance restrictions.
- Standard-chess UCI move validation.
- Immutable occurrence-counting position history.
- Move-transition facts and complete legal-alternative contracts.
- Automatic avoidable-versus-forced repetition classification.
- Configurable dead-position and 50-move adjudication.
- Immutable game session and move records.
- Stable position identity for rejecting stale engine results.
- Engine-neutral asynchronous request/response boundary.
- Strict Fairy UCI protocol/session boundary with cancellation draining and timeouts.
- Named, custom-Elo, and adaptive difficulty contracts.
- Rated-only overall and ruleset/time-pool offline rating models.
- Casual hint and post-game review request planning.
- JVM-neutral `NativeEnginePort` lifecycle and byte-channel contract.
- Strict UTF-8 line framing and bounded, serialized `SerializedNativeUciTransport`.
- ABI manifest selection plus native artifact size/SHA-256 verification models.
- `NativeFairyEngineSession` composition from native byte transport through strict UCI to
  the public `ChessEngine` contract.

## Native engine integration source

The `:engine` Android library module now declares the pinned Fairy-Stockfish source,
Drawless patch identity, CMake target `drawless_fairy`, intended ABIs, and packaged legal
assets. It now also implements the verified private-test JNI command bridge,
`JniFairyEnginePort`, variant installation/integrity verification, timeout ownership, and
the Android factory selected by `GameRuntime`. Its verification tasks fail if the source
revision, source trees, patch series, variant configuration, or required notices drift.

The source now builds into audited debug/release AARs and APKs for both declared ABIs. JNI
load/search/close/restart passes on x86-64 and ARM64. The development engine remains
explicit debug-only and release hard-disables it.

## Trust boundary

`GameSession` does not pretend to generate chess moves. The current `core:chess` adapter
supplies a `MoveTransition` containing every legal alternative and its canonical resulting
position key. The constructor verifies internal consistency, and the session derives all
history-dependent rule facts itself.

This split prevents the UI or native engine from directly declaring a winner.

## Position-key requirement

The chess adapter's canonical key must encode all state relevant to legal equivalence:

- Piece placement
- Side to move
- Castling rights
- En-passant availability when legally meaningful

Move counters are intentionally excluded from repetition identity but remain explicit
transition facts for the selectable 50-move policies.

## Verification

Run everything:

```bash
npm install
npm run test:all
```

The Kotlin-only gate is:

```bash
npm run test:kotlin
```

At this checkpoint that command passes 223 JVM/core-and-endpoint tests. Twenty-five target the
native boundary specifically: line framing, bounded FIFO writes, backpressure, lifecycle
and failure behavior, ABI/artifact metadata, `NativeFairyEngineSession` composition, and
endpoint-crash propagation. Nine more exercise the managed JNI port lifecycle and exact
static-native signature contract.

The host-native gate also compiles the full patched engine and exercises the bridge core,
rules advertisement, forced-repetition search, singleton, close, and restart. The separate
Android machine gate now proves SDK/NDK/CMake compatibility, ART JNI loading, AAR/APK
packaging, and runtime behavior on both supported ABIs. A signed release, App Bundle,
sustained performance, and low-memory/native-crash testing remain open.
