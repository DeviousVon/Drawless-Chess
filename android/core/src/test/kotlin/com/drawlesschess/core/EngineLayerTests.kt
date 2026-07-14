package com.drawlesschess.core

import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.engine.*

private class RecordingTransport : UciTransport {
    val commands = mutableListOf<String>()
    var closed = false
    override fun send(command: String) { commands += command }
}

private class FakeTimeoutScheduler : UciTimeoutScheduler {
    data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
    val tasks = mutableListOf<Task>()

    override fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation {
        val task = Task(delayMillis, action)
        tasks += task
        return EngineCancellation { task.cancelled = true }
    }

    fun fireLatest() {
        val task = tasks.last { !it.cancelled }
        task.action()
    }
}

private data class UciFixture(
    val engine: FairyUciEngine,
    val transport: RecordingTransport,
    val timers: FakeTimeoutScheduler,
)

private fun uciFixture(requiredPatch: Int = 1, actualPatch: Int = 1): UciFixture {
    val transport = RecordingTransport()
    val timers = FakeTimeoutScheduler()
    val engine = FairyUciEngine(
        transport = transport,
        timeoutScheduler = timers,
        build = FairyEngineBuild("test-build", actualPatch),
        policy = UciSessionPolicy(requiredDrawlessPatchVersion = requiredPatch),
        closeTransport = { transport.closed = true },
    )
    return UciFixture(engine, transport, timers)
}

private fun completeHandshake(fixture: UciFixture) {
    fixture.engine.onLine("id name Fairy-Stockfish test")
    fixture.engine.onLine("id author Test Author")
    fixture.engine.onLine("option name UCI_Variant type combo default chess var chess var drawless var escape")
    fixture.engine.onLine("option name MultiPV type spin default 1 min 1 max 500")
    fixture.engine.onLine("option name Skill Level type spin default 20 min -20 max 20")
    fixture.engine.onLine("option name UCI_LimitStrength type check default false")
    fixture.engine.onLine("option name UCI_Elo type spin default 1350 min 500 max 2850")
    fixture.engine.onLine("option name UCI_AnalyseMode type check default false")
    fixture.engine.onLine("option name Syzygy50MoveRule type check default true")
    fixture.engine.onLine("option name Drawless Patch Version type spin default 1 min 1 max 1")
    fixture.engine.onLine("uciok")
    fixture.engine.onLine("readyok")
}

private fun productionRequest(
    id: String = "engine-request",
    gameId: String = "engine-game",
    purpose: EnginePurpose = EnginePurpose.BOT_MOVE,
    multiPv: Int = 1,
    strength: EngineStrength = EngineStrength.ApproximateElo(1_500),
) = EngineRequest(
    requestId = id,
    gameId = gameId,
    positionId = "$gameId:0:start",
    initialFen = ChessPosition.START_FEN,
    moves = emptyList(),
    rules = RulesContractV1.drawless(),
    strength = strength,
    limits = EngineLimits(100, multiPv),
    purpose = purpose,
)

private fun responseFor(request: EngineRequest) = EngineResponse(
    requestId = request.requestId,
    gameId = request.gameId,
    positionId = request.positionId,
    bestMove = UciMove("e2e4"),
    ponderMove = null,
    depth = 1,
    nodes = 1,
    variations = listOf(
        PrincipalVariation(
            scoreCentipawns = 0,
            mateIn = null,
            moves = listOf(UciMove("e2e4")),
        ),
    ),
    engine = EngineIdentity("pacing-test", "1", 1),
)

internal fun registerEngineLayerTests(suite: TestSuite) {
    suite.test("bot move pacing adds the requested delay after successful analysis") {
        val timers = FakeTimeoutScheduler()
        val delegate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(responseFor(request)))
                return EngineCancellation {}
            }
        }
        val engine = BotMovePacingEngine(delegate, timers, 500)
        var delivered = false

        engine.analyze(productionRequest()) { delivered = it.isSuccess }

        assertThat(!delivered)
        assertThat(timers.tasks.single().delay == 500L)
        timers.fireLatest()
        assertThat(delivered)
    }
    suite.test("bot move pacing cancellation stops upstream work and delayed delivery") {
        val timers = FakeTimeoutScheduler()
        var upstreamCancelled = false
        val delegate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(responseFor(request)))
                return EngineCancellation { upstreamCancelled = true }
            }
        }
        val engine = BotMovePacingEngine(delegate, timers, 500)
        var delivered = false

        val cancellation = engine.analyze(productionRequest()) { delivered = true }
        cancellation.cancel()

        assertThat(upstreamCancelled)
        assertThat(timers.tasks.single().cancelled)
        assertThat(!delivered)
    }
    suite.test("bot move pacing leaves hints and engine failures immediate") {
        val timers = FakeTimeoutScheduler()
        val successful = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(responseFor(request)))
                return EngineCancellation {}
            }
        }
        var hintDelivered = false
        BotMovePacingEngine(successful, timers, 500).analyze(
            productionRequest(purpose = EnginePurpose.HINT),
        ) { hintDelivered = it.isSuccess }

        val failure = IllegalStateException("engine unavailable")
        val failing = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.failure(failure))
                return EngineCancellation {}
            }
        }
        var deliveredFailure: Throwable? = null
        BotMovePacingEngine(failing, timers, 500).analyze(productionRequest()) {
            deliveredFailure = it.exceptionOrNull()
        }

        assertThat(hintDelivered)
        assertThat(deliveredFailure === failure)
        assertThat(timers.tasks.isEmpty())
    }
    suite.test("bot move pacing falls back to the valid move when scheduling is unavailable") {
        val delegate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(responseFor(request)))
                return EngineCancellation {}
            }
        }
        val rejectedScheduler = UciTimeoutScheduler { _, _ ->
            throw IllegalStateException("scheduler closed")
        }
        var delivered: EngineResponse? = null

        BotMovePacingEngine(delegate, rejectedScheduler, 500).analyze(productionRequest()) {
            delivered = it.getOrThrow()
        }

        assertThat(delivered?.bestMove == UciMove("e2e4"))
    }
    suite.test("UCI parser reads identity lines") {
        assertThat(UciProtocol.parse("id name Fairy-Stockfish 14") == UciMessage.IdName("Fairy-Stockfish 14"))
        assertThat(UciProtocol.parse("id author Fabian Fichter") == UciMessage.IdAuthor("Fabian Fichter"))
    }
    suite.test("UCI parser reads spin option ranges") {
        val parsed = UciProtocol.parse("option name Skill Level type spin default 20 min -20 max 20")
        val option = (parsed as UciMessage.Option).value
        assertThat(option.name == "Skill Level" && option.minimum == -20 && option.maximum == 20)
    }
    suite.test("UCI parser reads variant combo choices") {
        val parsed = UciProtocol.parse(
            "option name UCI_Variant type combo default chess var chess var drawless var escape",
        ) as UciMessage.Option
        assertThat(parsed.value.choices == listOf("chess", "drawless", "escape"))
    }
    suite.test("UCI parser preserves string option defaults") {
        val parsed = UciProtocol.parse(
            "option name Debug Log File type string default path with spaces",
        ) as UciMessage.Option
        assertThat(parsed.value.defaultValue == "path with spaces")
    }
    suite.test("UCI parser reads centipawn MultiPV info") {
        val info = (UciProtocol.parse(
            "info depth 12 seldepth 18 multipv 2 score cp -34 lowerbound nodes 900 nps 12000 time 75 pv e2e4 e7e5",
        ) as UciMessage.Info).value
        assertThat(info.depth == 12 && info.multiPv == 2 && info.nodes == 900L)
        assertThat(info.score == UciScore.Centipawns(-34, EngineScoreBound.LOWER))
        assertThat(info.principalVariation == listOf(UciMove("e2e4"), UciMove("e7e5")))
    }
    suite.test("UCI parser reads mate scores and WDL") {
        val info = (UciProtocol.parse(
            "info depth 4 score mate 2 wdl 980 20 0 nodes 31 pv d8h4",
        ) as UciMessage.Info).value
        assertThat(info.score == UciScore.Mate(2))
        assertThat(info.wdl == UciWdl(980, 20, 0))
    }
    suite.test("UCI parser accepts both null bestmove spellings") {
        assertThat((UciProtocol.parse("bestmove (none)") as UciMessage.BestMove).move == null)
        assertThat((UciProtocol.parse("bestmove 0000") as UciMessage.BestMove).move == null)
    }
    suite.test("UCI parser reads bestmove and ponder") {
        val best = UciProtocol.parse("bestmove e2e4 ponder e7e5") as UciMessage.BestMove
        assertThat(best.move == UciMove("e2e4") && best.ponder == UciMove("e7e5"))
    }
    suite.test("UCI parser rejects malformed numeric fields") {
        assertThrows<UciProtocolException> { UciProtocol.parse("info depth many pv e2e4") }
    }
    suite.test("UCI parser rejects malformed PV moves") {
        assertThrows<UciProtocolException> { UciProtocol.parse("info depth 1 score cp 4 pv e2-e4") }
    }
    suite.test("UCI parser keeps unknown lines forward compatible") {
        assertThat(UciProtocol.parse("registration checking") is UciMessage.Unknown)
    }
    suite.test("UCI command builder retains full move history") {
        val command = UciCommands.position(
            ChessPosition.START_FEN,
            listOf(UciMove("g1f3"), UciMove("g8f6"), UciMove("f3g1")),
        )
        assertThat(command == "position startpos moves g1f3 g8f6 f3g1")
    }
    suite.test("UCI engine queues work through handshake") {
        val fixture = uciFixture()
        var result: Result<EngineResponse>? = null
        fixture.engine.analyze(productionRequest(), onResult = { result = it })
        assertThat(fixture.transport.commands == listOf("uci"))
        completeHandshake(fixture)
        assertThat(fixture.engine.state == UciSessionState.PREPARING)
        assertThat("setoption name UCI_Variant value drawless" in fixture.transport.commands)
        assertThat("setoption name UCI_Elo value 1500" in fixture.transport.commands)
        fixture.engine.onLine("readyok")
        assertThat(fixture.transport.commands.takeLast(2) == listOf("position startpos", "go movetime 100"))
        fixture.engine.onLine("info depth 8 multipv 1 score cp 24 nodes 500 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove e2e4 ponder e7e5")
        assertThat(result?.getOrThrow()?.bestMove == UciMove("e2e4"))
        assertThat(result?.getOrThrow()?.engine?.drawlessPatch == 1)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine converts mate and MultiPV analysis") {
        val fixture = uciFixture()
        fixture.engine.start()
        completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.HINT, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 9 multipv 1 score mate 3 nodes 700 pv d1h5 b8c6")
        fixture.engine.onLine("info depth 9 multipv 2 score cp 40 nodes 700 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove d1h5")
        val completed = requireNotNull(response)
        assertThat(completed.variations.size == 2)
        assertThat(completed.variations[0].mateIn == 3 && completed.variations[1].rank == 2)
        assertThat("setoption name UCI_AnalyseMode value true" in fixture.transport.commands)
    }
    suite.test("UCI engine ignores shallower replacement PV") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest()) { response = it.getOrThrow() }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 8 score cp 30 nodes 600 pv e2e4")
        fixture.engine.onLine("info depth 7 score cp 10 nodes 700 pv d2d4")
        fixture.engine.onLine("bestmove e2e4")
        val completed = requireNotNull(response)
        assertThat(completed.variations.single().moves.first() == UciMove("e2e4"))
        assertThat(completed.nodes == 700L)
    }
    suite.test("UCI engine drains a cancelled search before queued work") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var firstCalled = false
        var secondCalled = false
        val cancellation = fixture.engine.analyze(productionRequest(id = "first")) { firstCalled = true }
        fixture.engine.onLine("readyok")
        cancellation.cancel()
        fixture.engine.analyze(productionRequest(id = "second")) { secondCalled = it.isSuccess }
        assertThat(fixture.engine.state == UciSessionState.DRAINING_SEARCH)
        assertThat(fixture.transport.commands.last() == "stop")
        fixture.engine.onLine("bestmove e2e4")
        assertThat(!firstCalled && fixture.engine.state == UciSessionState.PREPARING)
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 1 score cp 0 nodes 1 pv d2d4")
        fixture.engine.onLine("bestmove d2d4")
        assertThat(secondCalled)
    }
    suite.test("UCI engine times out and closes its transport") {
        val fixture = uciFixture()
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        fixture.timers.fireLatest()
        assertThat(error is UciEngineTimeoutException)
        assertThat(fixture.engine.state == UciSessionState.FAILED && fixture.transport.closed)
    }
    suite.test("UCI engine retains an asynchronous startup failure for later analysis") {
        val fixture = uciFixture()
        val startupFailure = IllegalStateException(
            "Only one native Fairy-Stockfish session may exist at a time",
        )
        fixture.engine.start()
        fixture.engine.onTransportFailure(startupFailure)

        val thrown = runCatching {
            fixture.engine.analyze(productionRequest()) {}
        }.exceptionOrNull()
        assertThat(thrown is UciEngineStateException)
        assertThat(thrown?.message?.contains(startupFailure.message!!) == true)
        assertThat(thrown?.cause === startupFailure)
        assertThat(fixture.engine.state == UciSessionState.FAILED && fixture.transport.closed)
    }
    suite.test("UCI engine rejects an unpatched production build") {
        val fixture = uciFixture(actualPatch = 0)
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture)
        assertThat(error is UciEngineCompatibilityException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine rejects mismatched advertised patch identity") {
        val fixture = uciFixture(actualPatch = 2)
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture)
        assertThat(error is UciEngineCompatibilityException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine rejects a live-position null bestmove") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("bestmove (none)")
        assertThat(error is UciEngineStateException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("named bot ladder matches the approved beginner progression") {
        val levels = BotDifficultyCatalog.namedLevels
        assertThat(
            levels.map { it.id to it.approximateElo } == listOf(
                "learner" to 500,
                "casual" to 650,
                "challenger" to 850,
                "club" to 1_100,
                "expert" to 1_500,
                "master" to 2_000,
                "grandmaster" to 2_500,
            ),
        )
        assertThat(levels.zipWithNext().all { (a, b) -> a.approximateElo < b.approximateElo })
        assertThat(BotDifficultyResolver.resolve(BotDifficultySelection.Named("club"), OfflineRating()).targetElo == 1_100)
    }
    suite.test("legacy ladder identity is inferred only from exact historical Elo") {
        assertThat(BotDifficultyCatalog.legacyLevelIdForElo(900) == "casual")
        assertThat(BotDifficultyCatalog.legacyLevelIdForElo(1_500) == "club")
        assertThat(BotDifficultyCatalog.legacyLevelIdForElo(650) == null)
        assertThat(BotDifficultyCatalog.legacyLevelIdForElo(1_499) == null)
    }
    suite.test("raw skill display no longer pretends every engine is Casual") {
        assertThat(BotDifficultyCatalog.displayLevel(null, EngineStrength.SkillLevel(-20)).id == "learner")
        assertThat(BotDifficultyCatalog.approximateEloForSkillLevel(-3) in 956..958)
        assertThat(BotDifficultyCatalog.displayLevel(null, EngineStrength.SkillLevel(-3)).id == "challenger")
        assertThat(BotDifficultyCatalog.displayLevel(null, EngineStrength.SkillLevel(20)).id == "grandmaster")
    }
    suite.test("every named rung uses exact UCI Elo limiting rather than raw skill") {
        BotDifficultyCatalog.namedLevels.forEach { level ->
            val fixture = uciFixture()
            fixture.engine.analyze(
                productionRequest(
                    id = "named-${level.id}",
                    strength = EngineStrength.ApproximateElo(level.approximateElo),
                ),
            ) {}
            completeHandshake(fixture)
            assertThat("setoption name UCI_LimitStrength value true" in fixture.transport.commands)
            assertThat("setoption name UCI_Elo value ${level.approximateElo}" in fixture.transport.commands)
            assertThat(fixture.transport.commands.none { it.startsWith("setoption name Skill Level value") })
        }
    }
    suite.test("custom bot Elo maps directly to engine strength") {
        val resolved = BotDifficultyResolver.resolve(BotDifficultySelection.CustomElo(1_735), OfflineRating())
        assertThat(resolved.targetElo == 1_735 && resolved.strength.elo == 1_735 && !resolved.adaptive)
    }
    suite.test("adaptive bot follows the selected rating pool") {
        val resolved = BotDifficultyResolver.resolve(BotDifficultySelection.Adaptive, OfflineRating(1_642, 12))
        assertThat(resolved.targetElo == 1_642 && resolved.adaptive)
    }
    suite.test("offline Elo rewards an upset win") {
        val rating = OfflineElo.update(OfflineRating(1_200, 0), 1_800, RatedResult.WIN)
        assertThat(rating.rating > 1_220 && rating.gamesPlayed == 1 && rating.provisional)
    }
    suite.test("offline Elo reduces rating after a loss") {
        val rating = OfflineElo.update(OfflineRating(1_500, 40), 1_500, RatedResult.LOSS)
        assertThat(rating.rating == 1_490 && rating.gamesPlayed == 41)
    }
    suite.test("rating book maintains overall and ruleset time pools") {
        val rules = RulesContractV1.escape()
        val control = TimeControl.Clock(5 * 60_000L)
        val book = OfflineRatingBook().recordRated(GameMode.RATED, rules, control, 1_200, RatedResult.WIN)
        assertThat(book.overall.gamesPlayed == 1)
        assertThat(book.forGame(rules, control).gamesPlayed == 1)
        assertThat(book.forGame(RulesContractV1.drawless(), control).gamesPlayed == 0)
    }
    suite.test("casual games cannot change offline ratings") {
        assertThrows<IllegalArgumentException> {
            OfflineRatingBook().recordRated(
                GameMode.CASUAL, RulesContractV1.drawless(), TimeControl.Untimed,
                1_200, RatedResult.WIN,
            )
        }
    }
    suite.test("hint requests are full strength casual MultiPV") {
        val request = AnalysisRequests.hint(
            "hint-1", "g", "p", ChessPosition.START_FEN, emptyList(),
            RulesContractV1.drawless(), GameMode.CASUAL,
        )
        assertThat(request.purpose == EnginePurpose.HINT && request.limits.multiPv == 3)
        assertThat(request.strength == EngineStrength.SkillLevel(20))
    }
    suite.test("rated games reject hint requests") {
        assertThrows<IllegalArgumentException> {
            AnalysisRequests.hint(
                "hint-2", "g", "p", ChessPosition.START_FEN, emptyList(),
                RulesContractV1.drawless(), GameMode.RATED,
            )
        }
    }
    suite.test("review planner tags every decision position") {
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        val plan = GameReviewPlanner.plan("review-game", ChessPosition.START_FEN, moves, RulesContractV1.drawless())
        assertThat(plan.requests.size == moves.size)
        assertThat(plan.requests.map { it.moves.size } == listOf(0, 1, 2))
        assertThat(plan.requests.all { it.purpose == EnginePurpose.REVIEW })
    }
    suite.test("review planner rejects illegal histories") {
        assertThrows<IllegalArgumentException> {
            GameReviewPlanner.plan(
                "review-game", ChessPosition.START_FEN,
                listOf(UciMove("e2e5")), RulesContractV1.drawless(),
            )
        }
    }
    suite.test("forced-repetition parity fixture has exactly one completing move") {
        val initialFen = "6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1"
        val moves = listOf(
            "f6f7", "g8h8", "f7f6", "h8g8",
            "f6f7", "g8h8", "f7f6", "h8g8",
        ).map(::UciMove)
        var position = ChessPosition.fromFen(initialFen)
        var session = GameSession.newGame(
            "forced-parity", RulesContractV1.drawless(),
            com.drawlesschess.core.chess.RepetitionKey.of(position), position.sideToMove,
        )
        for (move in moves) {
            val transition = com.drawlesschess.core.chess.ChessAdapter.transition(position, move)
            if (move == UciMove("h8g8") && session.moves.size == 7) {
                assertThat(transition.legalAlternativesBeforeMove.map { it.move } == listOf(UciMove("h8g8")))
            }
            session = session.apply(transition)
            position = com.drawlesschess.core.chess.ChessRules.apply(position, move)
        }
        assertThat(session.outcome?.reason == EndReason.REPETITION)
        assertThat(session.outcome?.winner == Side.BLACK)
    }
    suite.test("forced-repetition parity fixture is color symmetric") {
        val initialFen = "1k6/8/8/8/8/2q5/P7/1K6 b - - 0 1"
        val moves = listOf(
            "c3c2", "b1a1", "c2c3", "a1b1",
            "c3c2", "b1a1", "c2c3", "a1b1",
        ).map(::UciMove)
        var position = ChessPosition.fromFen(initialFen)
        var session = GameSession.newGame(
            "forced-parity-white", RulesContractV1.drawless(),
            com.drawlesschess.core.chess.RepetitionKey.of(position), position.sideToMove,
        )
        for (move in moves) {
            val transition = com.drawlesschess.core.chess.ChessAdapter.transition(position, move)
            if (move == UciMove("a1b1") && session.moves.size == 7) {
                assertThat(transition.legalAlternativesBeforeMove.map { it.move } == listOf(UciMove("a1b1")))
            }
            session = session.apply(transition)
            position = com.drawlesschess.core.chess.ChessRules.apply(position, move)
        }
        assertThat(session.outcome?.reason == EndReason.REPETITION)
        assertThat(session.outcome?.winner == Side.WHITE)
    }
}
