# Google Play Console reference

## Stable routing

- Account email: `bob@bostwick.us`
- Console account index: `/u/1`
- Developer display name: `BB_Games`
- Developer ID: `8465135086815564930`
- App: `Drawless Chess`
- Package: `com.drawlesschess`
- Console app ID: `4975227002124776938`
- Closed testing track name: `Alpha`
- Closed testing track ID: `4699411573101185907`
- Track URL:
  `https://play.google.com/console/u/1/developers/8465135086815564930/app/4975227002124776938/tracks/4699411573101185907`

Treat IDs and URLs as routing hints. Verify the visible developer name, app, package, and track
before writing because Console routes can change.

## Current gate discovery

Always inspect the current Console. As of 2026-08-01, production access was disabled because the
closed test showed 7 opted-in testers, below the 12-testers-for-14-days requirement. This is
historical context, not a current fact. If still gated, publish authorized updates to Alpha and
report production separately.

## Browser procedure

1. Use the Chrome control skill and a named release session so the signed-in Google session is
   retained. Prefer `/u/1`; verify `BB_Games` before continuing.
2. Open the Drawless Chess app and the chosen track. Resume a matching saved draft when present.
3. Use the accessible snapshot to locate controls. Do not reuse stale element locators after
   navigation, upload, processing, or save.
4. Set the file chooser to the `bundlePath` in the generated submission manifest. Upload exactly
   one `.aab` file.
5. After processing, compare Play's version name/code and bundle details to the manifest.
6. Fill the release name and all locale notes from the manifest. The expected locales are
   `en-US`, `de-DE`, `es-419`, `fr-FR`, and `pt-BR` when present in the source file.
7. Save, preview, resolve only routine validation issues, submit the release, handle its explicit
   confirmation modal, and verify the resulting track status.

Do not accept new legal terms, change App content declarations, alter countries/pricing, or modify
Play App Signing without explicit authorization.

## Developer API preference

When an existing external Play Developer API credential is configured and authorized for this app,
prefer the Edits API because bundle upload, localized listings, track assignment, and commit are
deterministic. Keep credentials external and never display their JSON. Verify the committed edit by
reading the track state afterward. Do not create a service account or grant roles implicitly.
