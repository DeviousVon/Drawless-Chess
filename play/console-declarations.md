# Play Console declaration recommendations

These are draft answers for the inspected `com.drawlesschess` version `0.2.0` build. Match
them to the current Console wording, and revisit every answer if the app changes.

## Ads

- **Contains ads:** No.
- Evidence: no ad SDK, ad service, ad UI, or ad-related permission is present.

## App access

- **Access restriction:** All functionality is available without special access.
- No login, membership, location restriction, external hardware, invitation code, or
  account is required.
- Reviewer instructions/credentials: none.

## In-app products and monetization

- **In-app purchases:** None.
- **Subscriptions:** None.
- **Play Billing integration:** None.
- The selected one-time Play Store purchase price is storefront pricing and does not change
  these in-app-product answers.
- Play's managed 60-minute paid-game trial is storefront protection applied by Play; it is
  not an in-app product and requires no Play Billing integration in Drawless Chess.

## Target audience

Recommended selection, subject to the owner's confirmation of actual marketing intent:

- **13–15**
- **16–17**
- **18 and over**

Reason: the current product is a general chess strategy game, is not specifically designed
or marketed for children, and contains no child-focused characters, stories, rewards,
social features, or advertising. Keep the listing free of "for kids," "for children," or
"for all ages" claims if this 13+ audience is selected.

Do not select younger age groups merely because chess can be played by children. If
BB_Games intentionally targets users under 13, reassess the audience honestly and complete
all then-current Families Policy requirements before submission.

## Content rating questionnaire

Recommended factual answers for the current app:

| Topic | Recommended answer | Basis |
| --- | --- | --- |
| Violence | **No depicted violence** | Chess captures remove abstract board pieces; there are no people, injuries, blood, weapons, or realistic attacks. The defeat effect is abstract cracked glass, not harm to a person. |
| Fear/horror | **No** | No frightening or horror content. |
| Sexuality/nudity | **No** | None. |
| Language/humor | **No profanity or crude humor** | None. |
| Drugs, alcohol, tobacco | **No** | None. |
| Gambling/simulated gambling | **No** | No wagering, casino mechanics, or chance-based purchases. |
| User-generated content | **No** | Players cannot create or publish public content. |
| User communication | **No** | No chat, messaging, profiles, or multiplayer. |
| Location sharing | **No** | No location permission or feature. |
| Digital purchases | **No in-app purchases** | The app may have a Play Store purchase price but sells nothing inside the app. |
| Unrestricted internet access | **No** | No embedded browser or general web access. A fixed GPL source link opens externally when the user chooses it. |

Let the IARC questionnaire calculate the rating; do not manually promise a specific ESRB,
PEGI, or regional result in the listing.

## Data Safety and privacy

- Use the answers in [`data-safety.md`](data-safety.md).
- Privacy-policy draft: [`../PRIVACY.md`](../PRIVACY.md).
- The privacy URL must be public, stable, non-PDF, and accessible without signing in before
  it is entered in Console.

## Other App content questions

- News or magazine app: **No**.
- Government app: **No**.
- Financial features: **None**.
- Health features: **None**.
- Social features or online matchmaking: **None**.
- Account creation: **None**.
- Sensitive permissions/API use: **None** for the inspected build.
- Foreground service use: **None**.

## Store classification

- App or game: **Game**.
- Category: **Board**.
- Primary experience: offline single-player chess against the built-in engine.
- Package ID: `com.drawlesschess`.
- Developer display name: **BB_Games**.
- Public support email: **realitymaster@protonmail.ch**.

## Items that still require real Console output or owner action

- Identity, contact, and physical-device verification were confirmed complete in Play Console
  on July 14, 2026.
- Standard price, any launch sale, countries/regions, tax, and merchant setup for the
  selected one-time paid listing.
- Final target-audience confirmation.
- IARC rating generated from the completed questionnaire.
- Public privacy-policy URL verification.
- Exact release AAB review and Play pre-launch report.
- Closed-test opt-ins, elapsed testing period, real feedback, and production-access review.
