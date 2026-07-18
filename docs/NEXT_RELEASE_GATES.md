# Drawless Chess next-release gates

**Recorded:** July 15, 2026; last audited July 18, 2026
**Applies to:** the first release after `0.2.0` and every later Play release
**Rule:** do not submit, roll out, or invite international testers until every applicable hard
gate below has evidence and the owner approves the exact Play Console changes.

## Current Play state

Read-only inspection on July 15, 2026 showed that **Closed testing - Alpha** targets only the
United States. Application localization does not change track availability. Expanding the
countries/regions on the actual testing track is therefore a hard release gate, not an assumed
side effect of translated resources.

## Hard gates outside Play Console

- [x] Assign a new, unique `versionCode` and `versionName`; show the installed version in the
  Options screen using build metadata rather than duplicated handwritten text.
- [x] Remove or update every resource that hard-codes an old release identity, including the
  localized license copy that previously named `v0.2.0`.
- [x] Enable R8 code optimization and resource shrinking for the release build. Add and verify
  keep rules for the Fairy-Stockfish JNI boundary and any reflection/generated-code paths.
- [ ] Build and exercise the optimized release candidate before submission. Retain its R8 mapping
  file and confirm that release crash reports can be deobfuscated.
- [x] Pass localization validation: canonical-key parity, placeholders, markup, plurals, generated
  locale configuration, English fallback, and no unapproved player-facing Kotlin literals.
- [ ] Test English, French, German, Latin American Spanish, and Brazilian Portuguese on the
  supported phone/tablet matrix. Include long-text layout, large font, TalkBack, locale switching,
  process restart, Quick Play, custom games, save/resume, results, and statistics.
- [ ] Test an upgrade from the Play-delivered `0.2.0` package to the exact next candidate without
  losing saved games, settings, statistics, or engine functionality.
- [ ] Verify the exact AAB's language splits, both supported ABIs, native library loading, 16 KB
  compatibility, permissions, package identity, and version identity.
- [ ] Regenerate the GPL corresponding-source archive, dependency/SBOM evidence, release notes,
  and any screenshots or listing-source files affected by UI or copy changes.
- [ ] Run the full host, Android, native-engine, release-lint, and physical-device verification
  gates on a clean release commit.

## Hard gates in Play Console

- [ ] On the exact track that international testers will use, open **Countries / regions**, choose
  **Edit countries / regions**, and apply only the owner-approved low-barrier market list.
- [ ] Verify the saved track summary reports more than one targeted country/region and manually
  confirm representative English, EU, French, German, Spanish-Latin-American, and Portuguese
  markets. Keep every held, deferred, sanctioned, or unsupported market off.
- [ ] Confirm the approved release is associated with that same track; country targeting on one
  track is not evidence that another testing or production track is configured.
- [ ] Before production, independently verify production countries/regions, localized listings,
  prices, release notes, and delivery in representative markets.
- [ ] Record a redacted before/after checklist or screenshot of the exact country selection and
  require owner approval before saving or rolling it out.

## Product and gameplay gates

- [x] Replace generic result text with rule-specific explanations for Drawless/Escape stalemate,
  forced/avoidable repetition, dead-position material, Final Capture, bare-king loss, and the
  configured material-scored 50-move result. The explanation facts remain replay-derived, so no
  save-format migration is required.
- [x] Add optional, system-respecting haptic feedback for board interaction, moves, captures,
  checks, and game completion. It defaults on, has an Options switch, requires no vibration
  permission, and does not replay after restoring a completed game.
- [x] Add a production-engine self-play harness that treats caps as inconclusive, fails illegal or
  mismatched engine output, and records full move histories plus device/engine identity.
- [x] Pass the initial headless gates: 225 production-core tests, native JNI lifecycle/search,
  22 exact rule fixtures, four campaign derivations, 56/96 parallel matrix canaries, strict report
  resume/corruption checks, and a two-game 350 ms paired diagnostic.
- [x] Complete and interpret the 56-game same-level and 96-game adjacent-level production-budget
  matrices. The retained July 16–17 evidence completed 26 matrix pairs (3,952 games) with zero
  harness failures; capped records remain excluded from win-rate claims, and repeated fixed
  matrices are classified as stability evidence rather than independent strength samples.
- [ ] Reach at least 10,000 diversified headless games, including expanded openings, endgames, and
  Drawless-rule edge positions. The final 5,000 consecutive games must have zero crashes, illegal
  moves, corrupt reports, or incorrect adjudications, followed by one 24-hour soak of the frozen
  release-candidate artifacts.
- [ ] Run the new build and haptic behavior on the physical Pixel and tablet. Emulator evidence is
  useful but cannot verify the feel or strength of real vibration hardware.
- [ ] Owner confirms the launch price. Current evidence supports retaining **$3.99** for launch,
  then testing **$4.99** only after a stable measurement window; do not change the Play price as
  part of this engineering work.
- [ ] After launch, collect at least 1,000 qualified listing visitors or 30 purchases before making
  a price decision. Compare revenue per listing visitor, conversion, refunds, ratings, traffic
  source, and country while holding listing assets constant.

The puzzle miner and independent MultiPV verifier are engineering-ready, but a puzzle UI remains a
post-launch exploration rather than a launch gate. Bot-v-bot games provide candidates, correctness,
stability, and ending-pattern evidence; only independently verified records may enter editorial
review, and they do not substitute for human judgment about clarity, difficulty, or fun.

## Pass condition

The next release is blocked if the application is localized but its Play track still targets only
the United States, or if Play targets international markets but the optimized candidate and its
localized upgrade path have not passed the non-Console gates above.
