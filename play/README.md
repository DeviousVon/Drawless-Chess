# Google Play submission package

Status: **draft; not yet submitted or approved**
App: Drawless Chess
Package: `com.drawlesschess`
Developer display name: BB_Games
Support/privacy email: realitymaster@protonmail.ch
Prepared: July 15, 2026

This directory contains copy-ready drafts for the Google Play listing and App content
forms. The drafts describe the repository and version `0.2.0` as inspected on the date
above. Re-audit the exact signed App Bundle before submission if code, dependencies,
permissions, data handling, features, or the version changes.

## Contents

- [`listing-en-US.md`](listing-en-US.md) — title, short and full descriptions, and
  initial release notes.
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
2. Publish `PRIVACY.md` at a public, stable, non-PDF URL. After this file reaches the
   public `main` branch, a workable draft URL is
   `https://github.com/DeviousVon/Drawless-Chess/blob/main/PRIVACY.md`. Open it in a signed-out
   browser before entering it in Play Console. A dedicated HTTPS website or GitHub Pages
   URL is preferable if one is set up later.
3. The owner selected a one-time paid listing; confirm the standard price and any launch sale.
   Keep Play's automatic protection and managed 60-minute paid-game free trial enabled. The
   trial is added to the App Bundle by Play and does not require Play Billing integration in
   the game. An app permanently offered free cannot later become paid under the same package,
   and monetizing a personal account causes Google to display the full payments-profile
   address. Personal accounts also display verified legal-name/country information.
4. Create and inspect the exact signed release AAB. Confirm its package, version,
   permissions, native libraries, target API, 16 KB compatibility, and GPL corresponding
   source before upload.
5. Replace placeholders in the tester invitation with the real opt-in link and, only when
   applicable, a private paid-app promo code.
6. Use Play Console's current wording when a question differs from these notes. Never
   claim that testing, feedback, production access, or approval occurred until it did.
7. Publish the exact `drawless-chess-0.2.0-source.tar.gz` and its SHA-256 on the planned
   GitHub `v0.2.0` release before submitting or distributing the build whose in-app source
   link points there.

The icon, feature graphic, and all ten screenshots are current for the July 15 candidate. The
phone set was captured on the API 36 emulator and the tablet set on the physical API 33 tablet;
their exact sources, transforms, dimensions, and hashes are recorded under `store-assets/`.
