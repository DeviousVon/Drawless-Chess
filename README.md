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
- `docs/NEXT_RELEASE_GATES.md` — mandatory code, localization, optimization, version, upgrade,
  and Play country-targeting gates for current and later releases.
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
  career statistics backed by immutable completed-game records. From the current completed-game
  result, players can immediately open a beta Game Review with move grades, better-move
  suggestions, short principal variations, and an interactive move-by-move board replay. Review
  output is not persisted as a history. Its verified and unverified
boundaries are documented in `docs/COMPOSE_APP.md`.

The production engine-facing core now adds strict UCI parsing, lifecycle and timeout
control, cancellation draining, patch identity checks, named/custom/adaptive difficulty,
offline rating pools, hint/review request planning, and a JVM-tested native byte-transport
boundary. See `docs/ENGINE_RUNTIME.md`.

The forced-repetition exception is an actual pinned Fairy-Stockfish patch with both-color
parity and history isolation. The Android `:engine` module contains the in-process JNI
runtime, and the app selects it by default without silent fallback. Gameplay and hint work use
the main app process; Game Review binds to a dedicated `:review_engine` app process so its
process-global native state and coordinator launch gate are not shared with live play. Current
patch-v2 focused instrumentation passes on the API-36 x86-64 emulator and the R6 ARM64 tablet.
Exact clean, optimized 1.0.0 candidate verification on both designated physical devices remains
a release gate. See
`docs/FORCED_REPETITION_PATCH.md`, `docs/NATIVE_ENGINE.md`, and
`docs/ADR-003-ANDROID-ENGINE-RUNTIME.md`.

The project includes a checksum-locked Gradle 9.4.1 wrapper and a stable API-36 machine
gate. Historical x86-64 emulator and ARM64 physical-device runs are retained as private-test
evidence; they do not substitute for an exact 1.0.0 candidate run. Windows users can reproduce the gate
directly in PowerShell 7—without WSL—using
`pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1`.
The Windows gate accepts a complete stable build JDK 17 or 21, including Android Studio's
bundled JBR 21, while keeping project Java/Kotlin compatibility at 17. Exact Android Studio
SDK/JDK setup and commands for both host lanes are in `docs/ANDROID_MACHINE_VERIFICATION.md`.

## Current verification checkpoint

- `npm test` passes 42 JavaScript contract and adjudication tests.
- `npm run test:kotlin` passes 357 JVM/core-and-endpoint tests.
- `npm run test:audio` verifies all 103 sampled effects and 18 retained sources, including
  decoded uniqueness, hashes, source pins, duration bounds, silence/clipping, format, and notices.
- The complete host release suite passes licensing, UI structure, localization, engine parity,
  pinned native-source integrity, and Android structure gates.
- The patch-v2 fifty-move/Game Review instrumentation passes on the API-36 x86-64 emulator and
  the R6 ARM64 tablet. The designated Pixel is still required for the exact candidate device gate.
- A same-search JNI strength harness passed 332 games and 3,320 decisions across the emulator and
  R6 with zero native bestmove, ponder, legality, or strength/configuration mismatches. It covered
  all eight visible opponents plus adaptive, custom, historical-Elo, and raw-Skill boundaries; it
  was adapter evidence, not an Elo calibration or a Pixel run.
- Bob accepted gameplay responsiveness, review latency, and difficulty consistency as RC1 on debug
  APK SHA-256 `634F1F3B334D0E04FC7C15CDF6A4F22E541A990B6EF27B3F309F2237B0DEE173`, installed and launched on
  the Pixel and R6. That APK came from the earlier 355-test tree and does not verify the later
  `GameCoordinator` changes in the current 357-test worktree.
- Older APK hashes and 51-test device runs remain historical engineering evidence in the detailed
  verification documents; they are not presented as evidence for 1.0.0.

No signed 1.0.0 APK/AAB is claimed until the exact source, signing-certificate, and both-device
release gates pass.

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
npm run bundle:source -- release/drawless-chess-1.0.0-source.tar.gz
```

The archive includes the complete prepared Fairy-Stockfish checkout and all Drawless
source/build material while rejecting signing secrets and generated binaries.
`docs/RELEASE_LICENSING.md` is the mandatory public-release checklist. It intentionally
keeps distribution blocked until a real immutable release identity, public source URL,
resolved third-party notice/SBOM, signing setup, and matching release evidence exist.
