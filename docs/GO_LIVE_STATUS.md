# Drawless Chess go-live status

Status as of July 14, 2026: **engineering candidate verified; public release remains blocked.**

No artifact has been uploaded to Google Play. The owner selected a one-time paid listing; the
standard price is still awaiting final approval. The release package is intentionally unsigned
until the owner creates a securely stored upload key.

GitHub CLI authentication and repository-admin permission are confirmed. The reviewed work is
on `codex/go-live-readiness`. The current test-harness APK's final emulator/tablet suite and the
independent repository review are complete; the branch is ready to commit, push, and open as a
draft PR.

## Completed in the current readiness pass

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
- The icon, feature graphic, and all ten screenshots are current for the same app candidate.
  Phone images came from the API-36 emulator and tablet images from the physical API-33 tablet;
  exact provenance, transforms, dimensions, and hashes are recorded under `play/store-assets/`.
- The 104-file sampled-audio gate passes decoding, format, duration, silence/clipping,
  uniqueness, hash, and source-provenance checks.
- Host verification passes: 37 JavaScript tests and 223 Kotlin tests. Compose, Android,
  native-source, patch-integrity, license-structure, and release lint gates pass. Lint reports
  zero errors and six existing non-blocking warnings.
- Fresh fail-closed Android machine gates pass on both supported runtime ABIs:
  Android 16/API-36 x86-64 emulator and Android 13/API-33 ARM64 tablet. Both builds package
  `arm64-v8a` and `x86_64` and identify patched engine tree
  `80208e5f35549b88505df983e4bc0f7621083fd4`.
- The exact clean app/test APK pair passes the targeted forfeit test plus the entire 51-test app
  suite twice from fresh processes on emulator, tablet, and Pixel 9 Pro XL. No crash, ANR, native fatal,
  engine-session failure, out-of-memory event, runner death, or audio resource/load failure was
  found. Tablet cold start was 2.206 seconds.

## Exact private-test artifacts

These are retained exact engineering artifacts, not Play-distribution files:

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

## Owner checklist before the first Play upload

Complete these in order. Items involving identity, private keys, payment, legal terms, real
testers, or publication must be performed or explicitly approved by the owner.

1. In the prefilled Play Console Create App form, personally review and accept the Developer
   Program Policies, Play App Signing terms, and US export-law declarations, then create the paid
   game. These are owner attestations and cannot be accepted by an assistant.
2. Confirm the public developer display name (`BB_Games`), support email
   (`realitymaster@protonmail.ch`), and intended target audience (recommended: 13 and over).
3. Confirm the standard price and launch sale. Current recommendation: `$3.99` standard and a
   14-day `$2.99` launch sale, with Play's managed 60-minute paid-game trial enabled. Complete
   merchant/tax/bank setup as required for the paid listing.
4. Create a strong upload-key password in a password manager, create the upload keystore outside
   the repository and synchronized folders, keep encrypted backups in two controlled locations,
   and enable Play App Signing. Never put key material or passwords in chat or Git.
5. Freeze the release commit. Build the signed release AAB and run the exact-AAB verifier for
   signature, package/version, API level, both ABIs, 16 KB native compatibility, permissions,
   dependency/SBOM evidence, notices, and corresponding source.
6. Publish and verify the privacy-policy URL while signed out. Publish the GPL corresponding-source
   archive and SHA-256 for the exact AAB on the matching `v0.1.0` GitHub release; verify the in-app
   source link before submission.
7. Complete Play Console App content: Data Safety, privacy URL, ads/app access, target audience,
   content rating, category, pricing, countries, and store listing. Use the prepared drafts, but
   match the live Console wording and the exact signed AAB.
8. Create the closed-test release and configure a self-enrollment Google Group. Testers need not
   provide addresses in advance: share the Group link, Play opt-in link, and individual promo
   codes after the release is published. Keep at least 12 opted in continuously for 14 days; aim
   for 15–18 so one dropout does not reset the minimum.
9. Collect honest dated feedback, fix release blockers, issue a new tested build when necessary,
    and apply for production access only after Play reports the closed-test requirement satisfied.
10. Review the final production submission and staged-rollout settings personally. Public release
    remains blocked until Google grants production access and the owner explicitly approves launch.

## Known non-blockers and deferred evidence

- The owner account has an Ubuntu WSL2 distribution. A restricted sandbox identity cannot
  enumerate it, which caused the earlier false "no distribution" result. Using Ubuntu's existing
  GNU Make/G++ and a checksum-verified portable Node 24.14.0 under `/tmp`, the complete patch
  verifier compiled the unpatched and patched engines and passed identity, ELO-rounding, forced
  repetition, history isolation, and stopped-search isolation gates. No package was installed in
  or removed from the owner's distribution.
- A portable trusted FFmpeg build was used only for the sampled-audio decode audit.
- Sustained low-memory testing, a Play pre-launch report, the signed-AAB check, and closed-test
  feedback necessarily remain future release evidence.
