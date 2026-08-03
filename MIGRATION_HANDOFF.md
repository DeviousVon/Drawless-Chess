# Drawless Chess replacement-Mac handoff

Captured for migration on 2026-08-01 from Bob's Intel Mac. The complete project archive that
contains this file is the authoritative continuation copy because it includes Git metadata,
tracked edits, untracked iOS/KMP work, ignored build products, and the patched nested engine
source tree.

## Repository state at handoff

- Project: `Drawless Chess`
- Canonical working directory: `/Users/bobby/Documents/DrawlessChess`
- Remote: `https://github.com/DeviousVon/Drawless-Chess.git`
- Working branch: `codex/kmp-ios-1.0`
- Working-tree base commit: `c8bcdbd60b8c2d32a18379777cd94e2369a471fb`
- Android 1.0 RC1 source on `origin/main`: `8c7c6b642dad1063824d95a3332e41e59e9649ea`
  (`Publish Drawless Chess 1.0.0 (#8)`)
- The working tree is intentionally dirty and the branch is behind `origin/main`. Do not run
  `git reset`, `git clean`, `git checkout --`, or merge/pull over it before reviewing and
  committing the recovered work.
- Android debug (`com.drawlesschess.debug`) on the Pixel is the product-parity reference. The
  production install is not the current reference.

The handoff package also includes a Git bundle for committed refs and a machine-readable Git
status snapshot. The Git bundle does not contain uncommitted or untracked work; use the complete
project archive first.

## iOS port status

The iOS/Kotlin Multiplatform port is on `codex/kmp-ios-1.0`. Android RC1 shared game-law,
difficulty/rating, coordinator, review, and engine-policy changes are integrated into the shared
source lane. The pinned Drawless Fairy-Stockfish patch-v2 source is applied, and the Apple
XCFramework contains device arm64 plus universal arm64/x86_64 simulator slices. Apple production
keeps the RC1 two-second search grace; only the deprecated Intel simulator test process receives
additional timing tolerance.

The iOS UI now includes all eight RC1 opponents and portraits, including adaptive Vesper; the RC1
adaptive-rating behavior; player-side game review and grading; the RC1 localization catalogs; the
exact Android app-icon artwork; themes and board assets; and the complete 103-file RC1 audio cue
catalog. A signed universal Debug build, version 1.0.0 build 1 (`com.drawlesschess`), was built,
signature-verified, installed on the physical iPhone 14, launched, and confirmed in its installed
app database on 2026-08-01.

The same signed universal build was also installed successfully on the physical iPad Air 2 after
it was trusted through Apple's legacy iOS 15 USB device channel. The iPad reported
`com.drawlesschess` as installed. Xcode 26's newer CoreDevice path still labels this iPad
unavailable, so future deployments on this Intel Mac may need the legacy `ios-deploy` path. No
restore, erase, unpair, OS update, or signing change was performed.

## Last verification evidence

- `npm run test:kmp`: passed after the RC1 shared-core integration.
- `bash scripts/native-validate-structure.sh --require-source`: passed with exact patch-v2 source
  and checksums.
- Direct Apple native bridge test: passed with patch version 2 and a healthy timed search.
- `npm run test:kmp:apple`: passed on 2026-08-01 after linking both the arm64 device framework and
  x86_64 simulator framework (`BUILD SUCCESSFUL in 40m 31s`).
- The final review projection passed the JVM KMP test suite (11 tests, zero failures), and the
  updated iOS ARM64 framework linked successfully (`BUILD SUCCESSFUL in 44m 25s`).
- `npm run test:localization`: passed against the synchronized RC1 catalogs.
- `npm run test:ios-structure`: passed with the RC1 icon, five textures, five palettes, six
  code-native piece sets, eight opponents, and the RC1 audio cues.
- The final physical-device Xcode Debug build succeeded with the existing Apple Development
  identity and existing provisioning profile. `codesign --verify --deep --strict` passed.
- The final iPhone install and launch succeeded, and the device reported Drawless Chess 1.0.0
  build 1 as an installed developer app.
- The final iPad Air 2 install succeeded over trusted USB on iPadOS 15.8.8, and the legacy device
  channel confirmed that `com.drawlesschess` is installed.
- Host diagnostic Fairy-Stockfish accepted the complete RC1 UCI option sequence and returned a
  legal best move.

## Safe restore order

1. Use Migration Assistant with the accompanying TrueNAS Time Machine backup when possible. This
   is the preferred way to restore the user account, Keychain, tool settings, and signing state.
2. Copy the timestamped Drawless Chess handoff folder from TrueNAS to the new Mac.
3. From that folder, verify every payload with `shasum -a 256 -c SHA256SUMS.txt`.
4. Extract the complete archive into the new account's `Documents` folder:

   ```bash
   ditto -x -k DrawlessChess-complete.zip ~/Documents
   cd ~/Documents/DrawlessChess
   ```

5. Before any Git integration, run `git status --short --branch` and compare it with the included
   `git-status.txt`. Confirm that `iosApp/`, `multiplatform/`, `ios-engine/`, the iOS scripts, the
   new shared-core files, and the nested engine source are present.
6. Verify the supplemental history bundle with `git bundle verify ../DrawlessChess-all-refs.bundle`.
   Keep the extracted archive as the canonical copy; the bundle is disaster recovery for Git refs.
7. Install compatible tools, then rerun the checks below. Xcode and device signing may require
   owner-controlled Apple prompts on the replacement Mac.

## Toolchain captured on the old Mac

- macOS 15.7.8 on x86_64 (the old 2013 host used OpenCore Legacy Patcher)
- Xcode 26.2, build 17C52
- Temurin OpenJDK 21.0.12
- Node.js 24.18.1 and npm 11.16.0
- Gradle wrapper 9.4.1 (downloaded automatically by the repository)
- Homebrew-managed Android command-line tools/SDK; Android Studio may be installed separately

On a new Apple Silicon Mac, install Xcode and accept its license, install JDK 21, Node 24, and the
Android SDK, then let the repository wrappers restore their pinned dependencies. Do not copy the
old Intel Kotlin/Native cache as an authoritative build product; it can be regenerated.

Useful checks:

```bash
npm install
npm run test:kmp
npm run test:kmp:apple
bash scripts/native-validate-structure.sh --require-source
npm run test:ios-structure
```

## Signing and credential safety

- Never generate, rotate, replace, upgrade, or reset the Android/Google Play upload key or upload
  certificate without Bob's explicit authorization for that exact action.
- Never request or modify a Google Play upload-key reset without that same exact authorization.
- Never remove or replace an existing Apple signing certificate or provisioning profile merely to
  unblock a build.
- Use existing signing material only as-is and never print its secrets in logs or documentation.
- If Migration Assistant does not restore an accessible signing identity, stop at read-only
  diagnosis and ask Bob. A missing password, mismatch, or deadline does not authorize replacement.
- The standalone project archive is not a substitute for the old Mac's Keychain. The TrueNAS Time
  Machine backup is the recovery layer for credentials stored outside the repository.

## Device context

At final capture time an authorized Pixel 9 Pro XL, iPhone 14, and iPad Air 2 were connected. The
iPhone was paired, available, and running the installed iOS build. The trusted iPadOS 15.8.8
device was available through Apple's legacy device channel and had the same build installed.
Device pairing/trust and Developer Mode may need to be re-established on the new Mac. Do not
uninstall or overwrite either Android installation while recovering the iOS work.

## Backup integrity

The timestamped handoff folder contains `SHA256SUMS.txt`. A successful migration requires the
local and TrueNAS copies to have matching hashes and the complete ZIP to pass an archive test.
The final backup record in that folder identifies the TrueNAS/Time Machine snapshot that contains
the package.
