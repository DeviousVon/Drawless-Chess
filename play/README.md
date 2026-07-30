# Google Play submission package

Current Play state (July 30, 2026): **`0.3.0` (`versionCode` 3) is active in
Closed testing — Alpha; Play Console shows six testers.**
Next candidate: **`1.0.0` (`versionCode` 4)**. Its listing and release copy are prepared here;
do not describe that candidate as live until Play Console confirms it.
App: Drawless Chess
Package: `com.drawlesschess`
Developer display name: BB_Games
Support/privacy email: support@drawlesschess.com
Website: https://drawlesschess.com
Prepared: July 30, 2026

This directory contains copy-ready drafts for the Google Play listing and App content
forms. The listing and release-note drafts describe the planned `1.0.0` (`versionCode` 4)
closed-test update; status notes distinguish it from the live `0.3.0` (`versionCode` 3)
Alpha. Re-audit the exact signed App Bundle before submission if code, dependencies,
permissions, data handling, features, or the version changes.

## Contents

- `listing-*.md` — title, short and full descriptions, localized metadata, and localized
  `1.0.0` release notes.
- [`release-notes-1.0.0.md`](release-notes-1.0.0.md) — one copy-ready block for each of
  en-US, de-DE, es-419, fr-FR, and pt-BR.
- [`data-safety.md`](data-safety.md) — Data Safety answers and the source evidence behind
  them.
- [`console-declarations.md`](console-declarations.md) — ads, app access, audience,
  content-rating, category, and related App content recommendations.
- [`closed-test-kit.md`](closed-test-kit.md) — tester invitation, coverage checklist,
  feedback template, and a privacy-safe tracking format.
- [`../PRIVACY.md`](../PRIVACY.md) — public privacy policy.
- [`../docs/GO_LIVE_STATUS.md`](../docs/GO_LIVE_STATUS.md) — verified candidate evidence and
  the ordered owner checklist for production launch.

## Before using these drafts in Play Console

1. Identity, contact-email, contact-phone, public-developer-email, and physical-device
   verification were confirmed complete in Play Console on July 14, 2026.
2. Use `https://drawlesschess.com/privacy/` as the privacy-policy URL and
   `https://drawlesschess.com` as the listing website. Verify both in a signed-out browser
   before reusing them in Play Console.
3. The owner selected a one-time paid listing; confirm the standard price and any launch sale.
   Keep Play's automatic protection and managed 60-minute paid-game free trial enabled. The
   trial is added to the App Bundle by Play and does not require Play Billing integration in
   the game. An app permanently offered free cannot later become paid under the same package,
   and monetizing a personal account causes Google to display the full payments-profile
   address. Personal accounts also display verified legal-name/country information.
4. Create and inspect the exact signed release AAB. Confirm its package, version,
   permissions, native libraries, target API, 16 KB compatibility, and GPL corresponding
   source before upload.
5. The current Alpha shows six testers as of July 30. Keep the real opt-in link and,
   when applicable, paid-app promo codes private; replace invitation placeholders only in
   private outreach.
6. Use Play Console's current wording when a question differs from these notes. Never
   claim that testing, feedback, production access, or approval occurred until it did.
7. Publish the exact `drawless-chess-1.0.0-source.tar.gz` and its SHA-256 on the GitHub
   `v1.0.0` release before submitting or distributing the `1.0.0` build whose in-app source
   link points there.

The icon, feature graphic, and ten screenshots form the documented July 18 set associated with
the `0.3.0` Alpha. Because `1.0.0` adds Vesper and Game Review Beta, do not label that screenshot
set current for `1.0.0` without a new visual audit; refresh affected images as one coherent set.
Exact historical sources, transforms, dimensions, and hashes remain under `store-assets/`.
