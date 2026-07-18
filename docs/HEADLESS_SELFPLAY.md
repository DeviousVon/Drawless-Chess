# Headless Drawless self-play

The headless harness runs the production Kotlin rules and notation code against an independently
built copy of the pinned, patched Fairy-Stockfish engine. It is intended for rule regression,
engine stability, ending-pattern, and strength-ladder diagnostics. It is not evidence that a bot
level is fun or correctly calibrated on a physical phone.

## Prepared Windows environment

The supported host for this machine is the WSL2 distribution `Ubuntu-24.04`. It has GCC/G++ 13,
Make, CMake, Ninja, Git, JDK 17, Node.js/npm, Python, jq, and the other build utilities used by the
scripts. The existing `Ubuntu` and `docker-desktop` distributions are not modified or used by the
wrapper.

Run commands from PowerShell 7 at the repository root:

```powershell
# Rebuild both artifacts, verify their identity, and run every harness/rule canary.
& .\scripts\headless-selfplay.ps1 --validate-only --jobs 12

# Run the short, node-limited plumbing smoke.
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/smoke.properties `
  --output build/headless/runs/smoke.jsonl

# Run the two-leg production-budget diagnostic.
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/diagnostic.properties `
  --output build/headless/runs/diagnostic.jsonl

# Exercise every matrix job quickly; capped records are plumbing evidence only.
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/same-level-canary.properties
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/adjacent-canary.properties

# Later, run the app-equivalent 350 ms diagnostic matrices.
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/same-level-diagnostic.properties
& .\scripts\headless-selfplay.ps1 `
  --config tools/selfplay/config/adjacent-diagnostic.properties
```

Use `--no-build` only after an unchanged build has passed validation. The runner still checks the
artifact hashes and source manifests before starting a game.

## Controlled duration soak

The Windows supervisor runs complete production-budget same-level and adjacent-level rounds until
the reports contain at least the requested amount of game-bearing runner time. Validation and
zero-work resume checks do not count toward that minimum. It keeps Windows awake while running,
captures every child process's output, applies a two-hour per-process watchdog, and stops only at a
matrix boundary.

```powershell
# Three-hour daytime soak.
& .\scripts\headless-selfplay-soak.ps1 -MinimumHours 3 -RunId daytime-YYYYMMDD

# Resume the same run directory after an interrupted supervisor.
& .\scripts\headless-selfplay-soak.ps1 -MinimumHours 3 -RunId daytime-YYYYMMDD -Resume
```

Each run writes atomic progress to `build/headless/runs/soak-<RunId>/state.json`, an append-only
child-process ledger, per-attempt stdout/stderr logs, and one JSONL report per matrix. Creating an
empty `stop.request` in that directory asks the supervisor to stop cleanly before the next matrix.
The supervisor rejects non-production search/rule/fixture profiles unless the explicit
`-AllowNonProductionConfig` rehearsal switch is used.

Repeated fixed matrices are useful stability and reproducibility tests, but they are deterministic
replicates rather than independent strength samples. Never multiply their count into Elo confidence
intervals. Censored games remain inconclusive and are excluded from every strength statistic.

## Puzzle candidate mining and independent verification

Self-play moves are candidate signals, not puzzle answer keys. The playing campaigns use a 350 ms
budget, strength-limited competitors, and `MultiPV=1`. The puzzle pipeline therefore has two hard
stages:

1. The miner strictly reads complete self-play records, preserves the initial FEN and every move
   before the candidate so repetition history remains valid, and emits deterministic candidate IDs.
   It mines exact terminal-rule moves and the first positive forced-mate signal from each game.
2. The verifier replays the complete history through the production core, rejects already-terminal
   or malformed roots, enumerates immediate winning alternatives, and runs two independent
   full-strength node searches with MultiPV. A record is `verified` only when both searches agree on
   the source move, the solution is unique, and the principal variation replays to a win under the
   same Drawless rules. Ambiguous or bot-disagreed positions are retained as `rejected`; engine or
   data failures are `error` and fail the command.

Run from PowerShell 7 at the repository root:

```powershell
# Mine one or more completed soak/report inputs. The output must not already exist unless
# --replace is explicitly supplied.
& .\scripts\headless-puzzles.ps1 mine `
  --input build/headless/runs/soak-RUN-ID `
  --output build/headless/puzzles/RUN-ID-candidates.jsonl `
  --no-build

# Verify candidates. Verification is append-only and resumes completed candidate IDs by default.
# Omit --max-candidates for the complete corpus.
& .\scripts\headless-puzzles.ps1 verify `
  --input build/headless/puzzles/RUN-ID-candidates.jsonl `
  --output build/headless/puzzles/RUN-ID-verified.jsonl `
  --primary-nodes 250000 `
  --confirm-nodes 1000000 `
  --multi-pv 5 `
  --parallel 4 `
  --max-candidates 20 `
  --no-build
```

The wrapper restricts generated output to `build/headless/puzzles`, verifies artifact hashes, and
copies the engine, runner, and variant definition into one content-addressed read-only snapshot
before verification. A concurrent rebuild cannot change an active verification campaign. Use
`--replace` only to intentionally discard an existing candidate or verification output.

Candidate counts are not publishable-puzzle counts. Only `verified` records are eligible for later
editorial review or app import. Difficulty labels require solver behavior and are deliberately not
invented from engine depth, mate distance, or bot level.

## Evidence and safety behavior

- The engine builder starts from the locked upstream revision, independently reapplies the ordered
  patch series, verifies the exact patched tree, and builds Linux x86-64 with prefetch disabled to
  match the Android engine.
- The runner jar contains the repository's platform-neutral production core, not a second chess
  implementation. Fixture tests cover checkmate, both stalemate rules, forced and avoidable
  repetition, dead-position policies, Final Capture, and all configured 50-move policies.
- Each report is UTF-8 JSON Lines. It records engine/runtime/config fingerprints, complete UCI and
  SAN histories, FEN timelines, search evidence, and app-derived adjudication facts.
- A report is locked while a run owns it. A restart repairs an interrupted final line and skips only
  complete game records with the same fingerprint. A changed engine, runtime, fixture, or material
  config is rejected instead of being mixed into old evidence.
- The wrapper copies the verified engine, runner, variant definition, and active campaign tables
  into one content-addressed, read-only snapshot. Rebuilding the normal artifacts while a campaign
  runs cannot change later games in that campaign.
- `maxPlies` is a resource boundary, never a draw adjudication. A capped game is recorded as
  censored/inconclusive and must not enter win-rate claims.
- Report paths are restricted to `build/headless/runs`; the wrapper cannot overwrite the engine,
  runner, source manifests, or repository files.
- Puzzle output is independently restricted to `build/headless/puzzles`. Verification records the
  candidate-file, engine, variants, and runner hashes and safely resumes only an identical campaign.

## Gates before a larger campaign

Do not interpret or expand a campaign unless all of these pass in order:

1. Native patch behavior and JNI host lifecycle checks.
2. Headless artifact identity and all exact rule fixtures.
3. A two-game smoke, followed by a same-output resume check that schedules zero duplicate games.
4. A small parallel canary that proves job IDs/openings/color legs are distinct and report records
   are not interleaved or lost.
5. Same-level and adjacent-level diagnostic matrices with capped games kept separate from results.
6. Physical Pixel/tablet calibration before making product claims about displayed bot strengths.

The harness makes no Play Console changes, signs no Android bundle, and does not change the app's
version or release track.

## Verification recorded July 16, 2026

- Production core: 225 Kotlin tests passed.
- Native integration: pinned-source structure plus JNI lifecycle, identity, rules, search, and
  restart gates passed under Ubuntu 24.04.
- Headless harness: 22 exact rule fixtures passed, including four bounded searches against the
  real patched engine; all four campaign configs passed derivation tests.
- Same-level canary: 56/56 unique jobs completed with zero failures and consistent UCI/SAN/FEN
  histories. Adjacent canary: 96/96 unique jobs, 48/48 complete color-swapped pairs, zero failures,
  and consistent histories. All were deliberately capped and therefore remain inconclusive.
- Resume gate: reopening both canary reports scheduled zero duplicate games. Strict report tests
  also cover malformed JSON, duplicate jobs, wrong fingerprints, unrelated files, and torn UTF-8
  final records.
- Production-budget paired diagnostic: both 350 ms games ended decisively without a cap or
  failure—one by Drawless stalemate and one by checkmate. The 2500-Elo competitor beat the
  2000-Elo competitor once with each color. Two games are plumbing evidence, not a strength
  estimate.
- Completed production-profile soaks: 3,952 games across 26 full same-level/adjacent rounds,
  10:57:33 of game-bearing time, zero failures, zero malformed color pairs, and empty stderr.
- Puzzle miner real-corpus gate: the 2,736-game overnight run produced 5,227 unique candidates
  (2,674 terminal moves and 2,553 positive forced-mate signals). A four-candidate independent
  MultiPV canary completed with zero pipeline errors and correctly rejected all four ambiguous or
  bot-disagreed positions.

The final hardened-run evidence is in `build/headless/runs/same-level-canary-final.jsonl`,
`build/headless/runs/adjacent-canary-final.jsonl`, and
`build/headless/runs/diagnostic-final.jsonl`.
