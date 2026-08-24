# Drawless Chess

This repository contains the offline Android and iOS/Kotlin Multiplatform
implementations of Drawless Chess. Android combines a
versioned no-draw rules core, Room-backed resume flow, Jetpack Compose UI, and a
pinned, patched Fairy-Stockfish engine behind an in-process JNI boundary. iOS
uses SwiftUI with the existing `multiplatform/shared-core` and Apple engine
adapter. The older JavaScript/WASM proof of concept remains a fast regression
lane; it is not either platform's shipped runtime.

Website: https://drawlesschess.com

Download Drawless Chess:

- Google Play: https://play.google.com/store/apps/details?id=com.drawlesschess
- App Store: https://apps.apple.com/app/drawless-chess/id6801584008

Project source: https://github.com/DeviousVon/Drawless-Chess

The Android and iOS apps share the decisive offline game, eight opponents, custom games,
themes, local records, and GPL-licensed engine foundation. Android 1.0.2 also includes the
on-device Game Review feature. The accepted iOS 1.0.2 (build 2) release is intentionally
core-only and does not advertise or expose Game Review.

The design and release controls are documented here:

- `docs/ARCHITECTURE.md` — Android module boundaries, runtime flow, persistence, and testing.
- `docs/ADR-001-ENGINE.md` — Fairy-Stockfish integration and forced-repetition decision.
- `docs/ADR-002-RULES-AND-SAVES.md` — rules versioning and saved-game compatibility.
- `docs/ADR-003-ANDROID-ENGINE-RUNTIME.md` — accepted JNI runtime and GPL release boundary.
- `docs/NATIVE_ENGINE.md` — pinned patch, native package boundary, verification, and release gates.
- `docs/ANDROID_MACHINE_VERIFICATION.md` — pinned Android toolchain and device evidence gate.
- `docs/RELEASE_STATUS.md` — current public versions, storefronts, source releases, price,
  and platform feature boundary.
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
result, Android players can immediately open Game Review for move grades, better-move
suggestions, short principal variations, and an interactive move-by-move board replay. The
shipped Android 1.0.2 interface labels this first review implementation Beta. Review output is
not persisted as a history. Its verified and unverified boundaries are documented in
`docs/COMPOSE_APP.md`.

The production engine-facing core now adds strict UCI parsing, lifecycle and timeout
control, cancellation draining, patch identity checks, named/custom/adaptive difficulty,
offline rating pools, hint/review request planning, and a JVM-tested native byte-transport
boundary. See `docs/ENGINE_RUNTIME.md`.

The forced-repetition exception is an actual pinned Fairy-Stockfish patch with both-color
parity and history isolation. The Android `:engine` module contains the in-process JNI
runtime, and the app selects it by default without silent fallback. Gameplay and hint work use
the main app process; Game Review binds to a dedicated `:review_engine` app process so its
process-global native state and coordinator launch gate are not shared with live play. See
`docs/FORCED_REPETITION_PATCH.md`, `docs/NATIVE_ENGINE.md`, and
`docs/ADR-003-ANDROID-ENGINE-RUNTIME.md`.

The project includes a checksum-locked Gradle 9.4.1 wrapper and a stable API-36 machine
gate. Windows users can reproduce the gate
directly in PowerShell 7—without WSL—using
`pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/android-machine-verify.ps1`.
The Windows gate accepts a complete stable build JDK 17 or 21, including Android Studio's
bundled JBR 21, while keeping project Java/Kotlin compatibility at 17. Exact Android Studio
SDK/JDK setup and commands for both host lanes are in `docs/ANDROID_MACHINE_VERIFICATION.md`.

## Verification

The repository supplies reproducible JavaScript, Kotlin, Kotlin Multiplatform,
native patch, localization, sampled-audio, Android, and iOS structure gates.
Platform artifact and device checks remain candidate-specific; a passing host
test never proves a different signed binary.

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

Drawless Chess has adopted GPL-3.0-or-later for the complete Android and iOS
applications linked in process with the modified Fairy-Stockfish engine. See `LICENSE`,
`NOTICE`, and `THIRD_PARTY_NOTICES.md`. The GPL permits paid distribution, but every
recipient must retain the GPL freedoms and receive access to the complete corresponding
source for the exact binary.

Create the iOS 1.0.2 (build 2) whole-project source archive only from the exact
clean release tree with the prepared Fairy-Stockfish staged source present:

```bash
npm run test:source-bundle
npm run bundle:source -- \
  build/drawless-chess-ios-1.0.2-build-2-source.tar.gz
```

The inclusion-only publisher reads exact committed blobs, rejects unclassified
tracked paths, applies hard deny rules, scans for private-only content, includes
the complete prepared Fairy-Stockfish staged tree except repository-local
`.git` data, `.github` administration, and nested `AGENTS.md` instructions,
and requires two byte-identical normalized builds before emitting the archive.
See `release/public-source/REBUILD-IOS.md` for manifest verification and unsigned
rebuild inspection.

`docs/RELEASE_LICENSING.md` is the mandatory public-release checklist. Each candidate remains
blocked until its immutable release identity, public source URL, third-party notices/SBOM,
signing setup, and matching release evidence exist. See `docs/RELEASE_STATUS.md` for the
v1.0.2 releases that cleared those candidate-specific gates.
