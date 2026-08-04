---
name: drawless-google-play-release
description: Build, verify, upload, submit, and confirm Drawless Chess releases in Google Play Console from C:\src. Use when Bob asks to update Google Play, upload an AAB, submit a release, update the Alpha closed test, check Play status, or move an eligible Drawless Chess release toward production.
---

# Drawless Chess Google Play release

Own routine Play release work end to end. Do not ask Bob for the package, account, developer,
track, AAB path, release-note locations, or Console navigation that this skill already records.

## Fixed project context

- Repository: `C:\src`
- Package: `com.drawlesschess`
- Developer account: `BB_Games`
- Preferred signed-in Console profile: the existing authorized profile for `BB_Games`
- Current pre-production route: `Closed testing - Alpha`
- ChatGPT Chrome extension: `hehggadaopoacecdllhhajmbjkdcmajg`
- Signed AAB: `android/app/build/outputs/bundle/release/app-release.aab`
- Verification evidence: `build/release-evidence/play-aab.json`
- Release notes: `play/release-notes-<version>.md`

Read [references/console.md](references/console.md) before opening Play Console. Generate the
exact machine-readable upload payload with
[scripts/New-PlaySubmissionManifest.ps1](scripts/New-PlaySubmissionManifest.ps1).

## Authorization

Treat Bob's direct request to “update Google Play”, “update the Play Store”, “upload the
release”, or equivalent as authorization to create or update the appropriate release, upload
the verified AAB, save the localized notes, advance routine review screens, confirm the rollout
dialog, and submit the release for Google review.

Do not stop to ask Bob to select files or repeat known values. Use Chrome file upload directly.
Do not infer authorization to change price, countries, tester membership, legal declarations,
payments/tax identity, app signing enrollment, or any signing/upload key.

## Workflow

1. Inspect `AGENTS.md`, concurrent work, branch, and candidate identity. Preserve unrelated
   edits. Confirm any explicit test waiver in the conversation; record it without pretending an
   omitted gate ran.
2. Before opening or editing Play Console, run the read-only Chrome upload preflight:

   ```powershell
   pwsh -NoProfile -File <skill>/scripts/Test-ChromeUploadAccess.ps1
   ```

   Require `extensionEnabled: true`, `fileUrlAccess: true`, and `ready: true`. Confirm the returned
   Chrome profile is the same profile used by the claimed Play Console tab; pass
   `-ProfileDirectory <profile>` only when the browser diagnostics identify a different profile.
   Record the extension version and install time in release evidence. A reinstall or extension
   update can clear Chrome's file-URL opt-in even though earlier uploads succeeded. If preflight
   is not ready, do not create or edit a Play draft yet; request only the one-time Chrome toggle
   described below, then rerun preflight.
3. Reuse only the existing protected external upload identity. Prefer the existing signing
   wrapper configured outside the repository for the release workstation. Never read or print
   its decrypted values. If it is unavailable, discover supported existing metadata read-only
   and stop before any key creation, replacement, or reset.
4. Require a committed clean candidate before producing the release AAB. Generate current SBOM
   and source archive, build through the existing signing wrapper, then run
   `scripts/verify-play-aab.ps1`. Do not upload a stale bundle left by an earlier build.
5. When a new test build is ready, identify the authorized devices by model and install and
   launch the separate debug package on both the designated Pixel and R6. Never store device
   serials in the repository and never replace the production package.
   If Bob explicitly waives repeated tests for a minor change, retain prior evidence only for
   the waived tests; exact build/install, signature, artifact, and destination checks still run.
6. Generate the submission manifest from the repository root:

   ```powershell
   pwsh -NoProfile -File <skill>/scripts/New-PlaySubmissionManifest.ps1 -RepositoryRoot C:\src
   ```

7. Prefer the Google Play Developer Publishing API when an already-authorized external service
   account is configured. Never create credentials or change API access implicitly. Otherwise,
   use the signed-in Chrome session and the existing Play Console app.
8. Inspect the live Console before choosing a track. Update the existing Alpha closed-test track
   while production access is unavailable. If production has since been granted, follow the
   latest active release path or Bob's explicit track request; do not claim production eligibility
   from memory.
9. Create or resume an idempotent draft for the exact version code. Upload the AAB path from the
   submission manifest with Chrome's file chooser, wait for processing, and verify the displayed
   package, version name/code, SDK/policy findings, and artifact status.
10. Fill every locale from the manifest, using Play's locale mapping exactly. Save, move to the
   preview screen, inspect warnings/errors, and submit/roll out to the selected track when no new
   legal or business decision is requested.
11. Confirm the final live state independently on the track page: version code/name, track,
    status (`In review`, `Available to testers`, or another exact Console status), rollout scope,
    and submission time. A saved draft or HTTP success is not completion.

## One-time browser prerequisite

Chrome's ChatGPT browser extension must have **Allow access to file URLs** enabled in the Chrome
profile used for Play Console so Codex can set the AAB file chooser. Chrome preserves this setting
during ordinary releases but can clear it when the extension is reinstalled or replaced. Always
use the preflight above instead of assuming a previous upload proves the current setting. If it is
disabled, ask Bob to open `chrome://extensions/?id=hehggadaopoacecdllhhajmbjkdcmajg` in that
profile, open **Details**, and enable **Allow access to file URLs**. This protected browser setting
is the only requested human action; never change it through automation. Rerun preflight and resume
immediately. Once enabled, never ask Bob to select the AAB manually.

## Idempotence and recovery

- Resume an existing matching draft rather than create duplicates.
- If version code already exists on Play, inspect its track/status. Treat a matching uploaded or
  submitted release as success after verification; never upload a different AAB under the same
  version code.
- If upload processing fails, record Play's exact error and keep the draft. Revalidate the local
  AAB before retrying.
- If Console UI labels move, use current accessible snapshots and semantics; do not ask Bob for
  navigation help.
- If production is gated by tester count/duration, continue the authorized closed-track update
  and report the production gate separately.

## Absolute safety rules

- Never generate, rotate, replace, upgrade, or reset the upload key or certificate. Never request
  or alter a Play upload-key reset without Bob's explicit authorization for that exact action.
- Never reveal or store signing secrets, passwords, OAuth tokens, service-account JSON, tester
  personal data, promo codes, tax information, or payment details in repository files or logs.
- Stop only for a genuinely new owner decision or protected Google action such as legal terms,
  identity verification, price/payment changes, or a signing-identity change—not for routine
  release mechanics.
