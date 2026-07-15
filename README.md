# Drawless Chess

This repository contains the current offline Android implementation of Drawless Chess.
The app combines a versioned no-draw rules core, Room-backed resume flow, Jetpack Compose
UI, and a pinned, patched Fairy-Stockfish engine behind an in-process JNI boundary. The
older JavaScript/WASM proof of concept remains as a fast regression lane; it is not the
runtime shipped in the Android private-test package.

Project source: https://github.com/DeviousVon/Drawless-Chess

The design and release controls are documented here:

- `docs/ARCHITECTURE.md` — Android module boundaries, runtime flow, persistence, and testing.
- `docs/ADR-001-ENGINE.md` — Fairy-Stockfish integration and forced-repetition decision.
- `docs/ADR-002-RULES-AND-SAVES.md` — rules versioning and saved-game compatibility.
- `docs/ADR-003-ANDROID-ENGINE-RUNTIME.md` — accepted JNI runtime and GPL release boundary.
- `docs/NATIVE_ENGINE.md` — pinned patch, native package boundary, verification, and release gates.
- `docs/ANDROID_MACHINE_VERIFICATION.md` — pinned Android toolchain and device evidence gate.
- `contracts/` — language-neutral JSON contracts for rules and saved games.

The Android foundation lives under `android/` and includes a dependency-free Kotlin
core, immutable game sessions, position history, saved-game contracts, and an engine API.
See `docs/ANDROID_FOUNDATION.md` for its verified scope and toolchain boundary.

The chess-law layer now includes FEN, complete legal move generation, replay, repetition
keys, dead-position detection, and Drawless transition construction. Its perft evidence
and conservative boundaries are in `docs/CHESS_CORE.md`.

The game coordinator adds turn orchestration, clocks, rated/casual restrictions, engine
cancellation, stale-response protection, undo, and process-death checkpoints. See
`docs/GAME_COORDINATOR.md`.

The presentation layer adds pure board interaction, promotion, orientation, highlighting,
responsive layout policy, themes, piece-set contracts, and accessibility descriptions.
See `docs/BOARD_PRESENTATION.md`.

The Compose application adds Quick Play, custom/advanced setup, a first-run rules guide,
Room resume, clocks, SAN history, gestures, original code-native pieces, sampled close-board
move/capture sounds, five persisted visual themes, post-game results, rematches, and local
career statistics backed by immutable completed-game records. Its verified and unverified
boundaries are documented in `docs/COMPOSE_APP.md`.

The production engine-facing core now adds strict UCI parsing, lifecycle and timeout
control, cancellation draining, patch identity checks, named/custom/adaptive difficulty,
offline rating pools, hint/review request planning, and a JVM-tested native byte-transport
boundary. See `docs/ENGINE_RUNTIME.md`.

The forced-repetition exception is an actual pinned Fairy-Stockfish patch with both-color
parity and history isolation. The Android `:engine` module contains the in-process JNI
runtime, and the app selects it by default without silent fallback. The native host gate
and real Android instrumentation now pass: packaged JNI load, forced-repetition search,
close, and sequential restart were exercised independently on an API-36 x86-64 emulator
and an API-33 ARM64 physical tablet. See
`docs/FORCED_REPETITION_PATCH.md`, `docs/NATIVE_ENGINE.md`, and
`docs/ADR-003-ANDROID-ENGINE-RUNTIME.md`.

The project includes a checksum-locked Gradle 9.4.1 wrapper and a stable API-36 machine
gate. The required x86-64 emulator and ARM64 physical-device runs have both passed for the
current private-test checkpoint. Windows users can reproduce the same evidence gate
directly in PowerShell 7—without WSL—using
`pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1`.
The Windows gate accepts a complete stable build JDK 17 or 21, including Android Studio's
bundled JBR 21, while keeping project Java/Kotlin compatibility at 17. Exact Android Studio
SDK/JDK setup and commands for both host lanes are in `docs/ANDROID_MACHINE_VERIFICATION.md`.

## Current verification checkpoint

- `npm test` passes 37 JavaScript contract and adjudication tests.
- `npm run test:kotlin` passes 223 JVM/core-and-endpoint tests.
- `npm run test:audio` is a required gate over all 104 sampled effects: encoded and decoded
  uniqueness, hashes, source pins, duration bounds, silence/clipping, format, and CC0/MIT notices.
- The native engine instrumentation passes once on each runtime ABI: an Android 16/API-36
  x86-64 emulator and an Android 13/API-33 ARM64 tablet.
- The accepted 51-test app instrumentation suite passes twice from fresh processes against the
  exact clean APK pair on the tablet, emulator, and Pixel 9 Pro XL. The targeted forfeit flow
  also passes independently on all three. The tests load
  every effect through Android `MediaExtractor` and `SoundPool`. The suite covers
  Room restore/stale-write protection, rapid game replacement, native hint then bot analysis,
  responsive layouts, options, captures/history, themes/pieces, rematch, completion feedback,
  versioned game scoring, stable opponent identity across ladder changes, immutable completed-game
  history, Room v1-to-v2 migration, and career stats.
- The tested debug APK is 17,709,024 bytes with SHA-256
  `25a252a21b65a768c19b74e1dfecdb4ee7af2093ee0761c9fa06e3c85d0b87ff`; the exact acceptance
  test APK used for the 51-test device runs is 1,355,590 bytes with SHA-256
  `79d308e03b858b7ae500574ace19287336aef98fdf801037b9e8c7ccb9c75d0b`. The later
  screenshot-only instrumentation harness leaves the app APK unchanged and produces a
  1,470,750-byte test APK with SHA-256
  `41e14c71596c5946aa9e2bb073e31823efcf86f4478cda758059e1ba652aae0c`; its deterministic
  capture flow and the complete 51-test suite both pass on the emulator and tablet. The debug app is
  installed on the tablet under its separate debug package, with the production package preserved.
  The current unsigned release APK builds successfully at 12,736,428 bytes with SHA-256
  `e4b2215919e220d9e6e21159c6987b16ea0f7f3049b5659bdb0dffcf77e71bda`.

These are engineering artifacts, not a public release. Distribution authorization remains
false, and no signed APK/AAB is claimed.

## Run the rules tests

```bash
npm test
```

## Run the Kotlin core tests

```bash
npm run test:kotlin
```

## Run every verification gate

```bash
npm run test:all
```

`test:all` includes the Android wrapper/toolchain contract and native lock/package
structure gates. The full clean-source native
compile is intentionally separate because it is slow and can require a network fetch:

```bash
scripts/native-fetch-fairy.sh
npm run test:native-source
npm run test:native-patch
npm run test:native-jni-host
```

## Run the Fairy-Stockfish experiment

```bash
npm run test:engine
```

The older WASM engine experiment is deliberately isolated from the pinned native source.

## License and release source

Drawless Chess has adopted GPL-3.0-or-later for the complete application, including the
Android work linked in process with the modified Fairy-Stockfish engine. See `LICENSE`,
`NOTICE`, and `THIRD_PARTY_NOTICES.md`. The GPL permits paid distribution, but every
recipient must retain the GPL freedoms and receive access to the complete corresponding
source for the exact binary.

Create the whole-project source archive only from the exact release tree:

```bash
npm run bundle:source -- release/drawless-chess-0.2.0-source.tar.gz
```

The archive includes the complete prepared Fairy-Stockfish checkout and all Drawless
source/build material while rejecting signing secrets and generated binaries.
`docs/RELEASE_LICENSING.md` is the mandatory public-release checklist. It intentionally
keeps distribution blocked until a real immutable release identity, public source URL,
resolved third-party notice/SBOM, signing setup, and matching release evidence exist.
