package com.drawlesschess.selfplay

import com.drawlesschess.core.BareKingPolicy
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.MoveTransition
import com.drawlesschess.core.PositionFacts
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.engine.BotDifficultyCatalog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val RULE_FIXTURE_COUNT = 22

private val RULE_FIXTURE_HEADER = listOf(
    "schema_version",
    "fixture_id",
    "mode",
    "rules",
    "initial_fen",
    "prefix_moves",
    "selected_move",
    "engine_must_choose",
    "engine_must_not_choose",
    "engine_expected_mate_in",
    "expected_state",
    "expected_reason",
    "expected_winner",
    "expected_loser",
    "expected_legal_after",
    "expected_in_check",
    "expected_position_occurrences",
    "expected_repetition_avoiding",
    "expected_halfmove_after",
    "expected_fifty_avoiding",
    "expected_dead",
    "expected_capture",
    "expected_material_white",
    "expected_material_black",
)

private data class RuleFixture(
    val lineNumber: Int,
    val values: Map<String, String>,
) {
    val id: String get() = value("fixture_id")
    val mode: String get() = value("mode")

    fun value(name: String): String = values.getValue(name).also { value ->
        require(value.isNotEmpty()) { "$id line $lineNumber has an empty '$name' field" }
    }

    fun optional(name: String): String? = value(name).takeUnless { it == "-" }

    fun moves(name: String): List<UciMove> = optional(name)
        ?.split(Regex("\\s+"))
        ?.map(::UciMove)
        .orEmpty()

    fun expect(name: String, actual: Any?) {
        val expected = optional(name)
        val rendered = actual?.toString()
        check(expected == rendered) {
            "$id field '$name': expected ${expected ?: "<none>"}, got ${rendered ?: "<none>"}"
        }
    }
}

/** Test-only deterministic search limit matching the pinned engine verification depths. */
private data class DepthSearchLimit(val depth: Int) : SearchLimit {
    init {
        require(depth in 1..64)
    }

    override val uciCommand: String = "go depth $depth"
    override val label: String = "depth:$depth"
}

private data class FixtureState(
    var position: ChessPosition,
    var session: GameSession,
) {
    fun apply(move: UciMove): PositionFacts {
        val transition = ChessAdapter.transition(position, move)
        val facts = factsBeforeApply(session, transition)
        session = session.apply(transition)
        position = ChessRules.apply(position, move)
        check(session.sideToMove == position.sideToMove) {
            "Core session and chess position disagree after ${move.value}"
        }
        check(RepetitionKey.of(position) == transition.resultingPositionKey) {
            "Transition key disagrees with resulting position after ${move.value}"
        }
        if (session.outcome == null) {
            check(session.adjudicationFacts == null)
        } else {
            check(session.adjudicationFacts == facts) {
                "Terminal adjudication facts differ from the transition-derived facts"
            }
        }
        return facts
    }
}

fun main() {
    val root = findRepositoryRoot()
    val fixturePath = root.resolve("tools/selfplay/fixtures/rule-fixtures.tsv")
    val fixtures = loadRuleFixtures(fixturePath)
    val failures = mutableListOf<String>()
    var engineSearches = 0
    var puzzleChecks = 0

    fixtures.forEach { fixture ->
        try {
            if (fixture.mode.startsWith("search_")) engineSearches++
            runFixture(root, fixturePath, fixture)
        } catch (error: Throwable) {
            failures += buildString {
                append(fixture.id)
                append(": ")
                append(error::class.java.simpleName)
                append(": ")
                append(error.message ?: "no error message")
            }
        }
    }

    try {
        validateJsonEncoder()
    } catch (error: Throwable) {
        failures += "JSON encoder: ${error::class.java.simpleName}: ${error.message}"
    }
    try {
        validateCampaignConfigs(root)
    } catch (error: Throwable) {
        failures += "campaign config derivation: ${error::class.java.simpleName}: ${error.message}"
    }
    try {
        validateOpeningReplay(root, fixturePath)
    } catch (error: Throwable) {
        failures += "opening validation: ${error::class.java.simpleName}: ${error.message}"
    }
    try {
        validateJsonlReport(root, fixturePath)
    } catch (error: Throwable) {
        failures += "JSONL report hardening: ${error::class.java.simpleName}: ${error.message}"
    }
    try {
        puzzleChecks = runPuzzlePipelineTests()
    } catch (error: Throwable) {
        failures += "puzzle pipeline: ${error::class.java.simpleName}: ${error.message}"
    }

    if (failures.isNotEmpty()) {
        error("FAILED ${failures.size} headless runner checks\n${failures.joinToString("\n")}")
    }
    println(
        "PASSED ${fixtures.size} exact rule fixtures " +
            "($engineSearches bounded engine searches), four campaign derivations, opening gates, " +
            "JSON/report checks, and $puzzleChecks puzzle-pipeline checks",
    )
}

private fun runFixture(root: Path, fixturePath: Path, fixture: RuleFixture) {
    val rules = parseRules(fixture.value("rules"))
    val initialFen = fixture.value("initial_fen")
    val initialPosition = ChessPosition.fromFen(initialFen)
    val state = FixtureState(
        position = initialPosition,
        session = GameSession.newGame(
            gameId = "fixture-${fixture.id}",
            rules = rules,
            initialPositionKey = RepetitionKey.of(initialPosition),
            sideToMove = initialPosition.sideToMove,
        ),
    )

    fixture.moves("prefix_moves").forEachIndexed { index, move ->
        check(state.session.outcome == null) {
            "${fixture.id} is terminal before prefix ply ${index + 1}"
        }
        state.apply(move)
        check(state.session.outcome == null) {
            "${fixture.id} prefix became terminal at ply ${index + 1} (${move.value})"
        }
    }

    val searched = if (fixture.mode.startsWith("search_")) {
        search(root, fixturePath, fixture, rules, state)
    } else {
        null
    }
    val facts = when (fixture.mode) {
        "apply" -> state.apply(
            UciMove(fixture.optional("selected_move")
                ?: error("${fixture.id} apply fixture has no selected_move")),
        )
        "search_apply", "search_forced" -> {
            val result = checkNotNull(searched)
            fixture.optional("selected_move")?.let { selected ->
                check(result.move.value == selected) {
                    "${fixture.id} engine chose ${result.move.value}; selected_move is $selected"
                }
            }
            state.apply(result.move)
        }
        "search_avoid" -> {
            check(fixture.optional("selected_move") == null) {
                "${fixture.id} search_avoid must not apply a selected move"
            }
            null
        }
        else -> error("${fixture.id} has unsupported mode '${fixture.mode}'")
    }

    val actualState = when {
        facts == null -> "search_only"
        state.session.outcome != null -> "terminal"
        else -> "ongoing"
    }
    fixture.expect("expected_state", actualState)
    fixture.expect("expected_reason", state.session.outcome?.reason?.name)
    fixture.expect("expected_winner", state.session.outcome?.winner?.name)
    fixture.expect("expected_loser", state.session.outcome?.loser?.name)
    fixture.expect("expected_legal_after", facts?.legalMovesAfter)
    fixture.expect("expected_in_check", facts?.sideToMoveInCheck)
    fixture.expect("expected_position_occurrences", facts?.positionOccurrenceCount)
    fixture.expect(
        "expected_repetition_avoiding",
        facts?.repetitionAvoidingAlternativesBeforeMove,
    )
    fixture.expect("expected_halfmove_after", facts?.halfmoveClockAfter)
    fixture.expect("expected_fifty_avoiding", facts?.fiftyMoveAvoidingAlternativesBeforeMove)
    fixture.expect("expected_dead", facts?.deadPositionAfter)
    fixture.expect("expected_capture", facts?.moveWasCapture)
    fixture.expect("expected_material_white", facts?.materialAfter?.white)
    fixture.expect("expected_material_black", facts?.materialAfter?.black)
}

private fun search(
    root: Path,
    fixturePath: Path,
    fixture: RuleFixture,
    rules: RulesContractV1,
    state: FixtureState,
): UciSearchResult {
    val depth = if (fixture.mode == "search_avoid") 6 else 4
    val hashMb = when {
        fixture.id.endsWith("forced-black") -> 1
        fixture.id.endsWith("forced-white") -> 64
        else -> 16
    }
    val enginePath = root.resolve("build/headless/linux-x86_64/drawless-fairy")
    val variantsPath = root.resolve("engine/variants.ini")
    require(Files.isRegularFile(enginePath) && Files.isExecutable(enginePath)) {
        "Verified engine is missing or not executable: $enginePath"
    }
    require(Files.isRegularFile(variantsPath)) { "Variant file is missing: $variantsPath" }

    val config = SelfPlayConfig(
        sourcePath = fixturePath,
        runLabel = "fixture-${fixture.id}",
        enginePath = enginePath,
        variantsPath = variantsPath,
        outputPath = root.resolve("build/headless/fixture-test-unused.jsonl"),
        jobSource = JobSource.SINGLE,
        openingsPath = null,
        ladderLevelsPath = null,
        adjacentMatchupsPath = null,
        games = 1,
        parallelGames = 1,
        initialFen = fixture.value("initial_fen"),
        openingMoves = emptyList(),
        variant = rules.preset,
        deadPosition = rules.deadPosition,
        fiftyMove = rules.fiftyMove,
        whiteStrength = UciStrength.Skill(20),
        blackStrength = UciStrength.Skill(20),
        searchLimit = DepthSearchLimit(depth),
        maxPlies = 1,
        pairColors = false,
        failFast = true,
        markCappedForContinuation = true,
        hashMb = hashMb,
        handshakeTimeoutMillis = 15_000,
        readyTimeoutMillis = 15_000,
        searchTimeoutMillis = 30_000,
        stopGraceMillis = 2_000,
        quitTimeoutMillis = 3_000,
    )
    val result = UciEngineProcess.start(
        config,
        role = "fixture-${fixture.id}",
        strength = UciStrength.Skill(20),
    ).use { engine ->
        engine.search(
            initialFen = fixture.value("initial_fen"),
            moves = state.session.moves.map { it.move },
            limit = config.searchLimit,
        )
    }

    check(result.move in ChessRules.legalUciMoves(state.position)) {
        "${fixture.id} engine returned illegal move ${result.move.value}"
    }
    fixture.optional("engine_must_choose")?.let { encoded ->
        val required = encoded.split(Regex("\\s+")).toSet()
        check(result.move.value in required) {
            "${fixture.id} engine must choose ${required.sorted()}, got ${result.move.value}"
        }
    }
    fixture.optional("engine_must_not_choose")?.let { encoded ->
        val forbidden = encoded.split(Regex("\\s+")).toSet()
        check(result.move.value !in forbidden) {
            "${fixture.id} engine chose forbidden move ${result.move.value}"
        }
    }
    fixture.optional("engine_expected_mate_in")?.let { encoded ->
        val expected = encoded.toIntOrNull()
            ?: error("${fixture.id} has invalid engine_expected_mate_in '$encoded'")
        check(result.scoreType == "mate" && result.scoreValue == expected) {
            "${fixture.id} expected score mate $expected, got " +
                "${result.scoreType ?: "<none>"} ${result.scoreValue ?: "<none>"}"
        }
    }
    return result
}

private fun factsBeforeApply(session: GameSession, transition: MoveTransition): PositionFacts {
    val occurrences = session.history.occurrences(transition.resultingPositionKey) + 1
    val repetitionAvoiding = transition.legalAlternativesBeforeMove.count { alternative ->
        session.history.occurrences(alternative.resultingPositionKey) + 1 <
            session.rules.repetitionThreshold
    }
    val fiftyAvoiding = transition.legalAlternativesBeforeMove.count { alternative ->
        alternative.resultingHalfmoveClock < 100
    }
    return PositionFacts(
        mover = transition.mover,
        legalMovesAfter = transition.legalMovesAfter,
        sideToMoveInCheck = transition.sideToMoveInCheck,
        positionOccurrenceCount = occurrences,
        repetitionAvoidingAlternativesBeforeMove = repetitionAvoiding,
        halfmoveClockAfter = transition.halfmoveClockAfter,
        fiftyMoveAvoidingAlternativesBeforeMove = fiftyAvoiding,
        deadPositionAfter = transition.deadPositionAfter,
        moveWasCapture = transition.moveWasCapture,
        materialAfter = transition.materialAfter,
        lastCaptureBy = if (transition.moveWasCapture) transition.mover else session.lastCaptureBy,
    )
}

private fun parseRules(encoded: String): RulesContractV1 {
    val parts = encoded.split('-')
    require(parts.size == 3) { "Invalid rules fixture value '$encoded'" }
    val deadPosition = when (parts[1]) {
        "material" -> DeadPositionPolicy.MATERIAL_VICTORY
        "final" -> DeadPositionPolicy.FINAL_CAPTURE_VICTORY
        else -> error("Invalid dead-position policy in '$encoded'")
    }
    val fiftyMove = when (parts[2]) {
        "off" -> FiftyMovePolicy.DISABLED
        "completing" -> FiftyMovePolicy.COMPLETING_PLAYER_LOSES
        "forced" -> FiftyMovePolicy.FORCED_MOVE_EXCEPTION
        "material" -> FiftyMovePolicy.MATERIAL_VICTORY
        else -> error("Invalid fifty-move policy in '$encoded'")
    }
    return when (parts[0]) {
        "drawless" -> RulesContractV1.drawless(deadPosition, fiftyMove)
        "escape" -> RulesContractV1.escape(deadPosition, fiftyMove)
        else -> error("Invalid rules preset in '$encoded'")
    }.copy(bareKing = BareKingPolicy.CONTINUE)
}

private fun loadRuleFixtures(path: Path): List<RuleFixture> {
    require(Files.isRegularFile(path)) { "Rule fixture table is missing: $path" }
    val raw = Files.readString(path, StandardCharsets.UTF_8)
    require(raw.isNotEmpty()) { "Rule fixture table is empty: $path" }
    require('\r' !in raw) { "Rule fixture table must use LF line endings: $path" }
    val splitLines = raw.split('\n')
    val lines = if (splitLines.lastOrNull().isNullOrEmpty()) splitLines.dropLast(1) else splitLines
    require(lines.none(String::isEmpty)) { "Rule fixture table contains a blank line: $path" }
    require(lines.size >= 2) { "Rule fixture table has no data rows: $path" }

    val header = lines.first().split('\t')
    require(header == RULE_FIXTURE_HEADER) {
        "Rule fixture header mismatch\nexpected: ${RULE_FIXTURE_HEADER.joinToString("\\t")}\n" +
            "actual:   ${header.joinToString("\\t")}"
    }
    val fixtures = lines.drop(1).mapIndexed { index, line ->
        val fields = line.split('\t')
        require(fields.size == RULE_FIXTURE_HEADER.size) {
            "Rule fixture line ${index + 2} has ${fields.size} columns; " +
                "expected ${RULE_FIXTURE_HEADER.size}"
        }
        RuleFixture(index + 2, RULE_FIXTURE_HEADER.zip(fields).toMap()).also { fixture ->
            require(fixture.value("schema_version") == "1") {
                "${fixture.id} has unsupported schema_version"
            }
            require(fixture.mode in setOf("apply", "search_avoid", "search_apply", "search_forced")) {
                "${fixture.id} has unsupported mode '${fixture.mode}'"
            }
        }
    }
    require(fixtures.size == RULE_FIXTURE_COUNT) {
        "Expected $RULE_FIXTURE_COUNT rule fixtures, found ${fixtures.size}"
    }
    val duplicateIds = fixtures.groupingBy(RuleFixture::id).eachCount()
        .filterValues { count -> count != 1 }
        .keys
    require(duplicateIds.isEmpty()) { "Duplicate rule fixture IDs: ${duplicateIds.sorted()}" }
    return fixtures
}

private fun findRepositoryRoot(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
        if (
            Files.isRegularFile(current.resolve("engine/variants.ini")) &&
            Files.isRegularFile(current.resolve("tools/selfplay/fixtures/rule-fixtures.tsv"))
        ) {
            return current
        }
        current = current.parent
    }
    error("Could not infer repository root from ${Path.of("").toAbsolutePath().normalize()}")
}

private fun validateJsonEncoder() {
    check(Json.encode(linkedMapOf("x" to "\n")) == "{\"x\":\"\\n\"}")
    check(Json.decodeObject("{\"x\":[1,true,null]}")["x"] == listOf(1L, true, null))
    check(runCatching { Json.decodeObject("{\"x\":1,\"x\":2}") }.isFailure) {
        "JSON parser accepted a duplicate object key"
    }
    check(runCatching { Json.decodeObject("{\"x\":1} trailing") }.isFailure) {
        "JSON parser accepted trailing content"
    }
    check(runCatching { Json.encode(Double.NaN) }.isFailure) {
        "JSON encoder accepted a non-finite number"
    }
}

private fun validateCampaignConfigs(root: Path) {
    val engine = root.resolve("build/headless/linux-x86_64/drawless-fairy")
    val variants = root.resolve("engine/variants.ini")
    require(Files.isRegularFile(engine)) { "Built campaign engine is missing: $engine" }
    require(Files.isRegularFile(variants)) { "Variant file is missing: $variants" }
    val temporary = Files.createTempDirectory("drawless-campaign-config-test-")
    try {
        val cases = listOf(
            Triple("same-level-canary.properties", JobSource.SAME_LEVEL, 56),
            Triple("same-level-diagnostic.properties", JobSource.SAME_LEVEL, 56),
            Triple("adjacent-canary.properties", JobSource.ADJACENT, 96),
            Triple("adjacent-diagnostic.properties", JobSource.ADJACENT, 96),
        )
        val catalog = BotDifficultyCatalog.namedLevels.associateBy { it.id }
        cases.forEachIndexed { index, (name, expectedSource, expectedCount) ->
            val source = root.resolve("tools/selfplay/config/$name")
            require(Files.isRegularFile(source)) { "Campaign config is missing: $source" }
            val runtimeConfig = temporary.resolve(name)
            val replacements = mapOf(
                "enginePath" to engine.toString(),
                "variantsPath" to variants.toString(),
                "outputPath" to temporary.resolve("output-$index.jsonl").toString(),
                "openingsPath" to root.resolve("tools/selfplay/fixtures/openings.tsv").toString(),
                "ladderLevelsPath" to
                    root.resolve("tools/selfplay/fixtures/ladder-levels.tsv").toString(),
                "adjacentMatchupsPath" to
                    root.resolve("tools/selfplay/fixtures/adjacent-matchups.tsv").toString(),
            )
            val rewritten = Files.readAllLines(source, StandardCharsets.UTF_8).joinToString("\n") { line ->
                val key = line.substringBefore('=', missingDelimiterValue = "")
                replacements[key]?.let { "$key=$it" } ?: line
            } + "\n"
            Files.writeString(runtimeConfig, rewritten, StandardCharsets.UTF_8)
            val config = SelfPlayConfig.load(runtimeConfig)
            check(config.jobSource == expectedSource) { "$name loaded the wrong job source" }
            val jobs = SelfPlayJobs.create(config)
            check(jobs.size == expectedCount && jobs.size == config.games) {
                "$name derived ${jobs.size} jobs; expected $expectedCount"
            }
            check(jobs.map(SelfPlayJob::jobId).toSet().size == jobs.size) {
                "$name derived duplicate job IDs"
            }
            check(jobs.map(SelfPlayJob::openingId).toSet().size == 8) {
                "$name did not derive exactly eight openings"
            }
            val usedLevels = jobs.flatMap { listOfNotNull(it.whiteLevelId, it.blackLevelId) }.toSet()
            check(usedLevels == catalog.keys) {
                "$name levels ${usedLevels.sorted()} do not match the production catalog"
            }
            jobs.forEach { job ->
                val white = catalog.getValue(checkNotNull(job.whiteLevelId))
                val black = catalog.getValue(checkNotNull(job.blackLevelId))
                check(job.whiteCompetitor == white.id && job.blackCompetitor == black.id)
                check(job.whiteStrength == UciStrength.Elo(white.approximateElo))
                check(job.blackStrength == UciStrength.Elo(black.approximateElo))
            }
            if (expectedSource == JobSource.SAME_LEVEL) {
                check(jobs.all { job ->
                    job.pairId == null && job.pairLeg == null &&
                        job.whiteCompetitor == job.blackCompetitor &&
                        job.whiteStrength == job.blackStrength
                }) { "$name contains a malformed same-level job" }
            } else {
                val pairs = jobs.groupBy { checkNotNull(it.pairId) }
                check(pairs.size == 48 && pairs.values.all { it.size == 2 }) {
                    "$name did not derive 48 complete paired openings"
                }
                pairs.forEach { (pairId, legs) ->
                    val lowerWhite = legs.singleOrNull { it.pairLeg == "lower-white" }
                        ?: error("$name pair $pairId has no lower-white leg")
                    val higherWhite = legs.singleOrNull { it.pairLeg == "higher-white" }
                        ?: error("$name pair $pairId has no higher-white leg")
                    check(lowerWhite.openingId == higherWhite.openingId)
                    check(lowerWhite.matchupId == higherWhite.matchupId)
                    check(lowerWhite.whiteCompetitor == higherWhite.blackCompetitor)
                    check(lowerWhite.blackCompetitor == higherWhite.whiteCompetitor)
                    check(lowerWhite.whiteStrength == higherWhite.blackStrength)
                    check(lowerWhite.blackStrength == higherWhite.whiteStrength)
                    check(lowerWhite.whiteLevelId == higherWhite.blackLevelId)
                    check(lowerWhite.blackLevelId == higherWhite.whiteLevelId)
                }
            }
        }
    } finally {
        deleteRecursively(temporary)
    }
}

private fun validateOpeningReplay(root: Path, sourcePath: Path) {
    val baseConfig = SelfPlayConfig(
        sourcePath = sourcePath,
        runLabel = "opening-validation-test",
        enginePath = root.resolve("build/headless/linux-x86_64/drawless-fairy"),
        variantsPath = root.resolve("engine/variants.ini"),
        outputPath = root.resolve("build/headless/opening-validation-unused.jsonl"),
        jobSource = JobSource.SINGLE,
        openingsPath = null,
        ladderLevelsPath = null,
        adjacentMatchupsPath = null,
        games = 1,
        parallelGames = 1,
        initialFen = ChessPosition.START_FEN,
        openingMoves = emptyList(),
        variant = RulesContractV1.Preset.DRAWLESS,
        deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
        fiftyMove = FiftyMovePolicy.DISABLED,
        whiteStrength = UciStrength.Skill(0),
        blackStrength = UciStrength.Skill(0),
        searchLimit = SearchLimit.Nodes(1),
        maxPlies = 1,
        pairColors = false,
        failFast = true,
        markCappedForContinuation = true,
        hashMb = 1,
        handshakeTimeoutMillis = 1_000,
        readyTimeoutMillis = 1_000,
        searchTimeoutMillis = 1_000,
        stopGraceMillis = 100,
        quitTimeoutMillis = 100,
    )

    fun job(id: String, fen: String, vararg moves: String) = SelfPlayJob(
        jobId = id,
        pairId = null,
        pairLeg = null,
        openingId = id,
        openingName = id,
        matchupId = null,
        whiteLevelId = null,
        blackLevelId = null,
        initialFen = fen,
        openingMoves = moves.map(::UciMove),
        whiteCompetitor = "A",
        blackCompetitor = "B",
        whiteStrength = UciStrength.Skill(0),
        blackStrength = UciStrength.Skill(0),
    )

    val valid = replayOpening(baseConfig, job("valid", ChessPosition.START_FEN))
    check(valid.session.outcome == null && valid.fenTimeline.size == 1)

    fun expectRejected(label: String, config: SelfPlayConfig = baseConfig, candidate: SelfPlayJob) {
        check(runCatching { replayOpening(config, candidate) }.isFailure) {
            "Opening gate accepted $label"
        }
    }

    expectRejected(
        "a prefix consuming maxPlies",
        candidate = job("at-cap", ChessPosition.START_FEN, "e2e4"),
    )
    expectRejected(
        "an initially checkmated position",
        candidate = job("initial-checkmate", "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1"),
    )
    expectRejected(
        "an initially dead position",
        candidate = job("initial-dead", "4k3/8/8/8/8/8/8/4K3 w - - 0 1"),
    )
    expectRejected(
        "an already exhausted enabled 50-move clock",
        config = baseConfig.copy(fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION),
        candidate = job(
            "initial-fifty",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 100 51",
        ),
    )
    expectRejected(
        "an illegal prefix",
        config = baseConfig.copy(maxPlies = 2),
        candidate = job("illegal", ChessPosition.START_FEN, "e2e5"),
    )
    expectRejected(
        "a prefix that reaches checkmate",
        config = baseConfig.copy(maxPlies = 5),
        candidate = job(
            "prefix-checkmate",
            ChessPosition.START_FEN,
            "f2f3",
            "e7e5",
            "g2g4",
            "d8h4",
        ),
    )
}

private fun validateJsonlReport(root: Path, sourcePath: Path) {
    val temporary = Files.createTempDirectory("drawless-report-test-")
    try {
        val output = temporary.resolve("base.jsonl")
        val config = SelfPlayConfig(
            sourcePath = sourcePath,
            runLabel = "report-hardening-test",
            enginePath = root.resolve("build/headless/linux-x86_64/drawless-fairy"),
            variantsPath = root.resolve("engine/variants.ini"),
            outputPath = output,
            jobSource = JobSource.SINGLE,
            openingsPath = null,
            ladderLevelsPath = null,
            adjacentMatchupsPath = null,
            games = 1,
            parallelGames = 1,
            initialFen = ChessPosition.START_FEN,
            openingMoves = emptyList(),
            variant = RulesContractV1.Preset.DRAWLESS,
            deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove = FiftyMovePolicy.DISABLED,
            whiteStrength = UciStrength.Skill(0),
            blackStrength = UciStrength.Skill(0),
            searchLimit = SearchLimit.Nodes(1),
            maxPlies = 1,
            pairColors = false,
            failFast = true,
            markCappedForContinuation = true,
            hashMb = 1,
            handshakeTimeoutMillis = 1_000,
            readyTimeoutMillis = 1_000,
            searchTimeoutMillis = 1_000,
            stopGraceMillis = 100,
            quitTimeoutMillis = 100,
        )
        val identity = RunIdentityFactory.create(config)
        JsonlReport.open(config, identity).use { report ->
            check(report.resumedJobIds.isEmpty())
            report.writeInvocationStarted(1, 1, 0)
            report.writeSummary(0, 0, 0, 0, 0, aborted = false)
        }
        JsonlReport.open(config, identity).use { report ->
            report.writeInvocationStarted(1, 1, 0)
            report.writeSummary(0, 0, 0, 0, 0, aborted = false)
        }
        val base = Files.readString(output, StandardCharsets.UTF_8)
        val events = base.lineSequence().filter(String::isNotEmpty)
            .map { Json.decodeObject(it).getValue("event") }
            .toList()
        check(events.count { it == "run_header" } == 1)
        check(events.count { it == "invocation_started" } == 2) {
            "Every initial/resumed invocation must append environment evidence"
        }

        fun path(label: String) = temporary.resolve("$label.jsonl")
        fun expectRejectedUnchanged(label: String, content: String) {
            val candidate = path(label)
            Files.writeString(candidate, content, StandardCharsets.UTF_8)
            val before = Files.readAllBytes(candidate)
            val candidateConfig = config.copy(outputPath = candidate)
            check(runCatching { JsonlReport.open(candidateConfig, identity).close() }.isFailure) {
                "$label report corruption was accepted"
            }
            check(before.contentEquals(Files.readAllBytes(candidate))) {
                "$label report was modified before rejection"
            }
        }

        expectRejectedUnchanged("unrelated", "{\"event\":\"not_drawless\"}")
        val wrongHeader = base.replaceFirst(identity.fingerprint, "0".repeat(64)) +
            "{\"event\":\"partial"
        expectRejectedUnchanged("wrong-header-before-partial", wrongHeader)

        val partial = path("partial-tail")
        Files.writeString(partial, base + "{\"event\":", StandardCharsets.UTF_8)
        JsonlReport.open(config.copy(outputPath = partial), identity).close()
        check(Files.readString(partial, StandardCharsets.UTF_8) == base) {
            "A crash-partial final record was not repaired to the last complete record"
        }

        val partialUtf8 = path("partial-utf8-tail")
        Files.write(
            partialUtf8,
            base.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0xC3.toByte()),
        )
        JsonlReport.open(config.copy(outputPath = partialUtf8), identity).close()
        check(Files.readString(partialUtf8, StandardCharsets.UTF_8) == base) {
            "A crash-partial UTF-8 sequence was not repaired"
        }

        val headerOnlyWithTornTail = path("header-only-torn-utf8-tail")
        val headerOnly = base.substringBefore('\n') + "\n"
        Files.write(
            headerOnlyWithTornTail,
            headerOnly.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0xC3.toByte()),
        )
        JsonlReport.open(config.copy(outputPath = headerOnlyWithTornTail), identity).close()
        check(Files.readString(headerOnlyWithTornTail, StandardCharsets.UTF_8) == headerOnly) {
            "Header ownership validation read ahead into a torn UTF-8 tail"
        }

        val unterminated = path("complete-without-newline")
        Files.writeString(unterminated, base.removeSuffix("\n"), StandardCharsets.UTF_8)
        JsonlReport.open(config.copy(outputPath = unterminated), identity).close()
        check(Files.readString(unterminated, StandardCharsets.UTF_8) == base) {
            "A complete non-newline-terminated record was not preserved"
        }

        val fingerprint = identity.fingerprint
        val validSummary = linkedMapOf<String, Any?>(
            "event" to "invocation_summary",
            "run_fingerprint" to fingerprint,
            "invocation_id" to "00000000-0000-0000-0000-000000000001",
            "scheduled_this_invocation" to 0,
            "resumed_records_skipped" to 0,
            "completed_this_invocation" to 0,
            "censored_this_invocation" to 0,
            "failures_this_invocation" to 0,
            "aborted" to false,
            "finished_at_epoch_ms" to 1,
        )
        fun appended(record: String): String = base + record + "\n"
        expectRejectedUnchanged("blank-record", base + "\n")
        expectRejectedUnchanged(
            "unknown-event",
            appended(Json.encode(linkedMapOf("event" to "mystery", "run_fingerprint" to fingerprint))),
        )
        expectRejectedUnchanged(
            "missing-fingerprint",
            appended(Json.encode(linkedMapOf("event" to "invocation_summary"))),
        )
        expectRejectedUnchanged(
            "duplicate-fingerprint",
            appended(
                "{\"event\":\"invocation_summary\",\"run_fingerprint\":\"$fingerprint\"," +
                    "\"run_fingerprint\":\"$fingerprint\"}",
            ),
        )
        expectRejectedUnchanged(
            "wrong-event-fingerprint",
            appended(Json.encode(validSummary + ("run_fingerprint" to "0".repeat(64)))),
        )
        expectRejectedUnchanged("extra-header", appended(base.lineSequence().first()))
        expectRejectedUnchanged(
            "missing-required-field",
            appended(Json.encode(validSummary - "invocation_id")),
        )

        val game = reportTestGameRecord(fingerprint)
        expectRejectedUnchanged(
            "duplicate-completed-game",
            base + Json.encode(game) + "\n" + Json.encode(game) + "\n",
        )
    } finally {
        deleteRecursively(temporary)
    }
}

private fun reportTestGameRecord(fingerprint: String): Map<String, Any?> = linkedMapOf(
    "event" to "game",
    "run_fingerprint" to fingerprint,
    "record_complete" to true,
    "job_id" to "report-test-game",
    "pair_id" to null,
    "pair_leg" to null,
    "opening_id" to "single",
    "opening_name" to "Test opening",
    "matchup_id" to "single",
    "white_level_id" to null,
    "black_level_id" to null,
    "white_competitor" to "A",
    "black_competitor" to "B",
    "white_strength" to "skill:0",
    "black_strength" to "skill:0",
    "started_at_epoch_ms" to 1,
    "elapsed_ms" to 1,
    "engine_white_name" to "test-engine",
    "engine_black_name" to "test-engine",
    "initial_fen" to ChessPosition.START_FEN,
    "opening_moves" to emptyList<String>(),
    "opening_plies" to 0,
    "plies" to 0,
    "max_plies" to 1,
    "censored" to true,
    "continuation_recommended" to true,
    "winner" to null,
    "loser" to null,
    "end_reason" to null,
    "adjudication_facts" to null,
    "uci_moves" to emptyList<String>(),
    "san_moves" to emptyList<String>(),
    "fen_timeline" to listOf(ChessPosition.START_FEN),
    "final_fen" to ChessPosition.START_FEN,
    "searches" to emptyList<Any?>(),
)

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
