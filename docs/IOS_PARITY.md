# iOS 1.0 parity ledger

Updated: 2026-08-01

This is the working evidence ledger for the native SwiftUI host and the shared Kotlin core.
`Complete` means the iOS implementation uses the production behavior and has automated evidence.
`Partial` means a usable vertical slice exists but one or more Android behaviors or release tests are
missing. `Missing` means it is not implemented for iOS. `External` is not solvable solely in source.

The Android application remains the reference product until every release-critical row is complete.
Apple builds use the pinned native Fairy engine. The deterministic shared-rules engine is limited
to non-Apple host tests and is never considered Apple engine evidence.

Latest automated rerun on 2026-08-01:

- `npm run test:all`: 42 JavaScript contract/rules tests, 104 decoded sampled-audio assets,
  243 Kotlin core tests, and all license, Android/iOS UI-structure, localization, engine,
  native-source and Android-structure gates passed. The iOS gate verifies five procedural
  textures, five piece palettes, all six code-native shapes, and rejects Unicode-piece fallback.
  Localization validation also requires identical Apple key sets and placeholder types in all
  five locales and rejects missing/interpolated SwiftUI literals.
- Forced uncached `iosX64Test`: 11 shared parity tests, the native UCI test, and the 12-cycle
  engine lifecycle soak all executed with zero failures.
- Xcode 26.2 rebuilt the x86_64 Debug simulator app and unsigned arm64 Release device app. The
  Release app targets iOS 15, supports device families 1 and 2 and all four orientations, and
  packages the RC1 sampled-audio catalog, 9 PNG assets and 5 localization bundles.
- A Personal Team-signed Debug app launched on an iPhone 14 running iOS 26.6 and an iPad Air 2
  running iPadOS 15.8.8. All eight UI cases pass on both devices across completed final-code runs:
  all five themes plus relaunch persistence, production-engine hint/reply plus move/SAN/undo,
  portrait/landscape layout, checkpoint save/resume, Quick Play opponent selection/persistence,
  saved-game forfeit/statistics, the original seven-opponent advanced setup, and presentation-option
  persistence. The consolidated final iPhone run passed seven cases and isolated the
  theme-menu opener; the corrected theme case then passed on both devices.
- The iPad also remained live through a 12-second on-device Time Profiler trace. iPadOS 15 requires
  the owner to authorize Apple's protected `Enable UI Automation` sheet with Touch ID for each new
  test session; after that authorization the suite executes normally.
- After all simulators were shut down to isolate the broken renderer, the current app completed
  a clean unsigned Debug build for the generic iOS Simulator destination.

## Shared game implementation

| Capability | Reference implementation | iOS status | Current evidence / remaining work |
| --- | --- | --- | --- |
| FEN, legal moves, check, castling, en passant, promotion | `android/core/.../chess` | Complete | Exact sources compile in KMP; JVM and iOS x86_64 tests pass; ARM64 framework links. |
| SAN and move history | `GameHistoryPresentation.kt` | Complete | Exact presenter is exposed through `SharedGameRuntime`; KMP and iPhone UI tests verify `1. e4`. |
| Drawless and Escape adjudication | `RulesContractV1`, `DrawlessAdjudicator` | Complete | Exact rules sources and contract fixtures run in KMP. |
| Session, clocks, pause/resume, undo, resign | `GameCoordinator` | Complete | Exact coordinator sources compile in KMP; runtime and UI exercise controls. More timed-game UI cases remain. |
| Hints and assistance accounting | coordinator/scoring sources | Complete | Exact hint/scoring paths use the production Apple engine; penalties, counts, threat assistance and score breakdown are exposed and covered by shared tests. The frozen Android hint move is projected through `BoardMoveArrow` and drawn over the live SwiftUI board from the shared from/to squares. |
| Board reducer, orientation, targets, threats, check and last move | presentation sources | Complete | Exact reducer/presenter sources are shared; move selection and undo pass the iPhone UI test. |
| Promotion UI | shared reducer plus SwiftUI dialog | Partial | Implemented; an end-to-end underpromotion UI fixture remains. |
| Checkpoint model and revision semantics | coordinator sources | Complete | Exact checkpoint codec/revision contract is shared and tested; the Swift adapter writes the same JSON payload only when its revision changes. Frozen foreground Game Review roots and adjacent roots now round-trip through the Apple codec, while old payloads and invalid optional cache evidence remain playable. |

## Product functionality

| Capability | Android reference | iOS status | Current evidence / remaining work |
| --- | --- | --- | --- |
| Home and Quick Play | `DrawlessApp.kt` | Partial | Home, saved-game controls, Quick Play opponent picker/theme preview, rules, privacy and license screens are implemented. Physical iPhone and iPad cases verify opponent selection, game launch and persistence; final VoiceOver/Dynamic Type audit remains. |
| Advanced setup | `SetupScreen` | Partial | Ruleset, side, all eight RC1 opponents, clocks/increment, threat indication, copy and portraits are implemented. The original seven profiles passed on physical iPhone and iPad; Vesper/adaptive-rating device verification and the final VoiceOver/Dynamic Type audit remain. |
| Eight opponent profiles | `OpponentProfiles` | Partial | Vesper plus Mira, Theo, Rhea, Mateo, Yuna, Amara and Lucian use the RC1 IDs, exact Elo behavior, portraits, epithets and personalities. The original seven selections passed on physical iPhone and iPad; Vesper's adaptive presentation and rating loop await the current device run. |
| Playable game screen | `GameScreen.kt` | Partial | Board, clocks, status, history, hint, undo, pause, resign, promotion, retry, flip, score detail, completion feedback and review are implemented. Physical iPhone XCTest passes a production-engine hint, human move, opponent reply, SAN and undo; promotion, completion and review UI cases remain. |
| Real offline opponent | `FairyUciEngine` + pinned native bridge | Complete | The checksum-pinned Fairy-Stockfish XCFramework and production `FairyUciEngine` run through the Apple transport. Direct UCI, shared runtime, cancellation/restart and 12-cycle lifecycle tests pass; ARM64 symbols are linked into the Release app. |
| Save/resume active game | Room checkpoint store/codec | Partial | The Swift adapter durably stores the Android-compatible checkpoint by revision and implements Resume/discard/forfeit. Shared round trips pass; physical iPhone and iPad cases verify checkpoint restoration and the forfeit-and-replace path. Legacy migration coverage remains. |
| Completed-game persistence | Room completed-game rows | Partial | Append-only, game-ID-idempotent JSON history and legacy aggregate migration are implemented. Physical iPhone and iPad cases verify that forfeit appends one loss; automated legacy migration UI coverage remains. |
| Career statistics | `PlayerStatsPersistence` / `PlayerStatsScreen` | Partial | Win rate, average score, current/best streak, unassisted wins and per-opponent records are derived from durable history. Physical-device UI tests verify game/loss totals after forfeit; final migration and broader statistics UI evidence remains. |
| Review/analysis | engine review controller and presentation | Partial | Full-strength player-only review now runs speculatively during each foreground human turn through the coordinator's same-engine cancellation/drain gate. Exact roots and played-move fallbacks persist in checkpoints and seed the final runner; any missing work starts automatically behind the completion presentation. Every request reapplies strength and analysis options, with a regression proving full-strength review cannot leak into the following limited opponent request. End-to-end physical-device UI evidence remains. |
| Preferences | `GamePreferences`, `OptionsScreen` | Partial | Sound/volume, haptics, coordinates, celebrations, threat assistance and theme persist and drive adapters. Physical iPhone and iPad cases verify coordinate, threat-indication and theme persistence; sound, volume, haptics and celebration controls still need the same UI/accessibility audit. |
| Five selectable board themes and code-native pieces | theme presentation plus picker | Complete | Imperial Marble, Desert Sandstone, Glacier Slate, Verdigris Copper and Amethyst Geode are release features in iOS. All five are selectable in Options, persist across launches, and drive the live board, theme preview, deterministic material texture and matching piece palette. The Swift renderer now follows frozen Android revision `f5da065`: the king uses the notched three-point crown and circlet band, and queens use the exact per-theme outlined jewel accents. Physical iPhone and iPad tests select every theme, terminate and relaunch the app, verify Amethyst persistence, then restore Imperial Marble. Shared `P/N/B/R/Q/K` codes are asserted on JVM and iOS. A repeatable host SwiftUI catalog renders all five starting boards and 60 theme/side/piece samples; visual inspection found and fixed Amethyst overdraw, and consecutive renders were byte-identical. Debug/Release compilation and source guards pass. Sustained GPU budgeting remains separate and does not make theme functionality partial. |
| Audio | sampled catalog and sound player | Partial | All 103 RC1 samples are converted and attributable. Move, crush capture, castling, check, en-passant and checkmate choose the same authored cue classes; volume, mixing, low-time and completion cues are wired. Physical-device audition remains. |
| Haptics | `GameHaptics` | Partial | UIKit feedback is connected to game events and preference state. A supported physical device is required to verify feel and timing. |
| Completion effects | `CompletionEffectOverlay/Timeline` | Partial | Win/loss overlay and sounds are reduced-motion aware. Visual and physical-device verification remain. |
| Rules, privacy, license and about | home dialogs/options | Partial | Exact game law, local-only privacy disclosure, GPL/source and audio attribution are present. Release/legal review remains. |
| Localization | Android resources plus native Apple copy | Complete | English, German, French, Latin American Spanish and Brazilian Portuguese cover static SwiftUI copy, dynamic status/review/score/statistics formats, board accessibility and iOS-specific backup/privacy/license text. The generator requires every Apple-only key in every locale; validation enforces identical tables, placeholder types and SwiftUI literal coverage, all five `.strings` files pass `plutil`, and Debug/Release compile. |
| Offline operation | product requirement | Complete | Engine, assets, saves, history and preferences are all on-device and have no network runtime dependency; Apple runtime tests use the packaged native engine. |

## Platform and release evidence

| Gate | Status | Evidence / remaining work |
| --- | --- | --- |
| iPhone simulator build | Complete | Debug app builds for x86_64 simulator. |
| iPhone functional UI test | Complete | Personal Team-signed Debug build launches on a physical iPhone 14/iOS 26.6. All eight final cases pass across completed runs. The consolidated final run passed seven cases; its corrected adaptive theme-menu case then passed independently. |
| iPhone rotation/layout UI test | Complete | Physical iPhone 14 XCTest exposed and verified the fix for the landscape safe-area breakpoint; portrait stacking and landscape side-by-side assertions pass. |
| iPad simulator build/install | Complete | The same app builds for device family 2 and installs on iPad mini/A16/13-inch simulator images. |
| Simulator launch/UI | Partial | The x86_64 app builds and installs, but an iOS 26.2 iPhone 16e and iOS 26.3 iPad mini both fail in Apple's host renderer before app-specific drawing. `SimRenderServer` aborts command buffers and `SimMetalHost` repeatedly exits after simulated `backboardd` reports GPU error 9 (`kIOAccelCommandBufferCallbackErrorInvalidResource`). The system framebuffer is black, boot waits at BackBoard or the system app, and both Drawless Chess and Apple's Settings app hang on launch. Cold restart, a full Mac reboot, Low Quality mode, Thread Performance Checker, and the legacy framebuffer hint do not fix it. The post-reboot Settings launch again produced error 9 and failed screenshot capture. No current visual/accessibility assertion is claimed on this 2013 Intel/NVIDIA host. |
| ARM64 Apple artifact | Complete | Debug device framework links, signs, installs and executes on physical arm64 iPhone and iPad hardware. |
| Supported physical iPad | Complete | Personal Team-signed Debug build installs and launches through Xcode on an iPad Air 2/iPadOS 15.8.8, stays live through a 12-second on-device Time Profiler trace, and passes all eight final UI cases across completed runs after the owner authorizes Apple's protected Touch ID sheet. |
| VoiceOver semantics | Partial | Board cells and major controls expose identifiers/labels used by XCTest. Full audit, rotor order and announcements remain. |
| Dynamic Type, contrast, Reduce Motion | Pending | Native text participates in Dynamic Type; systematic max-size, contrast and reduced-motion tests remain. |
| Correctness/performance soak | Partial | Apple tests cover 12 create/cancel/close cycles followed by a completed engine turn and a full review. Deterministic cross-platform long replay, memory, thermal and sustained-play budgets remain. |
| GPL/source notices | Partial | Repository structure/source validation passes and notices/source links are packaged. Final distribution review of exact binary/source correspondence remains. |
| App Store controls | External | Enrollment, agreements, tax/banking, privacy answers, age rating, price, screenshots, TestFlight and review are owner/release inputs. |

## Latest repeatable commands

```bash
npm run test:all
npm run test:kmp:apple
npm run ios:generate
npm run ios:build:simulator
npm run ios:test:ui
npm run ios:preview:board
bash scripts/sync-ios-assets.sh
node scripts/sync-ios-localizations.mjs
xcodebuild -project iosApp/DrawlessChess.xcodeproj -scheme DrawlessChess \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

For a specific booted simulator, set `DRAWLESS_IOS_SIMULATOR_ID` to its UDID. Always wait for
`xcrun simctl bootstatus <UDID> -b` to report terminal `Finished` before invoking Xcode tests.
