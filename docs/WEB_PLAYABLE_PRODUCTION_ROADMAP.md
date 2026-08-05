# Drawless Chess web playable production roadmap

**Status:** execution in progress; first playable candidate deployed to staging  
**Plan date:** July 31, 2026  
**Owner:** Bob  
**Target URL:** `https://drawlesschess.com/play/`  
**Latest planned production attempt:** August 12, 2026; deploy earlier when every gate is green  
**Latest acceptable verified-live date:** August 13, 2026  

## Execution checkpoint

The latest verified playable candidate is on the private acceptance staging site for Bob's review:

- Release ID: `drawless-webplay-20260801-162ffde8`
- Package build ID: `162ffde816bc7e88a4bd69b8f4c102175ac39de22f1fb318c7cdc1e594fbfcbe`
- Deployment archive SHA-256: `6a9d9f983352b76a323e8da56256c12b75528e0b759a4f676d51aaad509cce80`
- Opponent: accurately labeled `Web Casual`; no Android engine-strength or parity claim
- Visual identity: Android Imperial Marble palette and veining, original Drawless piece
  silhouettes and palette, and matching gold interaction accents; no platform chess glyphs
- Completed gates: isolated route, exact-law controller, legal move/promotion adapter, worker
  cancellation, White/Black/Random side choice, resignation/restart, responsive layout, package
  boundary, deterministic checksums, lint, unit tests, rendered-package and visual-regression
  tests, desktop/mobile real-browser move smoke with a clean browser log, staging MIME mapping,
  atomic staging switch, and retained rollback releases
- Current gate: Bob's staging acceptance and broader browser/device coverage

The in-app verification browser does not trust the staging host's private test certificate, so it
did not bypass that certificate interstitial. Instead, the exact release identity passed the full
browser journey locally, and the staging host independently verified the same payload's SHA-256
manifest, every route, the worker/module assets, and `application/wasm` response type. Bob's normal
staging browser remains the required end-to-end acceptance surface.

## Outcome

Ship a small, trustworthy browser version of Drawless Chess so a visitor can understand the
game by playing immediately. It is a casual web preview, not a port of the full Android app.

The visitor must be able to open `/play/`, choose a side, complete a legal game against the clearly
labeled Web Casual opponent, and receive the correct Drawless result. The game runs in the
browser. There are no accounts, payments, ads, analytics, cloud saves, or server-side game
sessions.

The launch is independent of the Android/Google Play release. Android signing, Play publication,
device-install gates, and the long-running Android self-play campaign neither authorize nor block
this web deployment. The web artifact has its own source identity, tests, deployment evidence, and
rollback point.

## Deadline strategy

Every date in this roadmap is the **latest** that gate may close, not a reason to wait. Production
deploys as soon as all hard gates pass and Bob accepts the exact staged artifact. August 13 is the
verified-live deadline, not the first deployment attempt. If work consumes the full allowance, the
normal production deployment is August 12 so one full day remains for rollback, correction, and
redeployment.

As soon as an end-to-end game is playable and passes the minimum staging smoke, deploy it to the
staging site for Bob. Thereafter, staging must track the newest playable, locally verified build;
do not leave Bob testing a superseded build while a newer playable candidate exists. Each staging
update records its source commit/worktree identity, artifact hash, deployment time, and known gaps.
An incomplete build that cannot start and complete the current supported journey stays local and
does not replace the latest playable staging build.

The schedule has nine normal weekdays after this plan, plus two weekends reserved for automated
testing and contingency. Scope is frozen after August 3. A new feature may enter after that date
only if it replaces work of equal or greater cost and does not weaken a hard gate.

The implementation uses one engine adapter with two production-capable implementations:

1. **Preferred:** the pinned, patched Drawless Fairy-Stockfish source compiled as a browser
   WebAssembly worker. It provides the closest computer-play behavior to Android.
2. **Deadline-safe fallback:** a lightweight local casual opponent that selects only legal moves
   and uses the exact web game-law adapter for terminal outcomes. It may be deliberately weak, but
   it must never pretend to have the Android engine's strength or identity.

The existing unpatched npm/WASM proof of concept may be used to build the UI and worker protocol.
It does not satisfy the preferred production-engine identity gate. It may not be described as the
production Android engine, and its evaluation cannot be the authority for a displayed game result.

If the patched WebAssembly build has not passed its browser parity gate by **August 4 at 17:00
Central**, development continues with the exact-law casual fallback and removes the engine-build
work from the critical path. This preserves the deadline and honest user-visible behavior.

## Release scope

### Required for August 13

- A dedicated `/play/` route linked from the public home page.
- One-player local game against a computer opponent; no network is required after assets load.
- Default Drawless rules. Escape and custom policy editing are excluded from this release.
- Play as White, Black, or a randomly assigned side.
- Three named difficulties when the patched engine is used: Learner, Casual, and Club, preserving
  the existing app mappings. The fallback opponent has one clearly named **Web Casual** level and
  makes no Elo claim.
- Tap/click and keyboard-accessible move entry, selected-square state, and legal destinations.
- Correct castling, en passant, check, promotion choice, resignation, and new-game behavior.
- Correct user-visible adjudication for checkmate, Drawless stalemate, repetition, bare king,
  known-dead positions, and the configured 50-move policy, including precedence and tie breakers.
- Clear loading, thinking, illegal-action, engine-failure, and game-over states.
- Responsive portrait and landscape layouts on the designated Pixel phone and R6 tablet, plus
  desktop browser layouts.
- A short, reachable explanation of how Drawless differs from orthodox chess.
- Local assets only; no gameplay, position, preference, or device data sent by application code.
- Exact licensing notices and corresponding-source access for the shipped browser binary.
- Immutable production release, live HTTPS checks, and a tested one-command-or-one-symlink
  rollback to the preceding website release.

### Allowed only if all required gates are already green

- Move and capture sounds using already-approved local assets.
- A small move list.
- Remembering side/difficulty locally on the device.
- Installable/PWA metadata.
- One additional stronger difficulty.

These items are cut first when schedule or defect pressure appears. None may delay Gate 4 or later.

### Explicitly out of scope

- Accounts, matchmaking, online multiplayer, spectators, chat, or leaderboards.
- Rated games, adaptive rating, statistics, achievements, or cross-device persistence.
- Game Review, hints, analysis scores, MultiPV, accuracy, or post-game annotations.
- Clocks, pause/forfeit accounting, saved-game recovery, and game history.
- The full Android theme, opponent, animation, audio, haptic, localization, and preference surface.
- Monetization, ads, billing, telemetry, cookies, or third-party analytics.
- Claiming feature parity, engine parity, or shared saves with the Android app.

## Architecture baseline

### Static-site boundary

The existing marketing site remains a static OpenBSD `httpd` deployment. `/`, `/privacy/`,
`/support/`, and `/open-source/` remain free of browser framework JavaScript. `/play/` is the only
interactive application route and lazy-loads its game code after navigation.

The current global tests reject every script tag. Change that check narrowly: scripts are allowed
only for `/play/` and its hashed local assets. Do not weaken the script prohibition on marketing,
privacy, support, open-source, or 404 pages.

### Browser modules

| Module | Responsibility | Hard boundary |
| --- | --- | --- |
| Play route shell | Metadata, rules summary, loading and failure UI | Does not own game law or engine state |
| Board renderer | Responsive board, pieces, focus, pointer/keyboard input | Emits intents; does not mutate a position |
| Web game controller | Immutable game state, move lifecycle, repetition history, result | Sole authority for committed moves and displayed outcomes |
| Chess adapter | FEN, legal moves, check state, move application, position facts | Hidden behind project-owned interface and golden fixtures |
| Rules adapter | Existing Drawless policy and terminal precedence | Engine evaluation cannot override it |
| Opponent adapter | `newGame`, `requestMove`, `cancel`, `dispose` | Every reply is correlated and revalidated before commit |
| Engine worker | UCI/WASM initialization and bounded search | Never blocks the UI thread |

Use the existing `ffish-es6` proof-of-concept dependency behind the chess adapter for the first
release unless the August 3 spike finds a contract failure. Do not let package-specific objects
escape the adapter. Reuse `src/main/js/rules.js` through a website-owned typed facade, and add the
position-fact construction it requires. Golden fixtures must compare the browser result to the
Kotlin rules/chess expectations.

### State and concurrency invariants

- Exactly one controller owns a game.
- A committed position has a monotonically increasing revision.
- Opponent requests contain game ID, position revision, and request ID.
- Restart, resignation, side change, navigation, or worker failure cancels the outstanding request.
- A returned move is ignored unless all three identifiers still match and the move is legal in the
  current position.
- Promotion is an explicit user choice; it is never silently forced to queen before confirmation.
- The UI never declares a result from UCI score text. It displays only the rules adapter's outcome.
- Worker exceptions become a recoverable message and a restart action, not a frozen board.
- The opponent worker is terminated when leaving `/play/` and performs no sustained idle work.

### WebAssembly profile

Prefer a single-threaded browser build for this casual release. One bounded worker is sufficient,
avoids a hard dependency on `SharedArrayBuffer`, reduces cross-origin-isolation complexity, and is
friendlier to mobile memory and battery.

If the patched build requires WebAssembly threads, Gate 1 must additionally prove HTTPS,
`Cross-Origin-Opener-Policy`, `Cross-Origin-Embedder-Policy`, worker loading, and all local asset
responses on every required browser. If route-scoped isolation cannot be configured safely on the
current host, use a dedicated same-site play subdomain or the exact-law fallback; do not apply
untested global headers to the marketing site.

## Dated delivery plan

| Date | Milestone | Required exit evidence |
| --- | --- | --- |
| Fri Jul 31 | Roadmap and scope baseline | This document reviewed; URL, scope, launch date, and fallback recorded |
| Mon Aug 3 | Gate 0: architecture spike | `/play/` shell builds; worker round-trip works; chess adapter can start, move, promote, and export FEN; patched WASM build feasibility known |
| Tue Aug 4 | Gate 1: engine/fallback decision | Preferred engine passes browser identity/parity tests, or fallback is selected and its strength claims are removed |
| Wed Aug 5 | Gate 2: end-to-end playable vertical slice and first staging deployment | Visitor can finish a complete human-vs-computer game; stale replies and restart work; core rule fixtures pass; exact playable build is live on staging for Bob |
| Thu Aug 6 | UX and responsive completion | Mobile/desktop board, side/difficulty controls, rules help, loading/failure/result states, keyboard path complete |
| Fri Aug 7 | Gate 3: feature complete alpha | Required scope complete; automated suite green; no Priority 0/1 defects; Bob plays representative games |
| Sat-Sun Aug 8-9 | Buffer and unattended checks | Repeated games, fixture matrix, cached/uncached loads, worker restart, and production-package verification run; no new features |
| Mon Aug 10 | Gate 4: release candidate 1 | Performance, security, privacy, licensing, static package, MIME/cache/header, and source-archive gates pass |
| Tue Aug 11 | Gate 5: staging acceptance | RC deployed to staging/preview; browser/device matrix complete; Bob accepts scope and experience; production/rollback commands frozen |
| Wed Aug 12 | Gate 6: normal production launch | Immutable release deployed; signed-out live play-through and rollback readiness verified; monitoring window completed |
| Thu Aug 13 | Contingency and final Gate 7 | Any rollback fix redeployed and reverified; production URL is playable no later than end of day Central |

## Gate 0: architecture spike — August 3

### Work

1. Add an isolated client entry for `/play/` without changing the rendering mode of other routes.
2. Prove Web Worker message exchange, cancellation, disposal, and a local asset load.
3. Wrap `ffish-es6` behind a typed adapter and prove start position, one normal move, castling,
   en passant, underpromotion, legal-move enumeration, check, and FEN round trip.
4. Attempt a reproducible Emscripten build from the pinned patched Fairy-Stockfish tree. Record
   compiler identity, commands, source/patched-tree identities, output SHA-256, and whether the
   result is single-threaded.
5. Run the exact forced-repetition fixtures in a real browser worker, not only Node.
6. Verify that OpenBSD can serve `.wasm` as `application/wasm` and can serve a deterministic gzip
   or Brotli sibling without corrupting streaming instantiation.

### Exit condition

The web architecture has no unknown dependency that can consume more than one remaining day. The
patched engine is classified as **green**, **fixable by August 4**, or **removed from this release**.
No UI polish begins while move application, cancellation, or production-engine feasibility is
unknown.

## Gate 1: opponent decision — August 4

### Preferred patched-engine evidence

- Exact upstream revision, patched tree, patch-series hash, variant hash, compiler version, build
  flags, and WASM SHA-256 are emitted into a machine-readable browser-engine manifest.
- `uci`, `isready`, variant activation, rule-policy options, bounded `go`, `stop`, follow-up search,
  worker restart, and malformed response handling pass.
- Every `engine/parity-fixtures-v1.json` case passes in the browser, including both forced
  repetition colors and user-visible terminal expectations.
- Patch-v2 bare-king, known-dead, 50-move, precedence, last-capture, and underpromotion fixtures
  pass at root and the deeper search cases required by the existing patch contract.
- The engine is configured for one search worker, bounded hash/memory, and a hard move-time cap.
- No network request occurs after local engine assets are loaded.

### Fallback evidence

If the preferred engine misses the deadline:

- The fallback selects from the chess adapter's complete legal move set only.
- It scores immediate terminal wins/losses using the same rules adapter as the controller, then a
  shallow material/mobility heuristic. Randomization is seeded or bounded so tests are repeatable.
- It completes a move within the response budget on Pixel and R6.
- UI and metadata call it **Web Casual** or **casual web opponent**. They do not mention Elo,
  Fairy-Stockfish strength, Vesper, or Android engine parity.
- The unpatched WASM may remain a non-production regression tool, but it is not silently relabeled
  as the patched engine.

### Exit condition

Exactly one production opponent implementation is selected and frozen. From this point onward,
opponent changes require rerunning Gates 1 through 7.

## Gate 2: playable vertical slice — August 5

### Work

- Build immutable `WebGameState` and reducer/controller APIs.
- Support side selection, new game, legal move selection, promotion, opponent turn, resignation,
  and game over.
- Build position facts for no-legal-move/check, repetition occurrence and alternatives, bare king,
  known-dead state, halfmove clock, avoiding alternatives, material, and last capturer.
- Make the configured Drawless contract explicit and versioned in every started game.
- Add stale-response, cancel/restart, worker-crash, invalid-bestmove, and illegal-bestmove tests.
- Convert relevant Kotlin/core and engine fixtures into one language-neutral browser fixture lane.

### Hard acceptance fixtures

- Normal opening moves, captures, check evasion, castling both sides, en passant, and all four
  promotion choices.
- Checkmate beats other simultaneous terminal facts.
- In Drawless stalemate, the trapped player loses.
- Avoidable third repetition defeats the completing player; a forced third repetition applies the
  forced exception for both colors.
- Bare-king and known-dead outcomes match the configured contract.
- The 100-halfmove policy and material/last-capturer/forced tie breakers match the contract.
- Restart while the opponent is thinking cannot commit an old move into the new game.
- A complete game can be played without console error, main-thread freeze, or server request.

### Exit condition

The browser controller, not the presentation, can complete deterministic games and passes every
hard fixture. A visually plain board is acceptable at this gate; incorrect or incomplete game law
is not. Deploy this first playable verified build to staging immediately and give Bob the staging
URL plus build identity. Each later playable verified build replaces it promptly; staging may move
forward before later gates close, but it must never silently move backward or point at an
unidentified build.

## Gate 3: feature-complete alpha — August 7

### User experience

- Board is the primary content and remains fully visible/reachable in common phone, tablet, and
  desktop layouts.
- White is shown from White's perspective and Black from Black's perspective unless the visitor
  explicitly flips the board.
- Selected square, legal destination, last move, check, disabled input, and opponent-thinking
  states are visually distinct without relying on color alone.
- Controls include side, permitted difficulty, new game, resign, board flip, and rules help.
- Promotion chooser is modal, focus-contained, labeled, and usable by pointer and keyboard.
- Game result names the winner and Drawless reason in plain language and offers another game.
- Initial engine download has visible progress/ready/failure states. The board shell appears before
  the engine is ready.
- A visitor can understand the core difference from ordinary chess before or during the first
  game without reading a long manual.

### Accessibility

- Every square has a stable accessible name containing coordinate and occupant.
- Keyboard navigation can select a piece, inspect legal destinations, commit a move, choose
  promotion, open rules, resign, and start again.
- Focus does not disappear after the opponent moves or a dialog closes.
- Status changes use a polite live region and do not announce every engine detail.
- Board and controls remain usable at 200% browser zoom and large Android font/display settings.
- Contrast, focus rings, touch targets, reduced-motion behavior, and screen-reader order are
  checked explicitly.

### Bob acceptance session

Bob plays at least:

1. one game as White;
2. one game as Black;
3. one mobile game or substantial partial game;
4. one promotion fixture; and
5. one staged Drawless terminal fixture.

Record date, URL/build identity, device/browser, result, and any defect. Informal approval without
the build identity does not close the gate.

## Gate 4: release candidate — August 10

### Performance and resource budgets

| Resource | Target | Hard release ceiling |
| --- | ---: | ---: |
| Marketing-route behavior | No new browser runtime | No script on existing static routes |
| `/play/` shell JS+CSS transfer | <= 250 KiB compressed | 400 KiB compressed |
| Async chess/opponent assets | <= 1.5 MiB compressed | 3 MiB compressed |
| Total cold `/play/` transfer | <= 2 MiB compressed | 3.5 MiB compressed |
| WebAssembly heap(s) | <= 160 MiB | 192 MiB |
| Active search concurrency | 1 worker / 1 search | 1 worker / 1 search |
| Board usable, engine deferred | <= 2.5 seconds | 5 seconds on Pixel/R6 over production HTTPS |
| Cold opponent ready | <= 6 seconds | 10 seconds on Pixel/R6 over production HTTPS |
| Default opponent response after warmup | p95 <= 2.5 seconds | Every move <= 5 seconds |
| Sustained idle engine CPU | Approximately zero | No continued search after move/cancel/navigation |

Measure transfer from an uncached production-like HTTPS server. Record raw and compressed sizes,
timings, browser/device, and artifact hashes. Do not substitute a desktop localhost measurement for
Pixel/R6 evidence.

### Reliability

- Run at least 100 automated games or fixture-driven partial games in the browser controller with
  zero illegal moves, hangs, stale commits, uncaught worker errors, or contradictory results.
- Repeat create/play/restart/dispose at least 50 times and confirm workers and memory do not grow
  without bound.
- Verify background/foreground, page refresh, back/forward navigation, orientation change, and
  cache-warm/cold behavior.
- A failed WASM fetch, unsupported browser feature, worker crash, and engine timeout each produce a
  useful recoverable state.

### Security and privacy

- All executable assets are same-origin, content-hashed, and served over HTTPS.
- No CDN, remote font, remote script, analytics endpoint, cookie, account identifier, or gameplay
  submission is present.
- Add a restrictive Content Security Policy compatible with the worker/WASM implementation;
  document any required `wasm-unsafe-eval` allowance rather than widening to unrestricted eval.
- Verify `.wasm` content type, `nosniff`, referrer policy, frame policy, and cross-origin headers.
- Validate all worker messages and UCI responses before using them.
- Update privacy/support copy only as needed to say that web gameplay remains local. Distinguish
  ordinary web-server access logs from application gameplay collection.

### Licensing and reproducibility

- The browser-engine manifest identifies exact source, patches, toolchain, flags, and binary hash.
- The public open-source page links to complete corresponding source for the exact shipped WASM,
  including build scripts and the Drawless patch series.
- Publish the source archive and SHA-256 on GitHub or the website before or with the binary. An
  expired GitHub login does not justify distributing a source-less GPL binary; use the website as
  the alternate source host if GitHub cannot be restored safely in time.
- Include Drawless, Fairy-Stockfish, `ffish-es6`, and other shipped dependency notices/licenses in
  the release and source archive.
- Generate a small browser artifact inventory/SBOM and verify it against the immutable package.
- No Android signing key, upload certificate, password, or signing configuration is involved.

### Packaging

- Add `/play/` to expected-route verification.
- Permit scripts only in the play route and its dependency graph.
- Generate and verify deterministic compressed siblings for `.wasm` and browser JavaScript.
- Keep the total static release under the existing 8 MiB ceiling unless a reviewed, recorded budget
  change is required by the exact patched artifact.
- Verify local links, source map exclusion, checksums, MIME types, headers, hashed-cache policy,
  HTML no-cache policy, and a custom 404.

## Gate 5: staging acceptance — August 11

Staging has been receiving the latest playable verified build since Gate 2. For Gate 5, promote the
exact release candidate to that production-like HTTPS origin and run the complete acceptance
matrix. Rebuilding after final staging acceptance invalidates that acceptance evidence, although
earlier rolling-staging feedback remains useful.

### Required browser/device matrix

| Environment | Required journey |
| --- | --- |
| Current Chrome on designated Pixel | Cold load, play both colors, restart while thinking, rotate, one result |
| Current Chrome on designated R6 tablet | Cold load, portrait/landscape, promotion, one result |
| Current Chrome on desktop | Keyboard-only game path, worker failure recovery, cache-warm replay |
| Current Edge on desktop | Full representative game and refresh/navigation |
| Current Firefox on desktop | Full representative game and asset/header verification |
| Current Safari on available Apple hardware | Smoke game, promotion, worker/WASM start; if unavailable, record the gap rather than claiming coverage |

For every row record browser version, OS/device, release hash, time, pass/fail, and defect links.
Pixel success does not imply R6 success, and Chromium success does not imply Firefox/Safari success.

### Defect policy

- **Priority 0:** security/privacy breach, wrong result, illegal move, production outage, or source
  compliance failure. Blocks launch.
- **Priority 1:** game cannot complete, engine frequently fails, inaccessible required control,
  target-device incompatibility, or data/resource runaway. Blocks launch.
- **Priority 2:** confusing but recoverable behavior or material visual defect. Fix before launch
  unless Bob explicitly accepts the exact documented defect.
- **Priority 3:** polish issue with an obvious workaround. May be deferred.

Gate 5 closes only with zero open Priority 0/1 defects, a reviewed Priority 2 list, and Bob's
acceptance of the exact staged artifact.

## Gate 6: production deployment — August 12

### Pre-deploy

1. Freeze source commit, website package, browser artifact manifest, source archive, SBOM, and
   SHA-256 inventory.
2. Re-run build, lint, unit, fixture, browser integration, packaging, and OpenBSD verification from
   the frozen tree.
3. Record the existing production `current` target and verify it is healthy as the rollback release.
4. Upload into a new immutable `/var/www/htdocs/sites/drawlesschess/releases/<release-id>`
   directory. Never modify the old release in place.
5. Verify the new directory through its staging/preview path before switching production.

### Switch and live verification

1. Replace `current` with `ln -sfh`; do not use a plain `mv` that follows the existing symlink.
2. Reload OpenBSD `httpd` only if configuration or headers changed.
3. From a signed-out, cache-cold browser, verify `/`, `/play/`, `/privacy/`, `/support/`,
   `/open-source/`, source archive, and 404 responses.
4. Verify HTTP status, TLS, content type, compression, cache policy, security headers, and the
   expected release/build identity.
5. Start a production game, make a legal human move, receive a legal opponent move, restart while
   thinking, and complete or load one deterministic terminal fixture.
6. Repeat the production smoke on Pixel and R6. A desktop-only smoke does not close launch.
7. Observe logs and client behavior for at least 60 minutes. Confirm no elevated 404/5xx rate,
   missing assets, repeated worker initialization failure, or unexpected third-party requests.

### Rollback

Rollback immediately for any Priority 0 defect, widespread inability to start/play, broken
marketing pages, missing source/compliance material, or resource behavior that risks the host or
client devices.

Switch `current` back to the recorded prior release with `ln -sfh`, reload only if needed, and
repeat signed-out public verification. Retain the failed immutable release and evidence for
diagnosis; do not overwrite it. A rollback restores service but does not satisfy the August 13
playable-production goal.

## Gate 7: final verified-live deadline — August 13

Use this day only for a bounded correction arising from production evidence. Do not add features,
change opponent strength, or refactor working components.

The deadline is met only when all of the following are true:

- `https://drawlesschess.com/play/` returns the intended immutable release over HTTPS.
- A cache-cold visitor can start and complete a legal game against the selected local opponent.
- User-visible Drawless outcome fixtures pass against the production artifact.
- Pixel, R6, and required desktop browser smoke results are recorded against that artifact.
- Marketing routes remain healthy and preserve their static/no-script contract.
- Privacy, security headers, local-only behavior, licenses, corresponding source, SBOM/inventory,
  and hashes are live and verified.
- The prior production release remains available and the rollback command is recorded.
- There are zero open Priority 0/1 defects and no unaccepted Priority 2 defect.
- The live URL, release ID, source commit, artifact hashes, test report, deployment time, and
  verifier identity are recorded together.

A successful build, a staging preview, an uploaded directory, a running worker, or a production
HTTP 200 alone is not completion.

## Work allocation and checkpoints

This plan requires approximately **7-9 focused engineering days**, **1-2 verification/review
days**, and short Bob acceptance sessions. One focused implementer can meet the deadline if scope
remains frozen. A second person is most valuable as an independent verifier on August 7, 10, and
11, not as a concurrent editor of the same website files.

Bob's expected decision/acceptance time:

- August 4: 15 minutes only if the fallback opponent decision is needed.
- August 7: 30-45 minutes for feature-complete alpha play.
- August 11: 30-45 minutes for exact staging acceptance.
- August 12: availability for go/no-go and live smoke, not implementation.

At the start and end of every day, record:

- current commit/worktree identity and concurrent edits;
- gate status and evidence location;
- new Priority 0-3 defects;
- remaining critical-path work;
- whether August 12 launch remains forecast green, yellow, or red; and
- the next irreversible or external action requiring owner awareness.

The shared worktree already contained unrelated website edits when this roadmap was written.
Before implementation, inspect ownership and current diffs, isolate work where practical, and do
not overwrite or absorb unrelated changes. Do not use a broad `git add -A` for this work.

## Risk register

| Risk | Earliest signal | Mitigation | Deadline response |
| --- | --- | --- | --- |
| Patched browser build fails or requires unavailable toolchain | August 3 spike | Time-box build; keep adapter stable; retain exact build logs | Select exact-law fallback by August 4 17:00 |
| Unpatched engine differs in rare search rules | Existing proof-of-concept boundary | Never use engine score as result; run exact controller fixtures | Do not claim Android engine parity; use fallback if patched identity fails |
| WebAssembly threads fail on a browser/host | Worker/header spike | Prefer single-thread build; test headers early | Use single-thread or fallback; do not weaken global headers blindly |
| Mobile memory or battery is excessive | Pixel/R6 performance measurements | One worker, bounded heap/hash/time, terminate on navigation | Lower search budget or choose fallback before Gate 4 |
| Static-site no-script verifier blocks game route | First route build | Make route-specific policy and retain marketing-route ban | Blocks Gate 0 until verifier design is explicit |
| Wrong game fact construction | Golden fixture mismatch | Cross-language fixtures and controller authority | Blocks Gate 2; cut polish, never waive correctness |
| GPL corresponding source cannot be published through GitHub | Auth check by August 3 | Prepare website-hosted source archive and hash | Website-host source before/with binary |
| Concurrent website work conflicts | Daily `git status`/diff | Coordinate ownership; isolate branch/worktree; narrow commits | Stop overlapping edit and rebase deliberately; do not overwrite |
| Safari cannot run selected engine profile | Gate 1/5 browser check | Prefer single-thread WASM and feature detection | Serve clear unsupported state or use fallback only if Bob accepts coverage |
| Production cache serves mismatched HTML/assets | Staging/live cache test | Hashed assets, short/no-cache HTML, release identity check | Roll back and correct cache policy |
| August 12 deploy uncovers a blocker | Production smoke | Launch one day early; keep immutable prior release | Roll back, fix only blocker, redeploy August 13 |

## Definition of done

- [ ] Required scope is complete; stretch scope did not displace a gate.
- [ ] Production opponent identity is accurate and frozen.
- [ ] Exact user-visible Drawless results and legal-move fixtures pass.
- [ ] Stale response, cancellation, restart, failure, and disposal behavior pass.
- [ ] Responsive and accessibility acceptance passes.
- [ ] Pixel, R6, Chrome, Edge, Firefox, and available Safari evidence is recorded honestly.
- [ ] Transfer, memory, response-time, concurrency, and idle-CPU budgets pass.
- [ ] Privacy and security checks pass with no unexpected external request.
- [ ] GPL notices, corresponding source, manifest, SBOM/inventory, and hashes are live.
- [ ] Deterministic static package and OpenBSD verifier pass.
- [ ] Bob accepted the exact staging release.
- [ ] Immutable production deployment and signed-out live gameplay smoke pass.
- [ ] Previous release and tested rollback remain available.
- [ ] Production evidence ties URL, release ID, commit, hashes, tests, and time together.

## After launch

For the first 72 hours, check availability and asset errors at least daily and review direct user
reports. Do not add gameplay telemetry to obtain this data. Fix Priority 0/1 defects immediately;
batch lower-priority polish into a separately tested release.

After one week, decide from actual feedback whether the web preview should gain sound, a move list,
more difficulty choices, local resume, Escape rules, or none of them. Online multiplayer, accounts,
and Game Review remain separate projects with separate architecture and resource estimates.
