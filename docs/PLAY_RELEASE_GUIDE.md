# Google Play Release Guide

**Public-release status: BLOCKED.** Drawless Chess must not be marked distribution-ready until every required gate in this guide has passed and the evidence has been recorded.

This is a plain-English runbook for a new personal Google Play Console account. Google can change Play Console screens and policies; follow the current Console prompt if it differs from this guide.

## 1. What the project owner should do now

The project owner should personally complete these signup actions involving identity, agreements, money, or private account access:

1. Sign in to [Google Play Console](https://play.google.com/console/) with a Google account he intends to keep long-term.
2. Choose a **Personal** developer account, accept Google's agreements, and pay the registration fee shown in the live signup flow (currently US$25). Google lists supported credit/debit cards and does not accept prepaid cards. Keep the payment receipt. See [account types](https://support.google.com/googleplay/android-developer/answer/13634885) and [payment methods](https://support.google.com/googleplay/android-developer/answer/9875040).
3. Link or create the Google Payments profile and enter accurate legal and contact information. Be prepared to verify a private contact email and phone number, provide a public developer email, and submit a government identity document if Google requests it. All identity documents must match the Payments profile. See [identity verification](https://support.google.com/googleplay/android-developer/answer/10841920) and [required account information](https://support.google.com/googleplay/android-developer/answer/13628312).
4. Complete device verification in the Play Console mobile app. Google requires a non-rooted physical Android phone running Android 10 or newer. See [device verification](https://support.google.com/googleplay/android-developer/answer/14316361).

After steps 1–4 are approved, stop and report that account and device verification passed. The remaining work happens later:

- If the game will be paid, enter and verify merchant banking and tax information directly in Play Console. Bank verification may use a small deposit or official bank document and can take up to five days.
- Review and approve all app, pricing, testing, and production submissions. Production-readiness statements must reflect real testing and feedback.

The project owner must never share Google passwords, one-time codes, government ID images, full payment-card details, bank credentials, or tax documents with an assistant or tester.

## 2. Work the assistant can prepare

The package ID is already approved as `com.drawlesschess`. The assistant will recommend a signing strategy and explain its tradeoffs for the project owner's approval. The project owner chooses whether the game is free or paid. The assistant can then prepare:

- A signed release Android App Bundle (`.aab`) using a locally retained upload key and secrets that never enter chat or the repository.
- Release checks for target API, 16 KB native-library compatibility, supported ABIs, and Play packaging.
- The GPL corresponding-source bundle, `LICENSE`, `NOTICE`, and release manifest.
- Store title, descriptions, screenshots, icon, feature graphic, and release notes.
- Privacy-policy text, Data safety draft answers, content-rating notes, and target-audience notes.
- A blank tester-list template or Google Group setup checklist, invitation text, test scenarios, and feedback log. The project owner enters real addresses directly in Play Console or in a private file outside `C:\src`.
- Draft production-access answers based only on feedback the project owner actually received and changes actually made.

The assistant cannot accept legal terms, complete identity or device verification, enter banking/tax information, recruit real people, perform their testing, or attest that testing occurred.

## 3. Package, bundle, signing, and privacy gates

Complete these before starting the required closed test:

- Decide the final application/package ID before the first Play artifact upload. The package ID becomes fixed after upload. Keep debug builds on a separate application ID.
- Upload an Android App Bundle, which is required for new Play apps. See [Android App Bundles](https://developer.android.com/guide/app-bundle).
- Configure Play App Signing and upload a release bundle signed with a secure upload key, never a debug key. See [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756) and [Android signing guidance](https://developer.android.com/studio/publish/app-signing.html).
- Meet the current [target API policy](https://developer.android.com/google/play/requirements/target-sdk).
- Verify every native library and the final bundle for [16 KB page-size support](https://developer.android.com/guide/practices/page-sizes).
- Publish a real, public, non-PDF privacy-policy URL and provide privacy-policy text or a link inside the app. Even an offline app that collects no data needs a policy. See the [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
- Complete the Data safety form accurately. It is required for closed, open, and production tracks even when the answer is "no data collected or shared." See [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469).

## 4. Required closed-test process

For a new personal developer account, Google requires at least **12 testers opted in to the closed test continuously for 14 consecutive days** before the project owner may apply for production access. See [new-personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).

### Recruit and invite

1. **Recommendation, not a Google requirement:** recruit 15–18 people now so the test does not fall below 12 if someone leaves.
2. Recruit people who have a Gmail or Google Workspace account and a compatible Android phone. Friends, relatives, colleagues, and relevant communities are acceptable recruitment sources.
3. Collect tester email addresses with permission and keep them outside the public repository.
4. After the closed-test release exists, add testers using a Play Console email list or Google Group and publish it.
5. Send each tester the private opt-in link. Before production, the app is not discoverable by ordinary Play Store search.
6. Each tester must be included in the configured tester group **and** explicitly opt in. Then the tester installs through the supplied Play Store link. Recruiting someone does not start their 14-day clock; it starts only after that closed release exists and the tester opts in. See [closed-test setup and eligibility](https://support.google.com/googleplay/android-developer/answer/9845334).

### During the 14 days

- Keep at least 12 testers continuously opted in. If a tester opts out, their earlier days do not carry over; a replacement starts a new continuous period.
- Google does **not** publish a requirement to open the app every day. Do not claim that daily launches are mandatory.
- Google does review engagement and may require more testing when testers were not meaningfully engaged. Ask testers to play several games, exercise Quick Play, custom setup, resume, hints, and rematch, and send honest feedback.
- Maintain a dated feedback log: device type, features tried, result, issue or observation, and any resulting change. Do not fabricate entries.
- Keep the closed track active and provide updated builds when fixes are needed.

### Apply for production access

When Play Console shows that the requirement is satisfied, the project owner selects **Apply for production** and truthfully summarizes:

- How testers were recruited.
- What features they exercised and how they used the game.
- Feedback received and how it was collected.
- Changes made because of testing.
- Why the game is ready for production.

Google says this review usually takes seven days or less, but it can take longer or require more testing. Production remains blocked until Google grants production access.

## 5. Paid-app testers and promo codes

For a paid app, closed-test users normally must purchase it; paid apps are free automatically only on the internal-test track. See [testing-track payment behavior](https://support.google.com/googleplay/android-developer/answer/9845334).

The project owner can create one-time paid-app promo codes in Play Console so closed testers receive the game free:

- A promo code addresses the purchase price only. The tester must still be on the tester list and opt in to the closed track.
- Google permits up to 500 non-subscription promo codes per app per quarter.
- Paid-app promo codes do not require in-app billing integration.
- Codes are redeemable value. Send them one-to-one, track which code was assigned, and never commit them to source control or post them publicly.
- Unused quarterly codes do not roll over.

See [Google Play promo-code rules](https://support.google.com/googleplay/android-developer/answer/6321495).

## 6. Sensitive-material handling

- Store the upload keystore outside the repository and synchronized folders; keep encrypted backups in two controlled locations.
- Keep keystore and key passwords in a password manager, never in Gradle files, scripts, chat, logs, screenshots, or CI output.
- Pass release secrets through approved environment variables or a protected secret store.
- A public upload certificate or fingerprint may be shared; the private key and passwords may not.
- Treat tester email addresses, feedback containing personal details, and promo codes as private operational data.
- Redact account numbers, legal documents, addresses, transaction IDs, and verification codes from screenshots before sharing them for troubleshooting.

## 7. Release-unblock checklist

Public release remains **BLOCKED** until all applicable items are complete:

- [ ] Personal developer account, identity, contact, and physical-device verification approved.
- [ ] Final package ID and paid/free choice confirmed before the first Play upload.
- [ ] Merchant payments and bank/tax verification complete if the app is paid.
- [ ] Play App Signing configured; upload key secured and backed up.
- [ ] Signed release AAB passes API, ABI, 16 KB, native, and install tests.
- [ ] GPL source bundle, license notices, and release manifest match the AAB exactly.
- [ ] Store listing, privacy-policy URL/in-app access, Data safety, content rating, and target audience complete.
- [ ] At least 12 testers satisfy the continuous 14-day closed-test requirement.
- [ ] Real feedback is recorded and release-blocking findings are fixed.
- [ ] Google grants production access.
- [ ] Final production release is reviewed and explicitly approved by the project owner.
