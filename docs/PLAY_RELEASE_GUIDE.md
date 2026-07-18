# Google Play Release Guide

**Public-release status: BLOCKED.** Drawless Chess must not be marked distribution-ready until every required gate in this guide has passed and the evidence has been recorded.

This is a plain-English runbook for a new personal Google Play Console account. Google can change Play Console screens and policies; follow the current Console prompt if it differs from this guide.

Verified in Play Console on July 14, 2026: the personal developer account is created, the
registration fee is paid, identity verification is approved, all three contact channels show
verified, and the separate physical-device verification task is complete.

## 1. Completed account setup

The project owner has already completed the following signup actions. They are retained here
as an account-history reference, not as remaining work:

1. Signed in to [Google Play Console](https://play.google.com/console/) with the long-term owner account.
2. Chose a **Personal** developer account, accepted the signup agreements, and paid the
   US$25 registration fee. See [account types](https://support.google.com/googleplay/android-developer/answer/13634885)
   and [payment methods](https://support.google.com/googleplay/android-developer/answer/9875040).
3. Linked the Google Payments profile and completed identity plus private/public contact
   verification. See [identity verification](https://support.google.com/googleplay/android-developer/answer/10841920)
   and [required account information](https://support.google.com/googleplay/android-developer/answer/13628312).
4. Completed physical-device verification. See [device verification](https://support.google.com/googleplay/android-developer/answer/14316361).

For a personal account, Google publicly displays the verified legal name, country, and
developer email. If the developer monetizes on Google Play, Google also displays the full
address from the linked payments profile. Review those disclosures before choosing paid
distribution or any other monetization. See Google's [developer information shown on
Play](https://support.google.com/googleplay/android-developer/answer/13628312) and
[personal-account information requirements](https://support.google.com/googleplay/android-developer/answer/13634081).

Those four steps were confirmed complete in Console on July 14, 2026. The remaining
owner-only account work is:

- If the game will be paid, enter and verify merchant banking and tax information directly in Play Console. Bank verification may use a small deposit or official bank document and can take up to five days.
- Review and approve all app, pricing, testing, and production submissions. Production-readiness statements must reflect real testing and feedback.

The project owner must never share Google passwords, one-time codes, government ID images, full payment-card details, bank credentials, or tax documents with an assistant or tester.

## 2. Work the assistant can prepare

The package ID is approved as `com.drawlesschess`, and the owner selected a one-time paid
listing. The final launch price still requires owner approval. This choice is asymmetric:
after this package has been offered permanently for free, Google does not allow changing that
same package to paid; a paid app may later be made free. See [pricing an
app](https://support.google.com/googleplay/android-developer/answer/6334373). The assistant can
then prepare:

- A signed release Android App Bundle (`.aab`) using a locally retained upload key and secrets that never enter chat or the repository.
- Release checks for target API, 16 KB native-library compatibility, supported ABIs, and Play packaging.
- The GPL corresponding-source bundle, `LICENSE`, `NOTICE`, and release manifest.
- Store title, descriptions, screenshots, icon, feature graphic, and release notes.
- Privacy-policy text, Data safety draft answers, content-rating notes, and target-audience notes.
- A self-enrollment Google Group setup checklist, invitation text, test scenarios, and
  feedback log. The selected Group flow does not require the owner to collect individual
  tester addresses in advance.
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

### Build and verify the Play bundle

Debug builds install as `com.drawlesschess.debug`; the Play release remains
`com.drawlesschess`, so private development builds no longer replace the store app.

Keep the upload keystore outside the repository. Signing values may come from the four
`DRAWLESS_UPLOAD_*` environment variables listed in
`android/signing.properties.example`, or from a private copy named
`android/signing.properties`. Environment variables take precedence. The properties file,
keystores, and common private-key formats are ignored by Git, and Gradle rejects a keystore
path inside the repository. If `DRAWLESS_SIGNING_PROPERTIES` is used, its custom file must
also live outside the repository.

For an update, use the same upload keystore that signed the previous Play upload. Google
does not retain its private key or its passwords. Verify the candidate keystore interactively;
never put a password on a command line or in chat:

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe' `
  -list -v -keystore 'C:\PRIVATE\PATH\drawless-chess-upload.jks'
```

Enter the keystore password at the prompt. Record the alias that `keytool` displays. The
certificate SHA-256 must be
`85:BF:58:0C:42:2F:8F:F4:02:03:E8:4B:C0:F9:FF:66:0F:AA:C8:15:E6:C4:E1:0C:87:B2:8D:58:26:96:AB:A8`.
If it differs, stop; do not build or upload with that key.

For a one-session build, set only the non-secret path and alias directly, prompt for both
passwords without echo, run Gradle, and remove the variables afterward:

```powershell
$env:DRAWLESS_UPLOAD_STORE_FILE = `
  (Resolve-Path 'C:\PRIVATE\PATH\drawless-chess-upload.jks').Path
$env:DRAWLESS_UPLOAD_KEY_ALIAS = 'ACTUAL-ALIAS-FROM-KEYTOOL'
$storeSecret = Read-Host 'Upload keystore password' -AsSecureString
$keySecret = Read-Host 'Upload key password' -AsSecureString
$storePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storeSecret)
$keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keySecret)
$locationPushed = $false
try {
  $env:DRAWLESS_UPLOAD_STORE_PASSWORD = `
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePointer)
  $env:DRAWLESS_UPLOAD_KEY_PASSWORD = `
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
  Push-Location C:\src\android
  $locationPushed = $true
  .\gradlew.bat --no-daemon :app:bundleRelease
  if ($LASTEXITCODE) { throw "bundleRelease failed ($LASTEXITCODE)" }
} finally {
  if ($locationPushed) { Pop-Location }
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePointer)
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
  Remove-Item Env:DRAWLESS_UPLOAD_STORE_FILE,Env:DRAWLESS_UPLOAD_STORE_PASSWORD,`
    Env:DRAWLESS_UPLOAD_KEY_ALIAS,Env:DRAWLESS_UPLOAD_KEY_PASSWORD `
    -ErrorAction SilentlyContinue
}
```

The store and key passwords are often identical, but the build must not assume that. If the
existing keystore or its password is lost, use Play Console's upload-key reset process; do not
create an unrelated key and attempt to upload it.

Before building, regenerate the exact dependency evidence, review and commit every release
source change, and require a clean worktree. Then create the corresponding-source archive;
the bundler refuses dirty trees and records the full source commit:

```powershell
pwsh -NoProfile -File .\scripts\generate-release-sbom.ps1
& 'C:\Program Files\Git\bin\bash.exe' -lc `
  'cd /c/src && scripts/source-bundle.sh release/drawless-chess-0.3.0-source.tar.gz'
```

The final AAB verifier independently regenerates the dependency reports and the complete
canonical source manifest. It rejects a stale SBOM, a different Git commit, any source
manifest/hash mismatch, an omitted source file, or any content difference from source
rebuilt from the clean repository.

With the upload-key configuration present, build from `C:\src\android`:

```powershell
.\gradlew.bat :app:bundleRelease
```

Without all four signing values and an external keystore, that command fails instead of
creating a new unsigned Play bundle. Do not upload a stale file merely because one remains in
the build directory from an earlier diagnostic build.

Verify the signed result from `C:\src` and preserve its public evidence report:

```powershell
pwsh -NoProfile -File .\scripts\verify-play-aab.ps1 `
  -Bundle .\android\app\build\outputs\bundle\release\app-release.aab `
  -SourceArchive .\release\drawless-chess-0.3.0-source.tar.gz `
  -ExpectedUploadCertificateSha256 '<64-HEX-UPLOAD-CERTIFICATE-FINGERPRINT>' `
  -OutputManifest .\build\release-evidence\play-aab.json
```

The verifier reads the final AAB and checks its upload-key signature, application ID,
version, SDK levels, exact `arm64-v8a`/`x86_64` ABI set, native engine presence, every native
ELF load segment, bundletool's 16 KB APK page-alignment setting, exact packaged legal/SBOM
assets, current dependency inventory, clean Git commit, and matching corresponding-source
archive. The evidence report contains hashes and the public upload-certificate fingerprint,
never a private key, password, username, or absolute local path.
The expected certificate fingerprint is public metadata produced from the locally created
upload key; pinning it prevents a validly signed bundle made with the wrong or debug key
from passing release verification.

## 4. Required closed-test process

For a new personal developer account, Google requires at least **12 testers opted in to the closed test continuously for 14 consecutive days** before the project owner may apply for production access. See [new-personal-account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).

### Recruit and invite

1. Create a self-enrollment Google Group and configure that one group address on the closed
   track. The owner does not need to collect tester email addresses in advance.
2. Create and publish the closed-test release before asking people to install. Before
   production, the app is not discoverable by ordinary Play Store search.
3. **Recommendation, not a Google requirement:** after the release is available, invite
   15–18 people so the test does not fall below 12 if someone leaves. Friends, relatives,
   colleagues, and relevant communities are acceptable recruitment sources.
4. Send the Google Group join page and the private Play opt-in link. Testers join both with
   the Google account they use in Play Store.
5. Each tester must be a member of the configured group **and** explicitly opt in before
   installing through the supplied Play Store link. Inviting someone does not start their
   14-day clock; it starts only after the closed release exists and the tester opts in. See
   [closed-test setup and eligibility](https://support.google.com/googleplay/android-developer/answer/9845334).

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

### Managed free trial for customers

Google Play now provides a managed 60-minute full-game trial for eligible paid games. New
paid games have it enabled by default, it requires Play App Signing and an App Bundle, and it
does not require Play Billing code in the game. Google adds the trial and paywall protection
to the delivered bundle; progress remains if the player buys. Keep automatic protection on
and verify the complete trial-to-purchase transition in a Play test track. See [paid-game free
trials](https://support.google.com/googleplay/android-developer/answer/16923846).

This is the recommended customer try-before-buy path. A public temporary `$0` sale gives the
game away to recipients and does not convert those users into later purchases; it is not a
trial. Closed testers who need unrestricted access should still receive individual paid-app
promo codes.

## 6. Sensitive-material handling

- Store the upload keystore outside the repository and synchronized folders; keep encrypted backups in two controlled locations.
- Keep keystore and key passwords in a password manager, never in Gradle files, scripts, chat, logs, screenshots, or CI output.
- Pass release secrets through approved environment variables or a protected secret store.
- A public upload certificate or fingerprint may be shared; the private key and passwords may not.
- Treat tester email addresses, feedback containing personal details, and promo codes as private operational data.
- Redact account numbers, legal documents, addresses, transaction IDs, and verification codes from screenshots before sharing them for troubleshooting.

## 7. Release-unblock checklist

Public release remains **BLOCKED** until all applicable items are complete:

The release after `0.2.0` must also pass every item in
[`NEXT_RELEASE_GATES.md`](NEXT_RELEASE_GATES.md). In particular, translated application resources
do not satisfy the separate Play country-targeting gate.

- [x] Personal developer account created and identity verification approved.
- [x] Contact details and Play Console physical-device verification show as approved.
- [x] Final package ID is `com.drawlesschess`.
- [x] One-time paid storefront model selected.
- [ ] Standard price and any launch sale confirmed before the first Play upload.
- [ ] Merchant payments and bank/tax verification complete if the app is paid.
- [ ] Play App Signing configured; upload key secured and backed up.
- [ ] Signed release AAB passes API, ABI, 16 KB, native, and install tests.
- [ ] Release optimization/R8, visible installed-version, old-version upgrade, and localized-device
  gates pass for the exact candidate.
- [ ] GPL source bundle, license notices, and release manifest match the AAB exactly.
- [ ] Store listing, privacy-policy URL/in-app access, Data safety, content rating, and target audience complete.
- [ ] The exact testing or production track targets the owner-approved countries/regions; verify
  the saved track summary rather than inferring availability from application languages.
- [ ] At least 12 testers satisfy the continuous 14-day closed-test requirement.
- [ ] Real feedback is recorded and release-blocking findings are fixed.
- [ ] Google grants production access.
- [ ] Final production release is reviewed and explicitly approved by the project owner.
