# First Compose application checkpoint

Status: app runtime, JNI engine, Room resume flow, Android builds, x86-64 emulator, and
ARM64 physical-device execution verified for private testing

## Toolchain declarations

- Android Gradle Plugin 9.2.1 with built-in Kotlin enabled.
- Kotlin/Compose compiler plugin 2.3.10, matching AGP 9.2's built-in Kotlin dependency.
- Compose BOM 2026.06.00.
- Activity Compose 1.12.4.
- Stable compile/target SDK 36, Build Tools 36.0.0, and minimum SDK 26.

Official references:

- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/develop/ui/compose/bom
- https://developer.android.com/jetpack/androidx/releases/activity

## Implemented application flow

1. Modern home screen with Resume, one-tap Quick Play, and Custom Game.
2. First-run Drawless rules guide and an in-app GPL/open-source summary.
3. Casual play by default; the unfinished offline-rating mode is no longer exposed.
4. Drawless defaults with Escape and dead-position variants under Advanced Rules.
5. No automatic 50-move ending; dead-position and third-repetition adjudication remain.
6. Untimed, 3-minute, 5-minute, 10-minute, and 15+10 clocks.
7. White or Black player side and seven descriptive opponent levels without numeric Elo claims.
8. Responsive game screen, durable Room-backed Resume, post-game result, Home, and Rematch.

The game screen renders:

- Board with tap and drag input.
- Legal move, capture, selection, last move, and check states.
- Animated-drag placeholder position.
- Clocks and active/low-time treatments.
- Status and recoverable bot errors.
- SAN move history.
- Pause/resume, undo, hint, flip, and resign controls.
- Promotion and resignation confirmation dialogs.
- Per-square accessibility semantics.
- Explicit Victory/Defeat feedback with a finite code-native fireworks or cracked-glass
  finish effect and synchronized procedural pop/crack cues. Victory runs for 2.6 seconds and
  defeat for 2.2 seconds; stable result text and Home/Rematch remain immediately available.

Medium portrait tablets keep the board stacked above the controls so the 600 dp breakpoint
does not collapse the board. Landscape/expanded layouts use a side panel only when at least a
360 dp board fits, and reserve vertical space for the clock row below the board.

## Verified outside Android

- The game-screen controller is compiled with the full JVM core.
- Standard Algebraic Notation is verified for quiet moves, captures, castling,
  promotion, check, checkmate, and disambiguation.
- UI event wiring into `GameCoordinator` is tested.
- Clock text, move rows, casual/rated control visibility, hint effects, and theme changes
  are tested.
- The actual `:engine` Kotlin sources and `GameRuntime.kt` are fully JVM type-checked
  using only narrow Android and generated-BuildConfig stubs.
- The remaining Compose files are parsed for Kotlin syntax and required application
  structure.
- The structure gate requires `:app` to depend on `:engine`, verifies the factory API,
  and rejects a release configuration that can select the development bot.
- `npm run test:kotlin` passes 196 tests at this checkpoint. This includes the core,
  native transport, and fake-native JNI-port lifecycle suites; it is not an Android
  binary or device test.

## Engine selection and visible failure behavior

`GameRuntime` now uses `AndroidFairyEngineFactory` by default in both ordinary debug
builds and release builds. The factory installs the packaged variant configuration in
private app storage, verifies its locked SHA-256, creates the in-process JNI port, and
owns the UCI timeout scheduler. Native diagnostics go to Logcat under
`DrawlessChessEngine`.

The legal-move-only `DevelopmentChessEngine` remains available solely for deliberate UI
development. It must be selected when making a debug build:

```bash
cd android
./gradlew :app:assembleDebug -Pdrawless.useDevelopmentEngine=true
```

That selection displays a visible in-game notice. Release hardcodes
`USE_DEVELOPMENT_ENGINE=false`; supplying the property cannot enable it. Synchronous
factory-time installation, construction, or linkage failures are logged and converted to
a non-playing failed engine so the existing bot-error UI can display the cause. Managed
JNI startup and UCI handshake failures propagate through the transport and are shown when
the first bot analysis is requested; for a human playing White, that is normally after the
first human move. The terminal UCI state retains the asynchronous startup cause, so later
analysis reports include the native failure detail. Neither path selects the development bot.

`MainActivity` is single-task, and an Activity-scoped `DrawlessAppViewModel` exclusively owns
one `GameRuntime`. Exit and replacement synchronously close that runtime before constructing
another. JNI close also waits when `nativeCreate` is still in flight, preventing a rapid
Start -> Exit -> Start sequence from overlapping process-global Fairy sessions.

## Remaining product integrations

Billing, ads, analytics, and premium entitlements are not connected. The current
chess-piece drawings and launcher icon are original code/vector assets, and move/capture plus
firework/glass finish sounds are synthesized by project code rather than loaded from a sampled recording. Their
release provenance is recorded in root `NOTICE` and `THIRD_PARTY_NOTICES.md`.

The exposed piece set, launcher mark, and move/capture sounds are now self-contained release
assets rather than placeholders. Unexposed theme contracts and future motion work remain
optional product extensions; the release build must still not ship while release-blocking
adapters or legal gates remain unresolved.

The `:app` module now depends on `:engine`. That library contains the factory, Android
timeout scheduler, managed JNI port, native command bridge, pinned Fairy-Stockfish source
target, and legal assets. The project has selected GPL-3.0-or-later for the combined app;
the private/public distinction no longer determines the code license. A particular public
binary remains blocked by the exact-source, notices/SBOM, signing, and release-evidence gate.

## Android verification completed

The Windows-native gate has compiled and audited both packaged ABIs, built debug and release
artifacts, and run the real JNI instrumentation independently on an API-36 x86-64 emulator
and ARM64 physical devices. The nine-test app instrumentation suite passes on an API-36
x86-64 emulator and API-33 ARM64 tablet; the preceding eight-test checkpoint also passed on
the API-37 ARM64 phone. The suite verifies:

- exact checkpoint codec round trips for the shipped defaults and alternate tagged variants;
- revision rollback and stale previous-game write rejection;
- file-backed Room close/reopen followed by `GameCoordinator.restore`;
- a fast first-game exit, second-game creation, and real native bot response;
- full-strength hint publication followed by a bot move through the same native session;
- descriptive custom setup with Escape kept under Advanced Rules; and
- a completed-game Rematch flow; and
- deterministic two-second-plus finish timing, cue ordering, and bounded procedural PCM.

A separate host-driven physical-phone acceptance run has also covered force-stop,
relaunch, and Resume. The current machine manifests agree on the debug and unsigned-release
APK hashes; both remain private-test artifacts with distribution authorization false.

Async hint completion explicitly invalidates the Compose model on the UI coroutine scope.
This prevents a completed result from being lost if coordinator polling observes the return
to `HUMAN_TURN` in the narrow interval before the formatted hint message is published.

Lifecycle polling now stops below `STARTED`, runs only while a hint or the bot is thinking or
a clock is active, and drops from 10 Hz to 1 Hz outside analysis/active-low-time windows.

Remaining Android work includes adding the app instrumentation lane to the immutable machine
evidence bundle, sustained/low-memory testing, broader accessibility and form-factor coverage,
and migration tests when database schema version 2 is introduced.

The in-process JNI source and its JVM tests are implementation evidence only. The whole app
is now GPL-3.0-or-later, but no APK, AAB, or AAR containing Fairy-Stockfish may be publicly
distributed until the exact corresponding-source archive and URL, complete notices/SBOM,
signing setup, and matching release evidence satisfy `docs/RELEASE_LICENSING.md`.

The first SDK-backed build should be treated as an integration checkpoint, not a ceremonial
compile. Fixes from that build must be folded back into the JVM and structural gates.
