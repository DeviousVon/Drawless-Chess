# Closed-test kit

Status: **template only; no testers, opt-ins, test period, or feedback are claimed**

For a new personal developer account, follow the current requirement shown in Play Console.
The working release plan is to keep at least 12 testers opted in to the closed test for 14
continuous days before applying for production access. Recruit 15–18 people as a buffer,
because merely inviting someone does not count as an opt-in and a tester may leave.

Keep tester email addresses, Google Group membership, promo codes, and identifiable
feedback outside the public repository. A private email list or Google Group should be
managed directly in Play Console.

## Tester invitation template

Subject: Help test Drawless Chess on Android

```text
Hi,

I'm preparing Drawless Chess for Google Play and would appreciate your help with its closed Android test.

Drawless Chess is an offline single-player chess game with decisive no-draw rules. To participate, please:

1. Open this private opt-in link while signed in to the Google account you use in the Play Store:
   [CLOSED-TEST OPT-IN LINK]
2. Choose to become a tester, then install Drawless Chess from the Play Store link shown there.
3. Stay opted in until [TEST END DATE]. You do not need to play every day, but please play several games during the test.
4. Send feedback to realitymaster@protonmail.ch using the short template below.

[IF THE APP IS PAID: Redeem this one-time promo code privately before installing: PROMO-CODE. Please do not share it.]

Please try a second game or rematch after finishing the first—this specifically helps us verify that the chess engine starts cleanly every time. If anything fails, include your device model, Android version, app version, what you tapped, and what happened. Please do not send passwords, account details, payment information, or unrelated personal data.

Thank you.
BB_Games
```

Delete the bracketed paid-app paragraph for a free listing. Never reuse or publicly post an
assigned promo code.

## Suggested tester coverage

Each tester does not need to cover every item. Coordinate the group so the combined test
covers phones, tablets, older supported Android versions, and current Android versions.

- Install from the Play closed-test link, launch, and read/dismiss the first-run rules guide.
- Start Quick Play; make several moves; leave the app; force-stop or swipe it away; relaunch
  and Resume.
- Finish or resign a game, observe the result feedback, then start a rematch or a completely
  new second game. Confirm there is no "Engine session has failed" message.
- Play as White and as Black.
- Try at least two bot levels, including one lower and one higher level.
- Try untimed play and one clock option.
- Exercise Hint, Undo, Pause/Resume, Flip, and Resign.
- Try tap-to-move and drag-to-move, including a capture and, if practical, promotion.
- Switch among themes on the home screen and during a game; relaunch and confirm the chosen
  theme remains selected.
- Confirm move/capture sounds and victory/defeat effects are understandable and not
  disruptive.
- Enable airplane mode before launch and confirm ordinary gameplay remains functional.
- Check portrait and landscape; tablet testers should also check the larger layout.
- Note visual clipping, unreadable text/pieces, accessibility issues, overheating, unusual
  battery drain, crashes, freezes, slow engine responses, and save/resume failures.

Do not tell testers a result is expected to pass. Ask for honest observations.

## Feedback template

```text
Date:
Device manufacturer/model:
Android version:
Drawless Chess version (from App info):
Phone or tablet:

Number of games started:
Number of games finished or resigned:
Features tried:

What worked well:
What was confusing or difficult:
Problem summary (if any):
Exact steps to reproduce:
Expected result:
Actual result:
Did it happen again? Always / Sometimes / Once
Screenshot or screen recording available? Yes / No

Overall: Ready / Ready with minor issues / Needs another build
```

Ask before forwarding identifiable feedback. Remove notifications, account names, email
addresses, serial numbers, and other unrelated personal information from screenshots or
logs.

## Privacy-safe tracking table

Keep the real email-to-ID mapping privately outside the repository. A public or committed
feedback summary should use IDs only.

| Tester ID | Opt-in confirmed date | Still opted in | Device/Android | Areas covered | Feedback received | Blocking issue |
| --- | --- | --- | --- | --- | --- | --- |
| T01 |  |  |  |  |  |  |
| T02 |  |  |  |  |  |  |

Do not prefill dates, results, or feedback. Play Console, not this table, determines whether
the production-access testing requirement has been met.

## Production-access evidence outline

After the real test—not before—summarize:

- how testers were recruited and how many actually opted in;
- device and Android-version coverage;
- how often and how deeply testers used the game;
- recurring positive and negative feedback themes;
- each material issue found, the build that fixed it, and how the fix was rechecked; and
- why the observed evidence supports production readiness.

Keep those answers factual. Do not infer feedback from silence or represent automated tests
as human closed-test activity.
