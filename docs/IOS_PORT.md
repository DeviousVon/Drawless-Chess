# Drawless Chess 1.0 multiplatform and iOS port

Status: isolated compatibility lane; Android runtime remains unchanged

## Release objective

The first public iOS product and the corresponding Android milestone use product version
`1.0.0`. The engineering target is feature parity with the current offline Android game,
adapted to Apple platform conventions without changing Drawless game law, opponent behavior,
local-only operation, assistance restrictions, or the five shipped localizations.

The iOS work lives on `codex/kmp-ios-1.0`. Existing Android modules remain the production
implementation until the gates below pass. No Google Play upload key, upload certificate,
or signing identity change is part of this port.

## Migration boundary

The KMP module is an evidence and production-sharing lane, not yet a replacement for the Android
module boundary. It compiles these exact existing Android `:core` sources directly into JVM and
Apple artifacts:

- immutable model, position history, and game session;
- versioned Drawless/Escape adjudication;
- FEN, legal moves, check state, castling, en passant, promotion, SAN, and repetition keys;
- known dead-position detection and perft;
- the complete coordinator, checkpoint codec, scoring and presentation reducers;
- the production `FairyUciEngine` behind platform transports.

The files remain under `android/core/src/main/kotlin`; the KMP build does not copy or fork their
implementation. Small JVM-only dependencies now have explicit common/platform adapters. Apple
uses the checksum-pinned Fairy-Stockfish XCFramework, a serialized UCI transport, cancellable
native timers and an output-drain contract so startup, cancellation, review and close/restart
share the same production engine behavior.

The KMP build is deliberately separate from the Android Gradle build. Both use the repository's
checksum-pinned Gradle wrapper, while a KMP-only build failure cannot alter Android packaging.

## Required replacement gates

Android may consume the KMP artifacts only after all of these are true:

1. **Game-law identity:** all existing JVM tests and language-neutral contract tests pass, and
   the same rules/chess sources pass on the host iOS simulator target and an ARM64 device target.
2. **Save compatibility:** Android 0.3.x saves, completed-game history, settings, ratings, and
   statistics round-trip through both platform adapters with no destructive migration.
3. **Engine parity:** the pinned Drawless Fairy-Stockfish build passes ordinary, avoidable-
   repetition, forced-repetition, lifecycle, cancellation, close, and restart fixtures on iOS.
4. **Feature parity:** Quick Play, advanced setup, both rulesets, all eight RC1 opponents, clocks,
   hints, review, undo/pause restrictions, themes, pieces, sounds, completion feedback, resume,
   career statistics, rules/about/legal screens, and all localizations have a recorded result.
5. **Accessibility and layout:** VoiceOver labels and actions, Dynamic Type behavior, contrast,
   reduced-motion behavior, iPhone/iPad layouts, rotation, and touch targets pass on supported
   simulators and at least one supported physical device.
6. **Correctness soak:** deterministic self-play and replay comparison finds no rules, SAN,
   outcome, save, rating, or review divergence between the legacy Android and shared lanes.
7. **Performance:** interaction remains responsive at 60 Hz, engine requests obey the same
   configured time limits, and launch, memory, battery/thermal, and sustained-play results meet
   device-specific budgets established before the Android runtime switch.
8. **Release controls:** GPL corresponding source and notices match the exact binaries; Apple
   enrollment, agreements, privacy, age rating, price, screenshots, TestFlight, and review gates
   are complete. Distribution remains separate from engineering verification.

Until every applicable gate has evidence, the legacy Android lane remains buildable and is the
rollback implementation.

## Verification

Fast cross-platform compilation and JVM tests:

```bash
npm run test:kmp
```

On macOS, also link the ARM64 device framework and the simulator framework for the host Mac:

```bash
npm run test:kmp:apple
```

The current Intel Mac uses the `iosX64` simulator framework. Release XCFramework assembly will
combine device and simulator slices after both compile independently.

The current Compose Multiplatform release no longer publishes an Intel iOS-simulator target.
For this Intel Mac, `iosApp` is therefore a thin SwiftUI host around the shared Kotlin framework.
It provides a runnable simulator and native Apple shell now and can host a Compose controller
later when development moves to Apple Silicon or a supported ARM64 device. This avoids pinning
the 1.0 release to an obsolete Compose runtime merely to retain Intel-simulator execution.

Regenerate and build the unsigned simulator host with:

```bash
npm run ios:generate
bash scripts/sync-ios-assets.sh
node scripts/sync-ios-localizations.mjs
npm run ios:build:simulator
npm run ios:test:ui
```

The UI suite launches `com.drawlesschess` and verifies the native accessibility tree, live
shared-core results (`20` legal moves and perft depth two of `400`), all five themes plus relaunch
persistence, a production-engine hint and reply, move/SAN/undo, rotation, checkpoint resume,
Quick Play opponent selection/persistence, the forfeit-and-replace path with durable loss
statistics, the original seven-opponent advanced setup, and presentation-option persistence.
All eight cases pass on a physical iPhone 14 running iOS 26.6 and a physical iPad Air 2 running
iPadOS 15.8.8 across the completed final-code runs. The consolidated iPhone run passed seven
cases and isolated the theme-picker opener; the corrected theme case then passed on both devices.
Earlier iPad runs likewise exposed only iPadOS 15 accessibility-query differences, and every
corrected case passed. On the current Intel host, the simulator still stalls before `XCTRunner`
because of the host graphics/runtime failure described below; physical-device evidence supersedes
that simulator limitation for the covered cases.
The current implementation and evidence ledger is kept in
[`IOS_PARITY.md`](IOS_PARITY.md).
On this Intel/NVIDIA Mac, CoreSimulator's framebuffer capture is black, so simulator screenshots
are not accepted as visual evidence. Visual screenshots remain a physical-device or modern Apple
Silicon simulator gate rather than inferred release evidence.

The SwiftUI board no longer depends on chess-font glyphs. It ports the Android product's original
vector geometry for pawn, knight, bishop, rook, queen and king, including each theme's fill,
outline, detail and king-accent palette. Its square renderer deterministically draws the five
shipped Sandstone, Marble, Slate, Verdigris and Amethyst materials from the algebraic square seed.
Both the live board and theme preview use this renderer. `npm run test:ios-structure`, included in
`npm run test:all`, rejects a Unicode fallback or an incomplete palette/texture/piece catalog;
the Xcode Debug and unsigned arm64 Release builds provide compiler evidence. Imperial Marble,
Desert Sandstone, Glacier Slate, Verdigris Copper and Amethyst Geode are all selectable iOS release
features—not optional diagnostics. Physical iPhone and iPad XCTest now select all five and verify
the final selection across termination/relaunch. Sustained rendering budgets remain a separate
validation gate and do not remove or downgrade the themes.
`npm run ios:preview:board` additionally renders a 3288×952 host SwiftUI catalog containing all
five starting boards and every side/piece combination. Inspection of that catalog caught and fixed
Amethyst facets painting outside their square; the structure gate now requires the cell clip and
the catalog harness. This is renderer evidence, not a substitute for iPhone/iPad layout or GPU
performance evidence.

The Android catalog is also synchronized with an Apple-only supplement for all five shipped
locales. That supplement covers native status/review/score formats, statistics, board VoiceOver
labels, and Apple-specific device/iCloud-backup, privacy and GPL copy. The localization gate checks
that every locale has the same keys and placeholder types, rejects an unlocalized SwiftUI literal
or interpolation fallback, and parses every generated `.strings` file with Apple tooling before
the Xcode compiler packages all five bundles.

## Known external constraints

- A physical iPhone 14 on iOS 26.6 is paired with Developer Mode enabled. Signed launch succeeds,
  and all eight final UI cases pass across the completed physical-device runs.
- A physical iPad Air 2 on iPadOS 15.8.8 accepts the signed arm64 app, launches through Xcode,
  stays live through a 12-second Time Profiler trace, and passes all eight final UI cases across
  completed runs after the owner authorizes Apple's protected `Enable UI Automation` Touch ID
  sheet. The protected sheet still requires a person for each new automation session; tests cannot
  authorize it themselves.
- The iOS 26.2 and 26.3 runtimes are installed. An iOS 26.2 iPhone 16e and an iOS 26.3 iPad mini
  both fail before app-specific rendering: host logs show `SimRenderServer` command buffers being
  aborted and `SimMetalHost` repeatedly terminating after `backboardd` reports GPU execution
  error 9 (`kIOAccelCommandBufferCallbackErrorInvalidResource`). The result is a black system
  framebuffer, a boot wait at BackBoard, and hung launches for both Drawless Chess and Apple's
  Settings app. A cold simulator restart, a full Mac reboot, Low Quality mode, disabling Thread
  Performance Checker, and Apple's legacy framebuffer-emulation hint do not resolve the failure;
  the hint and scheme diagnostic were restored after testing. Immediately after the full reboot,
  the dedicated iPad simulator again stalled waiting on the system app, Settings launched without
  a usable framebuffer, screenshot capture failed, and `SimMetalHost` reported the same GPU error
  9. This is a 2013 Intel/NVIDIA host-renderer failure, not an app launch failure. Visual and
  expanded XCTest evidence must be rerun on a modern supported host.
- A newer physical iPad or external TestFlight tester is still desirable before RC1 for modern-
  hardware visual, thermal and extended-performance coverage.
- Individual Apple Developer enrollment, paid-app agreements, tax/banking setup, and the final
  App Store price are owner-controlled release inputs and do not block unsigned simulator work.
- App Store distribution of the GPL-3.0-or-later application remains a release/legal gate; the
  technical port does not silently waive it.
