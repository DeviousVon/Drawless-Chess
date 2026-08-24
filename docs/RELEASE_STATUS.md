# Release status

Verified: 2026-08-24

This is the current public-release index. Older engineering checkpoint documents retain the
evidence and open questions that applied when they were written; they are not the storefront
status for version 1.0.2.

## Current releases

| Platform | Store version | Public storefront | Corresponding source |
| --- | --- | --- | --- |
| Android | 1.0.2, version code 6 | [Google Play](https://play.google.com/store/apps/details?id=com.drawlesschess) | [GitHub release v1.0.2](https://github.com/DeviousVon/Drawless-Chess/releases/tag/v1.0.2) |
| iPhone and iPad | 1.0.2, build 2 | [App Store](https://apps.apple.com/app/drawless-chess/id6801584008) | [GitHub release ios-v1.0.2-build-2](https://github.com/DeviousVon/Drawless-Chess/releases/tag/ios-v1.0.2-build-2) |

Apple accepted iOS 1.0.2 (build 2), and its public App Store page is live. Google accepted
Android 1.0.2 for production, and the reviewed production rollout, 56-country availability,
listing copy, and screenshot updates were published through managed publishing on 2026-08-24.
The U.S. public Play page is live at $4.99 with the current copy and replacement screenshot set;
other regional storefronts may still lag while Google propagates the release.

## Product and purchase boundary

- The U.S. price is $4.99 as a one-time purchase. There is no subscription or in-app purchase.
- There is no public free trial. Closed-test purchases used Google Play test payment instruments.
- Android and iOS include decisive offline chess, eight on-device opponents, custom games,
  five themes, resume, hints, undo, rematches, and local records.
- Game Review is an Android 1.0.2 feature. It analyzes the current completed game on the device,
  without uploading the game. The shipped Android interface labels this first implementation Beta.
- The accepted iOS 1.0.2 (build 2) release is core-only. Its Release configuration does not expose
  Game Review, and its App Store copy and screenshots do not claim that feature.

## Release controls

The Android v1.0.2 and iOS v1.0.2-build-2 GitHub releases are immutable source identities for
the corresponding store binaries. Historical plans, draft prices, test-payment notes, and old
store screenshots inside a source snapshot do not override the current storefront terms above.
Future binaries must clear the candidate-specific signing, source, store-metadata, localization,
device, and destination-verification gates in `docs/RELEASE_LICENSING.md`.
