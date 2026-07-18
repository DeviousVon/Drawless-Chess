# Drawless Chess international rollout plan

**Prepared:** July 15, 2026

**Scope:** prepare Drawless Chess for broad paid Google Play distribution without disrupting
the serving closed-test release.

**Status:** wave-one engineering implemented and verified for beta; Play submission requires
the external upload-signing configuration.

## 1. Decision and target outcome

Drawless Chess should be prepared for every country that Google Play currently allows to
purchase paid Android apps, with explicit holds for countries that have a material legal,
payments, sanctions, distribution, or engineering barrier.

Availability and localization are separate:

- **Availability:** a country may receive the English app when paid-app purchasing is supported,
  the English-only experience is not misrepresented, pricing is approved, and its market gate
  passes.
- **Promoted localization:** paid marketing, a localized listing, and locale-specific screenshots
  begin only after the app, rules, accessibility text, support material, and listing have passed
  the applicable language gate.

The intended result before the current testing phase ends is a **dormant international
candidate**: code, migrations, translations, tests, screenshots, price proposals, country
decisions, and Console-ready drafts are complete locally, but nothing replaces or changes the
candidate under review. Upload and Console activation are separate owner-approved actions after
the review freeze is lifted.

## 2. Non-negotiable review freeze

The current candidate and its Google Play state are immutable until Google finishes the current
review process and the owner explicitly releases the freeze.

During the freeze:

- Do not upload an AAB or APK to any Play track.
- Do not replace, promote, halt, or edit the release under review.
- Do not change Play countries, prices, listings, Data Safety, target audience, content rating,
  trial settings, or track configuration.
- Do not change the active tester list, Google Group configuration, closed-track targeting, or
  testing countries; preserve the Console's 12-tester/14-day continuity counter. Prospective
  international testers do not count until they are actually eligible and opted in after a later
  authorized change. Play Console, not a local roster, is authoritative.
- Do not use browser or Console automation.
- Do not build a signed release or give signing material to an agent.
- Do not run `bundleRelease`. Debug/test builds are normal; local unsigned `assembleRelease` and
  release lint are permitted only for private verification with evidence marked
  `distributionAuthorized: false`.
- Do not change the reviewed branch, tag, version code, version name, or artifact.
- Do not install or uninstall the production package `com.drawlesschess` during development;
  use `com.drawlesschess.debug`.
- Do not overwrite the evidence or store assets recorded for the reviewed candidate.
- Every agent must attest that it performed no Play Console action, signing, upload, production
  package change, or frozen-lane edit.

The owner may perform read-only Console inspection/export for G0 and G1. Unredacted screenshots,
submission/review IDs, tester or reviewer identities, promo codes, and private price/net notes stay
outside the public repository. Agents and automation receive only redacted evidence, and no
Console write is allowed.

The repository baseline observed while this plan was prepared is:

- Branch: `codex/go-live-readiness`
- Commit: `645de0f0b72f28a73caed57230e59fdced69c7aa`
- Release identity in source: version code `1`, version name `0.1.0`
- Debug identity: `com.drawlesschess.debug`

The repository's July 14 status says no Play artifact had yet been uploaded. The owner's newer
statement that review/testing is now active takes precedence. Gate G0 must record the actual Play
artifact and Console state before internationalization implementation begins.

## 3. Market inclusion policy

### 3.1 Automatic inclusion rule

At release-cut time, export or inspect Google's current paid-app availability table and the Play
Console country selector. Include every country that passes all ten conditions:

1. Google currently permits users there to buy and update paid apps.
2. No sanctions or payments block applies.
3. The country is not on the explicit hold list below.
4. Developer and merchant verification applicable to that country is complete.
5. The game's current content rating is accepted there.
6. Tax and product classification are complete.
7. An approved local customer price and estimated net proceeds are recorded.
8. The listing accurately describes the available languages.
9. Privacy/support material is usable for that audience.
10. The owner gives explicit activation approval after the current review finishes.

This rule is the authoritative definition of “all low-barrier areas.” Do not maintain a frozen
claim that a country is supported forever; Google changes availability, currencies, requirements,
and regional naming.

Official reference:
<https://support.google.com/googleplay/answer/143779>

### 3.2 Low-barrier launch groups

Subject to the automatic inclusion rule, prepare the following groups for availability.

#### English-first priority

- Canada, United Kingdom, Australia, New Zealand
- India, Singapore, Philippines, South Africa, Hong Kong
- Ireland as part of the complete EU block

These are the first international closed-test recruitment markets. Use `en-US` in the app,
prepare `en-GB` and `en-IN` listing variants, review local prices, and disclose English-only UI
until another locale is actually present.

#### European block

Prepare the entire EU together: Austria, Belgium, Bulgaria, Croatia, Cyprus, Czechia, Denmark,
Estonia, Finland, France, Germany, Greece, Hungary, Ireland, Italy, Latvia, Lithuania,
Luxembourg, Malta, Netherlands, Poland, Portugal, Romania, Slovakia, Slovenia, Spain, and
Sweden. Add Iceland, Liechtenstein, Norway, Switzerland, the United Kingdom, and other supported
European paid-app markets that pass the launch rule.

Do not selectively enable only a convenient subset of EU member states without a documented
legal reason. Google warns against unjustified intra-EU geo-blocking. Complete the required
developer/trader contact disclosure, tax/product classification, and public-contact review once,
then treat EU availability as a block.

Official references:

- <https://support.google.com/googleplay/android-developer/answer/6223646?hl=en-GB>
- <https://support.google.com/googleplay/android-developer/answer/138000>
- <https://support.google.com/googleplay/android-developer/answer/10463498>

#### Latin America, excluding the Brazil hold

Prepare Spanish Latin America (`es-419`) for supported paid markets including Mexico, Central
America, the Caribbean, and South America. Availability may use English fallback, but promoted
launches wait for reviewed Spanish rules, screenshots, and support copy.

#### Asia-Pacific

Prepare supported paid markets including India, Indonesia, Hong Kong, Malaysia, Philippines,
Singapore, South Korea, Taiwan, Thailand, Australia, and New Zealand. South Korea passes only
after its IARC/GRAC result is confirmed below 19+ and the no-gambling/no-loot-box declarations
remain accurate.

#### Middle East, Africa, and remaining supported markets

Prepare all countries in these regions that appear in Play's current paid-app selector and pass
the automatic inclusion rule. Use English fallback initially. Arabic promotion remains behind
the RTL language gate even when English availability is permitted.

### 3.3 Administrative gates

These markets are prepared during testing but are not activated automatically.

#### Brazil — hold pending written conclusion

Prepare `pt-BR`, pricing, screenshots, and listing drafts, but do not activate Brazil until:

- Google Payments merchant verification is complete.
- The owner has a written determination of how Brazil's Digital ECA applies to this offline chess
  game, including whether Play Age Signals must be ingested.
- The current ClassInd/IARC result is confirmed.
- The absence of loot boxes and randomized paid items is documented.
- Developer/app verification requirements effective September 30, 2026 are satisfied.

References:

- <https://support.google.com/googleplay/android-developer/answer/6223646?hl=en-GB>
- <https://support.google.com/android-developer-console/answer/16561738?hl=en>

#### Indonesia, Singapore, and Thailand — verification check

These remain launch candidates. Confirm developer identity, app ownership, and signing
registration before the September 30, 2026 Android verification date. Treat this as an account
gate unless Google identifies an application-code requirement.

#### Colombia and Peru — tax-operating hold

Google's current tax guidance says that a developer located outside Colombia or Peru remains
responsible for charging, reporting, and remitting the applicable VAT on paid-app sales in those
countries. Prepare Spanish localization, but do not activate either country until a tax adviser
provides a written conclusion and the owner has a registration, collection, reporting, and
remittance process—or explicitly excludes the country from the paid rollout.

Reference: <https://support.google.com/googleplay/android-developer/answer/138000?hl=en>

#### South Korea — rating check

Pass when the rating is below 19+ and the release still has no gambling, loot boxes, location
collection, or randomized paid items. Google's published country requirements say the special
GRAC certificate is not required for a game not deemed mature.

### 3.4 Deferred or excluded markets

| Market | Decision | Reason/unblock condition |
| --- | --- | --- |
| Japan | Defer | Paid-app business disclosures and Google's stated game-developer Local Finance Bureau notification need specialist confirmation. Google's current guidance says it handles JCT for an outside-Japan Play developer, which should be confirmed but is not treated here as a developer-remittance blocker. |
| Vietnam | Defer | Obtain a written determination that this no-Internet offline game is outside the “online electronic game” licensing regime. |
| Russia and Belarus | Exclude | Google blocks paid-app downloads and paid-app updates. |
| Sanctioned regions, including Crimea and the so-called DNR/LNR regions | Exclude | Google Play purchases are unavailable under sanctions. |
| Mainland China | Separate project | It is not a normal Google Play paid-app rollout; local stores, compliance, distribution, and likely a partner are required. |
| Any location absent from Play's paid-app buyer list | Exclude from this project | Requires a separate store, sanctions, export, payments, and support review. |

References:

- Country-specific requirements: <https://support.google.com/googleplay/android-developer/answer/6223646?hl=en-GB>
- Russia/Belarus: <https://support.google.com/googleplay/android-developer/answer/11950272?hl=en>
- Sanctions: <https://support.google.com/googleplay/android-developer/answer/11958934?hl=en>

### 3.5 Pricing policy

Use Google's generated local prices as a starting point, not the final decision.

| Band | Planning hypothesis | Typical market profile |
| --- | ---: | --- |
| A | 100% of the US reference value | Canada, UK, Australia, New Zealand, Western Europe, Nordics, Switzerland, Singapore, Hong Kong, South Korea |
| B | 60–80% | Central/Eastern Europe, Mexico, Chile, Uruguay, Gulf states, Malaysia, Thailand, Türkiye, South Africa |
| C | 25–50% | India, Indonesia, Philippines, Pakistan, Bangladesh, lower-income Latin American and African markets |

These are testing hypotheses, not automatic currency formulas. The owner must approve each
country price, tax-inclusive customer price, expected net, launch sale, and date. Retain the
managed 60-minute paid-game trial unless testing gives a documented reason not to.

References:

- <https://support.google.com/googleplay/android-developer/answer/1169947?hl=en>
- <https://support.google.com/googleplay/android-developer/answer/16923846?hl=en>
- <https://support.google.com/googleplay/android-developer/answer/112622?hl=en>

## 4. Locale waves

Translation is not a prerequisite for English availability, but it is a prerequisite for active
localized promotion.

| Wave | Locales | Purpose |
| --- | --- | --- |
| 0 | `en-US`, `en-GB` listing, `en-IN` listing, `en-XA`, `ar-XB` | Canonical source copy, English variants, expansion and RTL pseudolocale hardening |
| 1 | `es-419`, `fr-FR`, `de-DE`, `pt-BR` | Highest cross-country coverage and major paid markets; Brazil activation remains gated |
| 2 | `hi-IN`, `id`, `it-IT`, `pl-PL`, `tr-TR`, `es-ES` | Large or revenue-relevant expansion markets |
| 3 | `ko-KR`, Traditional Chinese for Taiwan/Hong Kong, `th`, Arabic | Script-, market-, or RTL-specific QA |
| Deferred | `ja-JP`, `vi`, Simplified Chinese, Russian | Only after their market gate or separate project is approved |

“Drawless Chess” remains the untranslated brand unless the owner explicitly approves a market
exception. Character names may remain stable. Epithets, personalities, levels, themes, settings,
rules, statuses, and accessibility descriptions are translated.

Every translated language requires:

- Machine draft or professional first pass.
- Native-language review.
- Chess-literate review of all rules and result language.
- Placeholder/plural/resource validation.
- A second-person in-app playthrough.
- Localized listing, release notes, screenshots, feature graphic when it contains text, privacy
  summary, support material, and asset provenance.

Google's translation tooling may be used only after the canonical resource catalog exists.
Machine translation is never final authority for rules, purchases, forfeiture, privacy, or legal
copy.

Reference: <https://support.google.com/googleplay/android-developer/answer/9844778?hl=en-EN>

Play locale tags and Android resource directories are not interchangeable. G2 must approve an
exact resource/fallback graph before files are created. The starting convention is:

- Complete default English in `values/` plus `resources.properties` declaring
  `unqualifiedResLocale=en-US` for AGP's generated locale configuration.
- Broad language bases such as `values-fr` when a language-wide fallback is intended.
- Play `es-419` mapped deliberately to Android `values-b+es+419`, with a separately reviewed
  Spain adaptation such as `values-es-rES`; do not assume one region directory inherits another.
- Traditional Chinese resources use explicit script/region qualifiers, for example
  `values-b+zh+Hant+TW` and `values-b+zh+Hant+HK`, with a deliberate common `zh-Hant` fallback if
  the contract chooses one.
- Generated locale-config contents and fallback behavior are verified for every supported
  `LocaleList`: a supported language with an unsupported region uses the approved language/script
  fallback, an unsupported language uses canonical English, and an ordered multi-locale list uses
  its first available approved match.

## 5. Current technical risks that drive sequencing

The app is not presently localization-ready:

- There is no application `strings.xml`; player-visible English is hard-coded across Compose UI,
  core presentation, errors, opponent/theme catalogs, and accessibility descriptions.
- `GameOutcome.explanation` and other English sentences originate in the Android-independent core.
- English `outcome_explanation` is stored in completed-game history, and explanation prose is
  present in checkpoint JSON.
- Some player-facing numbers force `Locale.US`.
- Several UI strings are constructed from enum names or English fragments.
- Many instrumentation tests navigate by exact English text.
- All current store screenshots and the feature-graphic tagline are English.
- The release supports API 26+ and `arm64-v8a`/`x86_64`, but not 32-bit ARM. This may reduce reach
  on lower-cost devices and must be measured in Play Device Catalog before an ABI project is
  authorized.

Important locations include:

- `android/app/src/main/kotlin/com/drawlesschess/ui/DrawlessApp.kt`
- `android/app/src/main/kotlin/com/drawlesschess/ui/GameScreen.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/Rules.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/presentation/BoardPresentation.kt`
- `android/core/src/main/kotlin/com/drawlesschess/core/presentation/GameHistoryPresentation.kt`
- `android/app/src/main/kotlin/com/drawlesschess/persistence/RoomCheckpointStore.kt`
- `android/app/src/main/kotlin/com/drawlesschess/persistence/PlayerStatsPersistence.kt`
- `android/app/src/androidTest/kotlin/com/drawlesschess/ui/StoreScreenshotHarness.kt`

The durable-data issue is the first engineering gate. Persist stable reason codes and semantic
parameters; render localized sentences only at display time. Do not add Android resource IDs to
the platform-neutral core.

## 6. Parallel subagent operating model

Use four concurrent slots: one integration gatekeeper and three bounded workers. Agents work in
separate Git worktrees and branches based on the same frozen SHA. Only the gatekeeper writes the
international integration branch.

### 6.1 Worktree and authority rules

- Create a protected frozen tag/manifest for the exact candidate under review.
- Create a separate integration branch, for example `codex/international-readiness`.
- Create one worktree and branch per worker under separate directories.
- Give each worker an exact owned-path allowlist.
- Do not allow two agents to edit canonical `values/strings.xml`, Room schema/domain files, or the
  same Compose screen in parallel.
- One resource/catalog owner controls canonical English keys.
- Translation agents own only their assigned locale directories and offline listing drafts.
- QA owns tests/check tooling and may not silently rewrite production copy.
- Integration uses small commits and coordinator cherry-picks; uncommitted work is never shared.
- Serialize emulator/physical-device access and release-like Gradle tasks.
- No agent receives signing secrets, Console access, or deployment authority.
- Before and after each packet, mechanically verify that HEAD descends from the frozen SHA, the
  worker is not on the frozen or integration branch, the diff is confined to owned paths, no
  signing or release-artifact file was added, and the handoff is clean and committed.
- Put localized candidate screenshots and graphics under a new candidate/version namespace; do
  not overwrite the recorded `play/store-assets` evidence for `0.1.0`. Canonical replacement is
  deferred to G9 after owner authorization.

### 6.2 Core worker roles

#### Agent A — semantic domain and persistence

Owns:

- Structured game outcome/status/error facts.
- Removal of English presentation authority from core models.
- Checkpoint backward compatibility.
- Room history compatibility/migration.
- Old-save, process-death, resume, forfeit, timeout, resignation, and adjudication fixtures.

Must not edit UI resource files, store material, signing, or release configuration.

#### Agent B — resources, Compose, and accessibility

Owns:

- Canonical English strings and plurals.
- Manifest/resource labels.
- Compose string resolution and locale-aware formatting.
- Opponent/theme/level resource mappings.
- Localized TalkBack descriptions and UI semantic rendering.

Must not change stable persistence identifiers or schema without returning to the contract gate.

#### Agent C — QA, validation, screenshots, and offline market packets

Owns:

- Hard-coded player-string detection and allowlist.
- Key, placeholder, markup, and plural parity checks.
- Pseudolocale setup and locale test matrix.
- Conversion of navigation tests from fragile English selectors to stable test tags where copy is
  not itself under test.
- Localized screenshot harness, asset validation, listing length validation, and offline market
  packet checks.

Must not “fix” product copy or domain behavior without returning work to the owning agent.

Ownership details fixed at G2:

- Agent A owns core/persistence fixtures and schema/codec compatibility tests.
- Agent B owns production Compose files and adds stable test tags requested through an approved
  QA ticket.
- Agent C owns test sources, scanners, harnesses, and asset validation—not production tags.
- The gatekeeper or one explicitly named worker exclusively owns shared locale configuration:
  `build.gradle.kts`, `resources.properties`, canonical locale metadata, and any manifest wiring.

### 6.3 Translation worker waves

After canonical English and the glossary are frozen, reuse the three worker slots for disjoint
locale batches. For example:

- Agent A: `es-419`, later `es-ES` adaptation.
- Agent B: `fr-FR` or `pt-BR`.
- Agent C: `de-DE` plus matching offline listing/asset draft validation.

Agents prepare translation drafts; named human reviewers approve them. No agent edits the
canonical English catalog during a translation batch. A requested English-source change reopens
the catalog gate and invalidates affected approvals.

### 6.4 Required subagent prompt contract

Every task prompt must state:

```text
Base SHA: <frozen SHA>
Integration gate: <G-number>
Worktree and branch: <isolated path and branch>
Owned paths: <exact allowlist>
Forbidden paths: frozen review lane, signing configuration, release artifacts
Forbidden actions: Play Console access/change, bundleRelease, signing, upload,
production-package install/uninstall, version-code change
Required checks: <focused commands and acceptance tests>
Required output:
1. bounded commits,
2. changed-file list,
3. tests and results,
4. unresolved risks,
5. migration/compatibility notes,
6. explicit no-live-change attestation.
Stop if the task requires an unowned file or an interface change outside the approved contract.
```

### 6.5 Loop-gated execution protocol

Every work packet follows the same loop:

1. **Inventory:** identify exact files, user states, durable-data impact, and tests.
2. **Contract:** gatekeeper approves interfaces, ownership, and exit evidence.
3. **Implement:** worker makes one bounded commit.
4. **Self-check:** worker runs focused tests and reviews its changed-file list.
5. **Integrate:** gatekeeper cherry-picks one dependency-ordered commit.
6. **Independent check:** a different worker audits behavior, tests, and scope.
7. **Repair:** the original owner receives only the failing packet and evidence.
8. **Close:** gatekeeper records acceptance criteria and no-live-change attestation.

P0/P1 defects block the gate. A P2 requires a written owner waiver and a tracked follow-up. If the
same blocking condition survives three repair loops, stop that lane and escalate with evidence;
do not spread speculative changes into adjacent areas.

## 7. Hard gates and exit evidence

### G0 — Freeze and identify the reviewed candidate

Actions:

- Record the actual Play track and review state.
- Record submitted commit SHA, version code/name, AAB SHA-256, signing certificate fingerprint,
  submission/review identifier, countries, prices, listing state, and screenshots of Console
  status.
- Store unredacted Console evidence and identifiers privately; commit only a redacted freeze
  manifest sufficient to prove artifact identity without exposing operational data.
- Protect the corresponding branch/tag and artifact evidence.
- Confirm debug/production package separation.

Pass:

- The reviewed artifact can be independently identified.
- The reviewed branch and Play state remain unchanged.
- The owner signs the freeze record.

### G1 — Market, language, pricing, and reviewer scope

Actions:

- Materialize the automatic-inclusion rule into a working country matrix.
- Assign every market `launch`, `English-only`, `localized`, `administrative hold`, or `exclude`.
- Approve locale waves, brand rules, pricing bands, English-fallback disclosure, and reviewer
  recruitment.
- Record Brazil/Japan/Vietnam and sanctions decisions.

Pass:

- Every market row has status, reason, price owner, language status, legal gate, test evidence,
  and owner approval fields.
- Human reviewers are booked for Wave 1.

### G2 — Localization architecture contract

Actions:

- Freeze resource-key naming, glossary, placeholder and plural rules.
- Define stable semantic reason/status/error/accessibility models.
- Define the core-to-app localization boundary.
- Define checkpoint and Room compatibility strategy.
- Freeze reviewed-candidate checkpoint JSON and SQLite/database fixtures before changing codecs or
  schema. Decide explicitly whether `outcome_explanation` remains a deprecated compatibility
  column in the first localized release or moves through a v3 migration; do not leave this to the
  implementation agent.
- Classify brand, URLs, FEN, SAN, PGN, UCI, database keys, test tags, IDs, and protocol tokens as
  non-translatable.
- Approve worktree/path ownership.

Pass:

- No new rendered English sentence needs to originate in core or durable storage.
- Old data has a documented forward-read strategy.
- Architecture, file ownership, and acceptance tests pass independent review.
- An executable contract commit containing semantic types/interfaces, compatibility fixtures, and
  shared ownership configuration is integrated before Agents A and B begin dependent production
  changes.

### G3 — Semantic domain and persistence compatibility

Actions:

- Persist stable `EndReason`/result codes and necessary facts, not localized prose.
- Preserve old checkpoint and database rows with migration or a tested legacy fallback.
- Make default player-name behavior locale-neutral.
- Add fixtures for every adjudication and lifecycle transition.
- Test old checkpoint JSON containing `explanation` through the new decoder; new checkpoint
  round-trip without localized authority; reviewed v2 database through the chosen v3 or
  legacy-column strategy; every `EndReason`; corrupt/missing legacy fields; and unchanged record
  counts, results, scoring, IDs, and signatures.
- State and test that downgrade compatibility is unsupported unless the owner deliberately funds
  a downgrade path.

Pass:

- Existing v1/current English checkpoints resume in any active locale.
- Existing completed history remains valid.
- Locale switching never changes IDs, signatures, scoring, results, or saved facts.
- Upgrade, process-death, export-and-restore, resume, forfeit, resignation, timeout, and
  adjudication tests pass. Actual Android/Google backup restoration is a separate manual device
  procedure recording source version/device, destination version/device, and result.

### G4 — Canonical English resource extraction

Actions:

- Add complete default strings, plurals, translator comments, manifest label resource, and
  default-locale declaration.
- Extract every player-facing UI, rule, error, catalog, result, statistics, dialog, and
  accessibility string.
- Replace enum-derived copy and sentence fragments with full positional messages.
- Replace `Locale.US` in player-facing formatting with active-locale formatting.
- Add the hard-coded-string detector and explicit allowlist.

Pass:

- English behavior and meaning match the reviewed product.
- No unapproved player-facing literal remains.
- Core tests assert facts rather than English presentation.
- Navigation tests use stable tags unless testing copy.
- Dedicated locale semantics tests still assert resource-resolved visible text and TalkBack
  descriptions for every semantic-message category; test-tag conversion must not erase language
  or accessibility coverage.
- G4 cannot close until G3's executable semantic-interface commit is integrated.
- Fast and full base-language suites pass.

### G5 — Locale platform and pseudolocale hardening

Actions:

- Enable generated locale configuration and declare `en-US` fallback.
- Let Android 8–12 follow the system locale.
- Expose supported locales in Android 13+ per-app language settings.
- Enable `en-XA` and `ar-XB` for debug/test builds.
- Test phone portrait, tablet portrait/landscape, 200% font, dialogs, first-run guide, controls,
  results, stats, captures, history, promotion, TalkBack, locale switch, process restart, and
  save/resume.
- Ensure RTL surrounding UI does not incorrectly mirror chessboard orientation or corrupt SAN and
  square notation.

Pass:

- No untranslated leak, truncation, overlap, off-screen action, crash, malformed punctuation, or
  incorrect board mirroring.
- Accessibility labels and focus order are usable.
- Pseudolocale matrix passes on API 36 x86-64 and API 33 ARM64 at minimum.
- Generated locale-config contents, advertised app languages, resource qualifiers, and the
  approved fallback graph pass automated and device tests for every supported `LocaleList`.

### G6 — Translation and linguistic approval

Actions:

- Freeze canonical English catalog and glossary.
- Translate one locale batch at a time.
- Validate key set, positional placeholder types/order, markup, escaping, and plural categories.
- Obtain native review, chess review, and second-person in-app review.
- Route source-copy ambiguity back through G2/G4 rather than silently guessing.

Pass:

- 100% required-key coverage.
- Zero extra/missing keys, placeholder mismatches, malformed XML, or untranslated accessibility
  copy.
- Named native and chess reviewers approve rules, result meanings, purchases, forfeiture,
  privacy, and support copy.

### G7 — Localized assets and offline market packets

For every marketed locale, prepare:

- App name decision, short/full listing, release notes, and character counts.
- Phone and tablet screenshots captured from the same international candidate.
- Localized feature graphic if text remains on it.
- Candidate assets stored under a new versioned namespace so the `0.1.0` evidence is untouched.
- Privacy/support material and source/license-link verification.
- Asset hashes and provenance.
- Country list, customer prices, net estimates, tax/product classification, rating, trial setting,
  and hold decision.
- A complete proposed Console diff stored offline.

Pass:

- No mixed-language or misleading asset.
- Listing claims match the exact candidate.
- Every market packet is upload-ready but remains unpublished during the freeze.

### G8 — Dormant international candidate verification

Actions:

- Integrate all approved work locally.
- Run existing host, Kotlin, JavaScript, audio, license, Compose, Android/native structure, lint,
  engine, process-restart, save/resume, forfeit, and instrumentation gates.
- Run private unsigned release-variant assembly and release lint where needed to check packaged
  resources, recording `distributionAuthorized: false`; do not sign or create/upload the Play
  bundle.
- Preserve the verified API 36 x86-64 emulator and API 33 ARM64 tablet matrix.
- Add locale/migration/RTL/font/accessibility/device-reach evidence.
- Inspect Play Device Catalog reach by country before deciding whether `armeabi-v7a` deserves a
  separate feasibility project.

Pass:

- Existing source, behavior, debug/device, and safe unsigned-release evidence remains green.
- All localization-specific evidence is green.
- Candidate and Console packet are complete locally.
- No signed release, upload, Play mutation, or version bump has occurred.
- Final signed-AAB language splits, Play delivery, generated protection, and exact-bundle
  equivalence remain explicitly deferred to G9.

### G9 — Post-review release cut

This gate cannot open until Google finishes the current review and the owner provides a separate
written authorization.

Actions:

- Merge the already-based international branch onto the owner-approved post-review base, then
  rerun migrations and all tests. Do not rebase a published evidence branch.
- Assign the next version code/name.
- Display the installed version from build metadata and eliminate stale hard-coded release
  identities from localized resources.
- Enable and verify R8 optimization/resource shrinking with JNI and generated-code keep rules;
  retain the mapping file for deobfuscation.
- Regenerate dependency/SBOM/source evidence and localized assets.
- Create the clean signed AAB using owner-controlled secrets.
- Run `scripts/verify-play-aab.ps1` on the exact AAB and source archive.
- Run Play pre-launch reports and a localized internal/closed validation release.
- Reconcile Google's current paid-country table and every price/requirement again.

Pass:

- Exact signed AAB, source archive, listings, screenshots, declarations, prices, and countries all
  agree.
- The optimized candidate passes clean install and `0.2.0` upgrade testing on the required device
  and locale matrix.
- The owner approves the exact final Console diff.

### G10 — Controlled international activation

Actions:

- Apply country targeting and localized listings in approved waves.
- Treat country targeting as a per-track hard gate. Verify the exact closed-testing track reports
  more than one targeted country/region before inviting international testers, and verify
  production targeting separately before production rollout.
- Start with English-first low-barrier markets, then Wave 1 localized markets.
- Keep held/deferred/excluded markets off.
- Monitor Android vitals, crashes/ANRs, reviews, refunds, support mail, trial conversion, purchase
  conversion, and country-level acquisition.
- Define rollback/hold thresholds before rollout begins.

Pass:

- Each wave has explicit owner approval and evidence.
- No held market is activated by bulk selection.

## 8. Schedule during testing

This schedule assumes translators and reviewers are booked immediately. It does not authorize
deployment when the review finishes.

| Days | Parallel work | Gate target |
| --- | --- | --- |
| 0–2 | Record freeze evidence; approve market/locale matrix; book reviewers; three read-only architecture inventories; freeze legacy fixtures and contract | G0–G2 |
| 3–7 | Integrate executable semantic contract first; Agent A persistence/core fixtures and implementation; Agent B English extraction; Agent C scanners and test conversion | G3–G4 integration loops |
| 8–11 | Complete migrations and extraction; pseudolocale/device/layout repair; freeze English catalog/glossary | G5 |
| 12–17 | First named translation batch (recommended `es-419`), native/chess review, screenshots, playthroughs, pricing and offline market packets | G6–G7 |
| 18–21 | Independent cross-review; full device regression; dormant English plus first-locale candidate and owner review | G8 |
| Weeks 4–6 | Remaining Wave 1 languages, then owner-selected Wave 2 languages, each through the same G6–G8 loop | Repeat G6–G8 |

Three focused weeks is a credible target for the architecture plus the first reviewed locale
batch. Four to six weeks is more realistic for all Wave 1 and Wave 2 languages with genuine human
review. Broad English availability can be prepared sooner; it must not be confused with complete
localization.

If Google finishes its review first, do not rush or upload partial internationalization. If the
international candidate finishes first, keep it dormant until the owner unlocks G9.

## 9. Owner action list

### Do now, during the review freeze

- [ ] Confirm the exact artifact currently under Play review: track, review status, commit SHA,
  version, AAB hash, and submission identifier.
- [ ] Approve and protect the frozen review lane.
- [ ] Approve the automatic-inclusion rule and explicit hold list.
- [ ] Decide whether English availability may precede local-language promotion.
- [ ] Approve “Drawless Chess” as the untranslated brand and decide whether opponent character
  names remain unchanged.
- [ ] Approve canonical English rules, result, purchase, trial, forfeit, privacy, and support tone.
- [ ] Choose the first locale batch and whether priority is revenue or audience reach.
- [ ] Recruit/pay native translators and chess-capable reviewers; name at least one independent
  in-app reviewer per shipped locale.
- [ ] Request the Brazil applicability conclusion and, if desired, Japan and Vietnam opinions.
- [ ] Decide acceptable public legal address/phone/email exposure for paid EU and global sales.
- [ ] Complete or schedule merchant bank/tax verification without sharing credentials with agents.
- [ ] Approve price-band philosophy and give a minimum acceptable net price.
- [ ] Recruit prospective international beta testers offline and reserve promo-code planning
  privately. Do not change the active Group, tester list, targeting, or Console counter during
  the freeze; recruitment is not Play eligibility or opt-in.
- [ ] Provide access to Play Device Catalog reports or export the country-weighted excluded-device
  data; do not expose account credentials.
- [ ] Identify at least one representative lower-cost ARM64 device for international QA.

### Do after the dormant candidate passes G8

- [ ] Review the complete country matrix, including every hold and English-only disclosure.
- [ ] Review translated rules, screenshots, listings, privacy/support material, and reviewer
  sign-offs.
- [ ] Approve every important local price and expected net.
- [ ] Decide whether missing 32-bit ARM reach justifies a separate feasibility project.
- [ ] Review the offline proposed Play Console diff.
- [ ] Keep signing keys and passwords outside the repository, chat, agent worktrees, logs, and
  synchronized folders.

### Do only after current review completion

- [ ] Explicitly release the review freeze and authorize G9; review completion alone is not upload
  permission.
- [ ] Approve version bump and exact merge scope.
- [ ] Create/authorize the signed release AAB and exact-AAB verification.
- [ ] Personally accept changed Play terms and attestations.
- [ ] Approve Console changes for countries, prices, listings, rating, Data Safety, audience,
  trial, tax/product classification, and rollout.
- [ ] Approve each staged international activation wave.
- [ ] Monitor support, reviews, refunds, crashes, and conversion; decide pauses or rollbacks.

## 10. Required planning and evidence artifacts

Before G8 closes, the internationalization lane should contain or produce the following. Commit
only redacted manifests, public-safe aggregates, and non-sensitive product artifacts. Keep
unredacted Console evidence, submission IDs, reviewer/tester identities and contact information,
promo codes, private pricing/net notes, and legal advice in owner-controlled private storage:

- Freeze manifest for the reviewed candidate.
- Country/market decision matrix with owner and compliance fields.
- Country pricing worksheet.
- Locale wave and human-review roster.
- Canonical English resource catalog and chess glossary.
- Stable semantic-message and persistence-migration design.
- Hard-coded-string and locale-resource validators.
- Old-save/database fixtures and migration evidence.
- Pseudolocale, RTL, font-scale, TalkBack, phone/tablet, and process-restart evidence.
- Play Device Catalog reach decision.
- Per-locale listing and asset packets with hashes/provenance.
- Complete dormant-candidate test report.
- Proposed post-review Console diff.
- Owner approvals and explicit holds.

## 11. Definition of complete

International preparation is complete when:

- The reviewed Play candidate was never changed during its review.
- Player-facing language is resource-driven and no rendered English prose is authoritative in
  core or durable storage.
- Existing saves/history survive the migration and render in the active locale.
- English, pseudolocale, accessibility, and selected real-locale matrices pass.
- Wave 1 translations and market assets have named human approvals.
- Every paid-app country has an explicit launch/English/localized/hold/exclude decision generated
  from the current Play list.
- Brazil, Japan, Vietnam, Russia/Belarus, mainland China, sanctioned regions, and unsupported
  paid markets cannot be activated accidentally.
- The country prices, support plan, privacy material, rating, and tax/product decisions are
  documented.
- The dormant candidate passes all existing Drawless Chess release gates plus localization and
  migration gates.
- No Play change happens until the owner separately opens G9 and approves the exact release.
