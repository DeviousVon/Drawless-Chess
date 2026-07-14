# Data Safety draft

Status: **copy-ready for the inspected build, but re-audit the exact release AAB**
Inspected package: `com.drawlesschess`
Inspected version: `0.1.0` (`versionCode` 1)
Source-candidate re-audit date: July 14, 2026 (repeat against the exact signed release AAB)

## Recommended Play Console answers

| Console topic | Answer for the inspected build | Notes |
| --- | --- | --- |
| Does the app collect or share any of the required user data types? | **No** | The app does not transmit user data from the device to BB_Games or an in-app third party. |
| Data collected | **None** | Do not select any personal info, financial info, location, messages, photos/videos, audio, files/documents, calendar, contacts, app activity, web browsing, app info/performance, device IDs, or other data type. |
| Data shared | **None** | There are no advertising, analytics, tracking, cloud, account, or developer-server integrations. |
| Account creation | **No accounts** | The app has no sign-in or account system. |
| Account/data deletion request | **Not applicable to an account** | Users delete installed-app data through Android Settings or uninstall. Android/cloud backups are managed through the user's device or Google account settings. |
| Privacy-policy URL | **Required before submission** | Publish `PRIVACY.md` and verify its public URL while signed out. |

If Console hides encryption-in-transit, deletion-request, or security-practice questions
after answering that no required data is collected or shared, do not invent answers for
hidden fields. If it still asks whether collected data is encrypted in transit, use the
Console's **not applicable/no data collected** route rather than implying transmission.

## Local data is not developer collection

The app stores the following only in Android private app storage:

- saved-game position and move history;
- rule, side, opponent, and clock selections;
- game result and clock state;
- counts of hints, undos, and pauses needed to restore game state;
- a random local player identifier, immutable completed-game records, calculated game
  scores, and derived wins, losses, averages, and streaks;
- selected theme; and
- whether the introductory guide has been seen.

This local processing supports gameplay, Resume, player statistics, and preferences. It is
not sent to BB_Games and is therefore not declared as collected or shared in the Data Safety
form.

## Android system backup nuance

The release manifest currently has `android:allowBackup="true"`. Android or the device's
backup provider may therefore include local game/preferences data in a device or cloud
backup when the user has system backup enabled. BB_Games does not receive, access, or
control that backup. The public privacy policy discloses this platform behavior and tells
users how to manage local data and backups.

Before submission, compare this treatment with the current Data Safety instructions shown
by Play Console. If a future app build adds its own backup service, network transport,
account, cloud sync, support upload, analytics, diagnostics reporting, ads, billing, or any
other SDK that sends data off device, this draft is no longer valid.

## Evidence from the inspected repository/build

- The app source manifest declares no network or sensitive user-data permission.
- The merged release manifest has no `INTERNET`, location, camera, microphone, contacts,
  notification, or shared-storage permission. Its only `uses-permission` is the
  app-defined, signature-protected
  `com.drawlesschess.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` added for Android component
  safety; it does not grant access to user data.
- Dependencies are AndroidX Activity/Compose, Room, AndroidX test tooling, and the local
  chess engine modules. There is no advertising, Play Billing, analytics, authentication,
  networking, cloud, or crash-reporting SDK.
- The only user-triggered URL actions open the fixed public-source and privacy-policy
  links through an external browser; the app itself has no internet permission.
- Technical engine/hint messages can be written to Android Logcat. The app contains no SDK
  or code that uploads those logs to BB_Games.

Google Play itself may process purchase, download, device, and platform diagnostic data
under Google's policies. That platform processing is disclosed in `PRIVACY.md`; it is not
an in-app data transmission by Drawless Chess.
