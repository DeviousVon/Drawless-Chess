# Drawless Chess go-live status

Status as of August 3, 2026: **The local RC1 source includes the visual hint correction,
durable exact Game Review prefetch across Save & exit/Resume, and the owner-approved piece
legibility correction. The exact clean, optimized 1.0.0 Android candidate/device proof and
public production release remain blocked.**

Google Play closed testing currently serves 0.3.0 (`versionCode` 3) on the Alpha track; six testers
were opted in when the Console was inspected on July 30. The owner selected a one-time paid listing,
and the standard price is still awaiting final approval. The authorized upload key already exists
and must be reused as-is. Never generate, rotate, replace, or reset that key or certificate without
Bob's explicit authorization for that exact action.

The current RC1 worktree is frozen locally for final publication preparation. Every artifact and device claim
below is scoped to its recorded source or binary identity; neither a branch name nor an older APK
silently verifies a later commit. The July 14 test-harness APK's emulator/tablet suite and
independent repository review are complete, but those artifacts predate the patch-v2 engine
candidate.

## Current patch-v2 engine candidate

- Fairy-Stockfish upstream remains pinned at commit
  `fb78cb561aa01708338e35b3dc3b65a42149a3c4` and upstream tree
  `dfe4b96037c10ab60e22613bf634452612fc2b04`.
- The current patched tree is `bf58452cf6bb2254050e7aa442d2b23f3664aaec`; the fourth-patch
  SHA-256 is
  `22c8327ed64a2d7711695d372d817a6e037bca5fdbfc24ad812c1e869e59bedd`, the ordered patch-series
  composite SHA-256 is
  `7501f6322ee73b9d737c387e32f1c45cb6de7d0cb3b67648601e0236fca799ed`, and the variant
  configuration SHA-256 is
  `0570d4805f915c2c77228babc31e127c4155413dba4d335ccb527dc2b974d28f`.
- Drawless patch interface v2 carries the complete `RulesContractV1` surface into native search:
  Drawless/Escape stalemate, repetition and its forced exception, both bare-king and dead-position
  choices, all 50-move choices and tie breakers, and the documented terminal precedence.
- `0004-preserve-drawless-deeper-search-boundaries.patch` protects those outcomes through deeper
  main/quiescence pruning, mixed terminal sets, quiet stalemate and bishop/knight underpromotion,
  node-neutral probes, null-history, legal-only en-passant keys, terminal-child ponder handling,
  and the custom-variant Syzygy guard.
- The clean Linux x86-64 source/patch verifier passes exact replay, the direct native-state harness,
  and the full UCI acceptance matrix, including both-color quiet stalemates beyond the sparse
  material frontier and the node-neutral speculative-probe assertion.
- The Kotlin core harness currently passes 362 tests. Full WSL headless `--validate-only` also
  passes with the rebuilt patch-v2 engine and runner. Headless campaign, puzzle-candidate, and
  puzzle-verification schemas are version 2 and include the bare-king policy in rule fingerprints;
  the soak supervisor requires the schema-2 bare-king value, and older schema-v1 material must be
  migrated, re-mined, or re-verified rather than mixed. This is validation, not a new duration or
  strength claim.
- Game Review analysis version 2 records full native contract-v1/patch-v2 fidelity and fails closed
  on a wrong patch, mixed engine identity, mismatched request, or illegal/post-terminal replay.
- Final Capture result copy in all five shipped locales now describes the terminal move rather than
  assuming it was a capture, covering the quiet bishop/knight-underpromotion edge honestly.
- The current production `FairyUciEngine.kt` blob
  `2b378b704ba15deeeffc87b9fa5519a1174da58f` passed a same-search JNI strength harness on the
  API-36 x86-64 emulator and R6 ARM64 tablet: 332 games and 3,320 decisions across all visible,
  adaptive, custom, historical-Elo, and raw-Skill cases, with zero native bestmove, ponder,
  legality, or strength/configuration mismatches. This proves adapter fidelity, not Elo calibration,
  independent strength samples, or Pixel execution.
- Debug APK SHA-256
  `634F1F3B334D0E04FC7C15CDF6A4F22E541A990B6EF27B3F309F2237B0DEE173` was installed and launched
  on the designated Pixel and R6 with app data preserved, and Bob accepted its responsiveness,
  review latency, and difficulty consistency as RC1. Its retained performance report records the
  earlier 355-test tree; later `GameCoordinator` changes and the current 357-test tree are therefore
  not covered by that APK's device result.
- The isolated review-engine class passed three instrumentation tests on both the emulator and R6,
  with a distinct `:review_engine` process and no fatal app-process crash. In the retained synthetic
  benchmark, all four final-turn responsiveness gates passed and enabled main-pulse p95 was lower
  than disabled in all four runs. The R6 full-scenario wall ratio nevertheless exceeded the old
  1.15 diagnostic limit twice (`1.1766x` and `1.1775x`); owner acceptance does not turn that metric
  into an automated pass.
- The exact clean, optimized 1.0.0 candidate still requires new artifact hashes, installation,
  launch, engine verification, and upgrade checks on the designated Pixel phone and R6 tablet.
  Game Review remains labeled Beta. Its engine Gate 1 closes only after that exact candidate proof;
  evidence Gate 0 and experience/persistence/exit Gates 2-5 remain open.

## Frozen hint correction

- A successful hint now carries the engine-validated best move into the gameplay board and draws
  the same from-square/to-square arrow used by Game Review. The existing localized hint message
  remains available as supporting text; the arrow clears when the position changes or assistance is
  replaced.
- The shared arrow renderer, core hint state, localized accessibility descriptions, controller
  assertions, and Compose coverage are included in this frozen candidate. The focused Compose test
  passed on the API-36 emulator; the debug artifact used for that check was
  `C:\src\android\app\build\outputs\apk\debug\app-debug.apk`, SHA-256
  `E6E92B83C6E2332160499C552CCBBF83E9BBA659F0D4D21739EAF5EE1C6BE999`.
- The focused hint-arrow test later passed on the API-36 emulator, Pixel 9 Pro XL, and R6 tablet.
  That evidence remains scoped to the frozen hint commit; the later combined RC1 still requires
  exact-candidate proof on both designated physical devices.

## August 3 RC1 additions

- The supplied king uses its approved three-point crown and colored circlet band on Android and
  the Web Casual board. Its path is locked as approved.
- The original queen silhouette is retained. Its four crown jewels now use outlined, theme-aware
  accent colors: the white queen uses the bright theme accent and the black queen uses crimson.
  The owner approved this exact visual after reviewing the R6 evidence sheet.
- Compact 14/18/24dp tests retain the king-vs-bishop and king-vs-pawn silhouette gates and require
  the queen jewel accent to survive rasterization. The focused two-test visual class passes on the
  API-36 emulator and R6 tablet.
- Version identity remains `1.0.0` with `versionCode` 4. The debug candidate was installed and
  launched on the emulator and R6; the Pixel was disconnected, so the exact two-device gate remains
  open.
- Completed foreground Game Review roots and adjacent fallback evidence are persisted immediately
  with exact history, rules, request, response, analysis-version, and embedded-engine identity
  validation. Compatible evidence survives Save & exit/Resume without another native search;
  stale or incompatible evidence is discarded without blocking the saved game.

## Completed in the July 14 readiness baseline

- Google Play personal-account creation, registration payment, identity verification, all three
  contact channels, and the separate physical-device verification are confirmed complete in
  Play Console.
- The final package ID is `com.drawlesschess`; debug builds use `com.drawlesschess.debug`.
- An unfinished game can no longer be silently replaced. Starting another game or leaving the
  game first shows: "Are you sure you want to forfeit your current game? It will count as a loss
  in your stats." Cancel preserves the game; confirmation durably records one loss before the
  replacement starts.
- The exact forfeit transaction is idempotent, rejects stale or mismatched game IDs, and cannot
  report success without the expected terminal checkpoint and completed-game history row.
- Player-facing statistics copy no longer explains implementation details that could suggest a
  force-close exploit.
- License direction is settled: the combined application is GPL-3.0-or-later. Pinned engine
  source, patches, checksums, notices, provenance, and source-bundle tooling are present.
- Store-listing, privacy, Data Safety, content-rating, target-audience, closed-test, and release
  runbook drafts are prepared under `play/` and `docs/PLAY_RELEASE_GUIDE.md`.
- The icon, feature graphic, and ten screenshots remain valid depictions of the 0.3.0 baseline, but
  they predate the 1.0.0 Game Review and Adaptive-opponent additions. Phone images came from the
  API-36 emulator and tablet images from the physical API-33 tablet; exact provenance, transforms,
  dimensions, and hashes are recorded under `play/store-assets/`.
- The 103-file sampled-audio gate passes decoding, format, duration, silence/clipping,
  uniqueness, hash, and source-provenance checks.
- Patch-v1 host verification passed: 37 JavaScript tests and 223 Kotlin tests. Compose, Android,
  native-source, patch-integrity, license-structure, and release lint gates pass. Lint reports
  zero errors and six existing non-blocking warnings.
- Fresh fail-closed Android machine gates pass on both supported runtime ABIs:
  Android 16/API-36 x86-64 emulator and Android 13/API-33 ARM64 tablet. Both builds package
  `arm64-v8a` and `x86_64` and identify the historical patch-v1 tree
  `80208e5f35549b88505df983e4bc0f7621083fd4`.
- The exact clean app/test APK pair passes the targeted forfeit test plus the entire 51-test app
  suite twice from fresh processes on emulator, tablet, and Pixel 9 Pro XL. No crash, ANR, native fatal,
  engine-session failure, out-of-memory event, runner death, or audio resource/load failure was
  found. Tablet cold start was 2.206 seconds.

## Historical patch-v1 private-test artifacts

These are retained exact July 14 engineering artifacts, not current patch-v2 or Play-distribution
files:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug APK | 17,709,024 | `25a252a21b65a768c19b74e1dfecdb4ee7af2093ee0761c9fa06e3c85d0b87ff` |
| Acceptance test APK | 1,355,590 | `79d308e03b858b7ae500574ace19287336aef98fdf801037b9e8c7ccb9c75d0b` |
| Current screenshot-harness test APK | 1,470,750 | `41e14c71596c5946aa9e2bb073e31823efcf86f4478cda758059e1ba652aae0c` |
| Unsigned release APK | 12,736,428 | `e4b2215919e220d9e6e21159c6987b16ea0f7f3049b5659bdb0dffcf77e71bda` |

The debug APK and acceptance test APK are the exact pair used for the targeted forfeit plus
51-test-twice runs on emulator, tablet, and Pixel. The later opt-in screenshot harness changes
only the non-shipping test APK. Its deterministic capture flow passes on emulator and tablet,
and the complete 51-test suite passes once with that current pair on both devices (60.992 seconds
on API 36 x86-64 and 81.624 seconds on the physical API 33 ARM64 tablet). Fresh logcat review
found no crash, ANR, native-fatal, engine-session, out-of-memory, runner, or audio-load signature.

Machine evidence is retained locally under:

- `build/android-machine-verification/20260714-x86-final`
- `build/android-machine-verification/20260714-arm64-final`
- `build/release-qa/emulator-clean`
- `build/release-qa/pixel-clean`
- `build/release-qa/final-harness`

## Checklist before the 1.0.0 Play update

Complete these in order. Items involving identity, private keys, payment, legal terms, real
testers, or publication must be performed or explicitly approved by the owner.

1. Verify the already-authorized upload key against the pinned public certificate fingerprint and
   configure it only for the release build. If it cannot be used, stop; no key or certificate
   change and no upload-key reset is authorized by this checklist.
2. Confirm the public developer display name (`BB_Games`), support email
   (`support@drawlesschess.com`), and intended target audience (recommended: 13 and over).
3. Confirm the standard price and launch sale. Current recommendation: `$3.99` standard and a
   14-day `$2.99` launch sale, with Play's managed 60-minute paid-game trial enabled. Complete
   merchant/tax/bank setup as required for the paid listing.
4. Freeze the release commit. Build the signed release AAB and run the exact-AAB verifier for
   signature, package/version, API level, both ABIs, 16 KB native compatibility, permissions,
   dependency/SBOM evidence, notices, and corresponding source.
5. Publish and verify the privacy-policy URL while signed out. Publish the GPL corresponding-source
   archive and SHA-256 for the exact AAB on the matching `v1.0.0` GitHub release; verify the in-app
   source link before submission.
6. Re-audit Play Console App content: Data Safety, privacy URL, ads/app access, target audience,
   content rating, category, pricing, countries, and store listing. Use the prepared drafts, but
   match the live Console wording and the exact signed AAB.
7. Update the existing closed-test release and keep the self-enrollment Google Group configured.
   Testers need not provide addresses in advance: share the Group link, Play opt-in link, and
   individual promo codes after the release is published. Keep at least 12 opted in continuously for 14 days; aim
   for 15–18 so one dropout does not reset the minimum.
8. Collect honest dated feedback, fix release blockers, issue a new tested build when necessary,
    and apply for production access only after Play reports the closed-test requirement satisfied.
9. Review the final production submission and staged-rollout settings personally. Public release
    remains blocked until Google grants production access and the owner explicitly approves launch.

## Known non-blockers and deferred evidence

- The owner account has an Ubuntu WSL2 distribution. A restricted sandbox identity cannot
  enumerate it, which caused the earlier false "no distribution" result. Using Ubuntu's existing
  GNU Make/G++ and a checksum-verified portable Node 24.14.0 under `/tmp`, the then-current
  patch-v1 verifier compiled the unpatched and patched engines and passed identity, ELO-rounding,
  forced repetition, history isolation, and stopped-search isolation gates. No package was installed in
  or removed from the owner's distribution.
- A portable trusted FFmpeg build was used only for the sampled-audio decode audit.
- Sustained low-memory testing, a Play pre-launch report, the signed-AAB check, and closed-test
  feedback necessarily remain future release evidence.
