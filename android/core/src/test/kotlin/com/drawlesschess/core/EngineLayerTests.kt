package com.drawlesschess.core

import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.coordinator.GameConfig
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

private fun uciFixture(requiredPatch: Int = 2, actualPatch: Int = 2): UciFixture {
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

private fun drawlessRulesOptionLines() = listOf(
    "option name Drawless Dead Position type combo default material-victory " +
        "var material-victory var final-capture-victory",
    "option name Drawless Fifty Move type combo default material-victory " +
        "var disabled var completing-player-loses var forced-move-exception var material-victory",
    "option name Drawless Bare King type combo default bare-king-loses " +
        "var continue var bare-king-loses",
)

private fun completeHandshake(
    fixture: UciFixture,
    advertiseShowWdl: Boolean = true,
    patchVersion: Int = 2,
    rulesOptionLines: List<String> = drawlessRulesOptionLines(),
) {
    fixture.engine.onLine("id name Fairy-Stockfish test")
    fixture.engine.onLine("id author Test Author")
    fixture.engine.onLine("option name UCI_Variant type combo default chess var chess var drawless var escape")
    fixture.engine.onLine("option name MultiPV type spin default 1 min 1 max 500")
    fixture.engine.onLine("option name Skill Level type spin default 20 min -20 max 20")
    fixture.engine.onLine("option name UCI_LimitStrength type check default false")
    fixture.engine.onLine("option name UCI_Elo type spin default 1350 min 500 max 2850")
    fixture.engine.onLine("option name UCI_AnalyseMode type check default false")
    if (advertiseShowWdl) {
        fixture.engine.onLine("option name UCI_ShowWDL type check default false")
    }
    fixture.engine.onLine("option name Syzygy50MoveRule type check default true")
    fixture.engine.onLine(
        "option name Drawless Patch Version type spin default $patchVersion " +
            "min $patchVersion max $patchVersion",
    )
    rulesOptionLines.forEach(fixture.engine::onLine)
    fixture.engine.onLine("uciok")
    fixture.engine.onLine("readyok")
}

private fun productionRequest(
    id: String = "engine-request",
    gameId: String = "engine-game",
    purpose: EnginePurpose = EnginePurpose.BOT_MOVE,
    multiPv: Int = 1,
    strength: EngineStrength = EngineStrength.ApproximateElo(1_500),
    rules: RulesContractV1 = RulesContractV1.drawless(),
) = EngineRequest(
    requestId = id,
    gameId = gameId,
    positionId = "$gameId:0:start",
    initialFen = ChessPosition.START_FEN,
    moves = emptyList(),
    rules = rules,
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

private class FakeReviewEngine : ChessEngine {
    data class Pending(
        val request: EngineRequest,
        val callback: (Result<EngineResponse>) -> Unit,
        var cancelled: Boolean = false,
        var responded: Boolean = false,
    )

    val requests = mutableListOf<Pending>()

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        val pending = Pending(request, onResult)
        requests += pending
        return EngineCancellation { pending.cancelled = true }
    }

    fun respond(
        bestMove: String,
        centipawns: Int? = 0,
        mateIn: Int? = null,
        bound: EngineScoreBound = EngineScoreBound.EXACT,
        wdl: EngineWdl? = null,
        evidenceAvailable: Boolean = true,
    ) {
        val evaluation = PrincipalVariation(
            scoreCentipawns = if (mateIn == null) requireNotNull(centipawns) else null,
            mateIn = mateIn,
            moves = listOf(UciMove(bestMove)),
            bound = bound,
            wdl = wdl,
            depth = 12,
            evidenceAvailable = evidenceAvailable,
        )
        respondWithVariations(bestMove, listOf(evaluation))
    }

    fun respondWithVariations(bestMove: String, variations: List<PrincipalVariation>) {
        val pending = requests.first { !it.responded }
        pending.responded = true
        pending.callback(Result.success(reviewResponseFor(pending.request, bestMove, variations)))
    }
}

private fun reviewResponseFor(
    request: EngineRequest,
    bestMove: String,
    variations: List<PrincipalVariation> = listOf(reviewVariation(bestMove)),
    engine: EngineIdentity = EngineIdentity("review-test", "1", 2),
): EngineResponse = EngineResponse(
    requestId = request.requestId,
    gameId = request.gameId,
    positionId = request.positionId,
    bestMove = UciMove(bestMove),
    ponderMove = null,
    depth = 12,
    nodes = 1_000,
    variations = variations,
    engine = engine,
)

private fun reviewVariation(
    move: String,
    centipawns: Int? = 0,
    mateIn: Int? = null,
    rank: Int = 1,
    bound: EngineScoreBound = EngineScoreBound.EXACT,
    wdl: EngineWdl? = null,
    evidenceAvailable: Boolean = true,
    continuation: List<String> = emptyList(),
): PrincipalVariation = PrincipalVariation(
    scoreCentipawns = if (mateIn == null) requireNotNull(centipawns) else null,
    mateIn = mateIn,
    moves = (listOf(move) + continuation).map(::UciMove),
    rank = rank,
    bound = bound,
    wdl = wdl,
    depth = 12,
    evidenceAvailable = evidenceAvailable,
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
        assertThat("setoption name UCI_ShowWDL value false" in fixture.transport.commands)
        fixture.engine.onLine("readyok")
        assertThat(fixture.transport.commands.takeLast(2) == listOf("position startpos", "go movetime 100"))
        fixture.engine.onLine("info depth 8 multipv 1 score cp 24 nodes 500 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove e2e4 ponder e7e5")
        assertThat(result?.getOrThrow()?.bestMove == UciMove("e2e4"))
        assertThat(result?.getOrThrow()?.engine?.drawlessPatch == 2)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI result callback can start the next analysis reentrantly") {
        val fixture = uciFixture()
        fixture.engine.start()
        completeHandshake(fixture)
        var firstDelivered = false
        var secondDelivered = false
        val nextRequest = productionRequest(
            id = "reentrant-review-prefetch",
            purpose = EnginePurpose.REVIEW,
            multiPv = 3,
        )

        fixture.engine.analyze(productionRequest(id = "completed-bot-move")) { result ->
            firstDelivered = result.isSuccess
            fixture.engine.analyze(nextRequest) { nextResult ->
                secondDelivered = nextResult.isSuccess
            }
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 8 score cp 24 nodes 500 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove e2e4")

        // onBestMove clears the completed work before delivery. beginQueuedIfAny must leave the
        // new active request installed by the callback instead of dropping or replacing it.
        assertThat(firstDelivered)
        assertThat(fixture.engine.state == UciSessionState.PREPARING)
        assertThat(fixture.transport.commands.last() == "isready")
        fixture.engine.onLine("readyok")
        assertThat(fixture.transport.commands.takeLast(2) == listOf("position startpos", "go movetime 100"))
        fixture.engine.onLine("info depth 8 multipv 1 score cp 20 nodes 500 pv d2d4 d7d5")
        fixture.engine.onLine("info depth 8 multipv 2 score cp 18 nodes 510 pv e2e4 e7e5")
        fixture.engine.onLine("info depth 8 multipv 3 score cp 15 nodes 520 pv c2c4 e7e5")
        fixture.engine.onLine("bestmove d2d4")

        assertThat(secondDelivered)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine converts mate and MultiPV analysis") {
        val fixture = uciFixture()
        fixture.engine.start()
        completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 9 multipv 1 score mate 3 wdl 998 2 0 nodes 700 pv d1h5 b8c6")
        fixture.engine.onLine("info depth 9 multipv 2 score cp 40 wdl 580 300 120 nodes 710 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove d1h5")
        val completed = requireNotNull(response)
        assertThat(completed.variations.size == 2)
        assertThat(completed.variations[0].mateIn == 3 && completed.variations[1].rank == 2)
        assertThat(completed.variations[0].wdl == EngineWdl(998, 2, 0))
        assertThat(completed.depth == 9 && completed.nodes == 710L)
        assertThat(completed.variations.all { it.depth == 9 })
        assertThat("setoption name UCI_AnalyseMode value true" in fixture.transport.commands)
        assertThat("setoption name UCI_ShowWDL value true" in fixture.transport.commands)
    }
    suite.test("UCI review tolerates an engine that does not advertise WDL output") {
        val fixture = uciFixture()
        fixture.engine.start()
        completeHandshake(fixture, advertiseShowWdl = false)
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW)) {}

        assertThat(fixture.transport.commands.none { it.contains("UCI_ShowWDL") })
        fixture.engine.close()
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
        assertThat(completed.depth == 8 && completed.nodes == 600L)
    }
    suite.test("every bot strength preserves native bestmove and ponder when rank-one PV differs") {
        val approximateEloCases = buildList {
            BotDifficultyCatalog.namedLevels.forEach { level ->
                add("named-${level.id}" to level.approximateElo)
            }
            addAll(
                listOf(
                    "adaptive-minimum" to 500,
                    "adaptive-starting" to 800,
                    "adaptive-maximum" to 2_850,
                    "custom-minimum" to 500,
                    "custom-representative" to 1_735,
                    "custom-maximum" to 2_850,
                    "legacy-learner" to 600,
                    "legacy-casual" to 900,
                    "legacy-challenger" to 1_200,
                    "legacy-club" to 1_500,
                    "legacy-expert" to 1_850,
                    "legacy-master" to 2_200,
                    "legacy-grandmaster" to 2_600,
                ),
            )
        }.map { (id, elo) -> id to EngineStrength.ApproximateElo(elo) }
        val rawSkillCases = listOf(-20, -3, 0, 19, 20).map { level ->
            "legacy-skill-$level" to EngineStrength.SkillLevel(level)
        }

        (approximateEloCases + rawSkillCases).forEach { (caseId, strength) ->
            val fixture = uciFixture()
            fixture.engine.start(); completeHandshake(fixture)
            var response: EngineResponse? = null
            fixture.engine.analyze(
                productionRequest(
                    id = "bestmove-$caseId",
                    purpose = EnginePurpose.BOT_MOVE,
                    strength = strength,
                ),
            ) {
                response = it.getOrThrow()
            }
            when (strength) {
                is EngineStrength.ApproximateElo -> {
                    assertThat(
                        "setoption name UCI_LimitStrength value true" in fixture.transport.commands,
                        "$caseId did not enable UCI Elo limiting",
                    )
                    assertThat(
                        "setoption name UCI_Elo value ${strength.elo}" in fixture.transport.commands,
                        "$caseId did not send its exact Elo",
                    )
                    assertThat(
                        fixture.transport.commands.none { it.startsWith("setoption name Skill Level value") },
                        "$caseId unexpectedly used raw Skill Level",
                    )
                }
                is EngineStrength.SkillLevel -> {
                    assertThat(
                        "setoption name UCI_LimitStrength value false" in fixture.transport.commands,
                        "$caseId did not disable UCI Elo limiting",
                    )
                    assertThat(
                        "setoption name Skill Level value ${strength.level}" in fixture.transport.commands,
                        "$caseId did not send its exact raw Skill Level",
                    )
                    assertThat(
                        fixture.transport.commands.none { it.startsWith("setoption name UCI_Elo value") },
                        "$caseId unexpectedly sent an Elo value",
                    )
                }
            }
            fixture.engine.onLine("readyok")
            fixture.engine.onLine("info depth 8 score cp 30 nodes 800 pv e2e4 e7e5")
            fixture.engine.onLine("bestmove d2d3 ponder d7d5")

            val completed = requireNotNull(response) { "$caseId did not complete" }
            assertThat(completed.bestMove == UciMove("d2d3"), "$caseId replaced the native bestmove")
            assertThat(completed.ponderMove == UciMove("d7d5"), "$caseId replaced the native ponder")
            assertThat(
                completed.variations.single().moves.first() == UciMove("e2e4"),
                "$caseId did not retain the stronger PV as analysis evidence",
            )
            fixture.engine.close()
        }
    }
    suite.test("a reused bot engine applies mixed strengths without leaking a prior game") {
        val mixedStrengths = listOf(
            "named-learner" to EngineStrength.ApproximateElo(550),
            "named-grandmaster" to EngineStrength.ApproximateElo(2_550),
            "named-casual" to EngineStrength.ApproximateElo(800),
            "legacy-skill-minimum" to EngineStrength.SkillLevel(-20),
            "named-master" to EngineStrength.ApproximateElo(2_100),
            "adaptive-minimum" to EngineStrength.ApproximateElo(500),
            "named-challenger" to EngineStrength.ApproximateElo(1_000),
            "legacy-skill-maximum" to EngineStrength.SkillLevel(20),
            "named-expert" to EngineStrength.ApproximateElo(1_675),
            "adaptive-maximum" to EngineStrength.ApproximateElo(2_850),
            "named-club" to EngineStrength.ApproximateElo(1_300),
            "custom-representative" to EngineStrength.ApproximateElo(1_735),
        )
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)

        mixedStrengths.forEachIndexed { index, (caseId, strength) ->
            val commandStart = fixture.transport.commands.size
            var response: EngineResponse? = null
            fixture.engine.analyze(
                productionRequest(
                    id = "mixed-$index-$caseId",
                    gameId = "mixed-game-$index",
                    purpose = EnginePurpose.BOT_MOVE,
                    strength = strength,
                ),
            ) {
                response = it.getOrThrow()
            }
            val requestCommands = fixture.transport.commands.drop(commandStart)
            when (strength) {
                is EngineStrength.ApproximateElo -> {
                    assertThat(
                        "setoption name UCI_LimitStrength value true" in requestCommands &&
                            "setoption name UCI_Elo value ${strength.elo}" in requestCommands,
                        "$caseId leaked a prior strength instead of applying Elo ${strength.elo}",
                    )
                }
                is EngineStrength.SkillLevel -> {
                    assertThat(
                        "setoption name UCI_LimitStrength value false" in requestCommands &&
                            "setoption name Skill Level value ${strength.level}" in requestCommands,
                        "$caseId leaked a prior strength instead of applying Skill Level ${strength.level}",
                    )
                }
            }
            fixture.engine.onLine("readyok")
            fixture.engine.onLine("info depth 8 score cp 30 nodes 800 pv e2e4 e7e5")
            fixture.engine.onLine("bestmove d2d3 ponder d7d5")
            assertThat(
                requireNotNull(response) { "$caseId did not complete" }.bestMove == UciMove("d2d3"),
                "$caseId replaced the native bestmove in a reused session",
            )
        }
        fixture.engine.close()
    }
    suite.test("UCI engine selects one deepest complete same-depth MultiPV snapshot") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 8 multipv 1 score cp 30 nodes 800 pv e2e4 e7e5")
        fixture.engine.onLine("info depth 8 multipv 2 score cp 20 nodes 810 pv d2d4 d7d5")
        fixture.engine.onLine("info depth 9 multipv 1 score cp 35 nodes 900 pv e2e4 e7e5")
        fixture.engine.onLine("bestmove e2e4")

        val completed = requireNotNull(response)
        assertThat(completed.depth == 8 && completed.nodes == 810L)
        assertThat(completed.variations.map { it.rank } == listOf(1, 2))
        assertThat(completed.variations.map { it.moves.first().value } == listOf("e2e4", "d2d4"))
        assertThat(completed.variations.all { it.depth == 8 && it.evidenceAvailable })
    }
    suite.test("UCI engine preserves a complete cycle when a timed iteration changes bestmove") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        val request = productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 9).copy(
            initialFen = "4k3/7p/8/8/8/8/P7/N3K3 w - - 99 1",
        )
        fixture.engine.analyze(request) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        listOf(
            "e1f2", "a2a4", "a1c2", "a1b3", "e1d2", "e1f1", "e1e2", "e1d1", "a2a3",
        ).forEachIndexed { index, move ->
            fixture.engine.onLine(
                "info depth 26 multipv ${index + 1} score cp ${30 - index} nodes 800 pv $move",
            )
        }
        // Fairy-Stockfish's final report can contain the searched ranks from depth 27 followed
        // by still-valid depth-26 ranks from the interrupted iteration. It is one mixed-depth
        // reporting cycle and must not overwrite the prior complete depth-26 cycle.
        fixture.engine.onLine("info depth 27 multipv 1 score cp 35 nodes 900 pv a2a4")
        fixture.engine.onLine("info depth 27 multipv 2 score cp 34 nodes 900 pv e1f2")
        listOf("e1f1", "a1c2", "e1d2", "e1e2", "a2a3", "e1d1", "a1b3")
            .forEachIndexed { index, move ->
                fixture.engine.onLine(
                    "info depth 26 multipv ${index + 3} score cp ${33 - index} nodes 900 pv $move",
                )
            }
        fixture.engine.onLine("bestmove a2a4 ponder h7h5")

        val completed = requireNotNull(response)
        assertThat(completed.bestMove == UciMove("e1f2"))
        assertThat(completed.ponderMove == null)
        assertThat(completed.depth == 26 && completed.nodes == 800L)
        assertThat(completed.variations.size == 9)
        assertThat(completed.variations[3].moves.first() == UciMove("a1b3"))
        assertThat(completed.variations.all { it.depth == 26 && it.evidenceAvailable })
    }
    suite.test("UCI engine marks incoherent MultiPV evidence unavailable") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 9 multipv 1 score cp 35 nodes 900 pv e2e4")
        fixture.engine.onLine("info depth 8 multipv 2 score cp 20 nodes 810 pv d2d4")
        fixture.engine.onLine("bestmove e2e4")

        val completed = requireNotNull(response)
        assertThat(completed.depth == 0 && completed.nodes == 0L)
        assertThat(completed.variations.single().moves == listOf(UciMove("e2e4")))
        assertThat(!completed.variations.single().evidenceAvailable)
    }
    suite.test("UCI engine aligns a coherent MultiPV snapshot with bestmove") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 8 multipv 1 score cp 30 nodes 800 pv e2e4")
        fixture.engine.onLine("info depth 8 multipv 2 score cp 20 nodes 810 pv d2d4")
        fixture.engine.onLine("info depth 9 multipv 1 score cp 40 nodes 900 pv d2d4")
        fixture.engine.onLine("info depth 9 multipv 2 score cp 35 nodes 910 pv e2e4")
        fixture.engine.onLine("bestmove e2e4")

        val completed = requireNotNull(response)
        assertThat(completed.depth == 8)
        assertThat(completed.variations.map { it.moves.first().value } == listOf("e2e4", "d2d4"))
    }
    suite.test("UCI engine does not treat no-depth MultiPV lines as coherent evidence") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info multipv 1 score cp 30 nodes 800 pv e2e4")
        fixture.engine.onLine("info multipv 2 score cp 20 nodes 810 pv d2d4")
        fixture.engine.onLine("bestmove e2e4")

        val completed = requireNotNull(response)
        assertThat(completed.depth == 0 && completed.nodes == 0L)
        assertThat(!completed.variations.single().evidenceAvailable)
    }
    suite.test("UCI engine does not mix repeated same-depth MultiPV reporting cycles") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW, multiPv = 2)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 8 multipv 1 score cp 30 nodes 800 pv e2e4")
        fixture.engine.onLine("info depth 8 multipv 2 score cp 20 nodes 810 pv d2d4")
        fixture.engine.onLine("info depth 8 multipv 1 score cp 35 nodes 820 pv c2c4")
        fixture.engine.onLine("bestmove c2c4")

        val completed = requireNotNull(response)
        assertThat(completed.depth == 8 && completed.nodes == 810L)
        assertThat(completed.bestMove == UciMove("e2e4"))
        assertThat(completed.variations.map { it.moves.first().value } == listOf("e2e4", "d2d4"))
        assertThat(completed.variations.all { it.evidenceAvailable })
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
    suite.test("UCI engine rejects a legacy patch that cannot configure the full rules contract") {
        val fixture = uciFixture(actualPatch = 1)
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture, patchVersion = 1, rulesOptionLines = emptyList())
        assertThat(error is UciEngineCompatibilityException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine rejects mismatched advertised patch identity") {
        val fixture = uciFixture()
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture, patchVersion = 3)
        assertThat(error is UciEngineCompatibilityException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine rejects an unrecognized future patch even when its identity matches") {
        val fixture = uciFixture(actualPatch = 3)
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture, patchVersion = 3)
        assertThat(error is UciEngineCompatibilityException)
        assertThat(fixture.engine.state == UciSessionState.IDLE)
    }
    suite.test("UCI engine configures every selectable Drawless rule policy on each request") {
        val presets = RulesContractV1.Preset.entries
        val deadPositions = DeadPositionPolicy.entries
        val fiftyMoves = FiftyMovePolicy.entries
        val bareKings = BareKingPolicy.entries

        for (preset in presets) {
            for (deadPosition in deadPositions) {
                for (fiftyMove in fiftyMoves) {
                    for (bareKing in bareKings) {
                        val fixture = uciFixture()
                        fixture.engine.start()
                        completeHandshake(fixture)
                        val base = when (preset) {
                            RulesContractV1.Preset.DRAWLESS -> RulesContractV1.drawless(
                                deadPosition = deadPosition,
                                fiftyMove = fiftyMove,
                            )
                            RulesContractV1.Preset.ESCAPE -> RulesContractV1.escape(
                                deadPosition = deadPosition,
                                fiftyMove = fiftyMove,
                            )
                        }
                        val rules = base.copy(bareKing = bareKing)
                        fixture.engine.analyze(productionRequest(rules = rules)) {}

                        val expectedVariant = preset.name.lowercase()
                        val expectedDeadPosition = when (deadPosition) {
                            DeadPositionPolicy.MATERIAL_VICTORY -> "material-victory"
                            DeadPositionPolicy.FINAL_CAPTURE_VICTORY -> "final-capture-victory"
                        }
                        val expectedFiftyMove = when (fiftyMove) {
                            FiftyMovePolicy.DISABLED -> "disabled"
                            FiftyMovePolicy.COMPLETING_PLAYER_LOSES -> "completing-player-loses"
                            FiftyMovePolicy.FORCED_MOVE_EXCEPTION -> "forced-move-exception"
                            FiftyMovePolicy.MATERIAL_VICTORY -> "material-victory"
                        }
                        val expectedBareKing = when (bareKing) {
                            BareKingPolicy.CONTINUE -> "continue"
                            BareKingPolicy.BARE_KING_LOSES -> "bare-king-loses"
                        }
                        val commands = fixture.transport.commands
                        assertThat("setoption name UCI_Variant value $expectedVariant" in commands)
                        assertThat(
                            "setoption name Drawless Dead Position value $expectedDeadPosition" in commands,
                        )
                        assertThat(
                            "setoption name Drawless Fifty Move value $expectedFiftyMove" in commands,
                        )
                        assertThat(
                            "setoption name Drawless Bare King value $expectedBareKing" in commands,
                        )
                        fixture.engine.close()
                    }
                }
            }
        }
    }
    suite.test("UCI engine starts a new search epoch when rules change within one game id") {
        val fixture = uciFixture()
        fixture.engine.start()
        completeHandshake(fixture)
        fixture.engine.analyze(productionRequest(id = "rules-before")) {}
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("info depth 1 score cp 0 nodes 1 pv e2e4")
        fixture.engine.onLine("bestmove e2e4")
        assertThat(fixture.transport.commands.count { it == "ucinewgame" } == 1)

        val changedRules = RulesContractV1.escape(
            deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
            fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION,
        ).copy(bareKing = BareKingPolicy.CONTINUE)
        val secondRequestStart = fixture.transport.commands.size
        fixture.engine.analyze(productionRequest(id = "rules-after", rules = changedRules)) {}

        assertThat(fixture.transport.commands.count { it == "ucinewgame" } == 2)
        val secondRequestCommands = fixture.transport.commands.drop(secondRequestStart)
        assertThat(
            secondRequestCommands.containsAll(
                listOf(
                    "setoption name UCI_Variant value escape",
                    "setoption name Drawless Dead Position value final-capture-victory",
                    "setoption name Drawless Fifty Move value forced-move-exception",
                    "setoption name Drawless Bare King value continue",
                    "ucinewgame",
                    "isready",
                ),
            ),
        )
        fixture.engine.close()
    }
    suite.test("UCI engine fails closed when a Drawless rules option is missing") {
        val fixture = uciFixture()
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture, rulesOptionLines = drawlessRulesOptionLines().dropLast(1))

        assertThat(error is UciEngineCompatibilityException)
        assertThat(error?.message?.contains("Drawless Bare King") == true)
        assertThat(fixture.transport.commands.none { it.startsWith("position ") || it.startsWith("go ") })
    }
    suite.test("UCI engine fails closed when Drawless rules choices or defaults drift") {
        val incompatible = listOf(
            drawlessRulesOptionLines()[0],
            "option name Drawless Fifty Move type combo default disabled " +
                "var disabled var completing-player-loses var material-victory",
            drawlessRulesOptionLines()[2],
        )
        val fixture = uciFixture()
        var error: Throwable? = null
        fixture.engine.analyze(productionRequest()) { error = it.exceptionOrNull() }
        completeHandshake(fixture, rulesOptionLines = incompatible)

        assertThat(error is UciEngineCompatibilityException)
        assertThat(error?.message?.contains("Drawless Fifty Move") == true)
        assertThat(fixture.transport.commands.none { it.startsWith("position ") || it.startsWith("go ") })
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
    suite.test("UCI engine marks a scoreless bestmove fallback as missing evidence") {
        val fixture = uciFixture()
        fixture.engine.start(); completeHandshake(fixture)
        var response: EngineResponse? = null
        fixture.engine.analyze(productionRequest(purpose = EnginePurpose.REVIEW)) {
            response = it.getOrThrow()
        }
        fixture.engine.onLine("readyok")
        fixture.engine.onLine("bestmove e2e4")

        val fallback = requireNotNull(response).variations.single()
        assertThat(!fallback.evidenceAvailable)
        assertThat(fallback.wdl == null && fallback.depth == null)
    }
    suite.test("named bot ladder matches the approved beginner progression") {
        val levels = BotDifficultyCatalog.namedLevels
        assertThat(
            levels.map { it.id to it.approximateElo } == listOf(
                "learner" to 550,
                "casual" to 800,
                "challenger" to 1_000,
                "club" to 1_300,
                "expert" to 1_675,
                "master" to 2_100,
                "grandmaster" to 2_550,
            ),
        )
        assertThat(levels.zipWithNext().all { (a, b) -> a.approximateElo < b.approximateElo })
        assertThat(BotDifficultyResolver.resolve(BotDifficultySelection.Named("club"), OfflineRating()).targetElo == 1_300)
        assertThat(BotDifficultyCatalog.adaptiveLevel().id == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID)
        assertThat(BotDifficultyCatalog.adaptiveLevel().approximateElo == 800)
        assertThat(BotDifficultyCatalog.adaptiveLevel(973).approximateElo == 973)
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
        assertThat(
            resolved.targetElo == 1_642 &&
                resolved.levelId == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID &&
                resolved.adaptive,
        )
    }
    suite.test("adaptive game configs require a frozen approximate Elo") {
        assertThrows<IllegalArgumentException> {
            GameConfig(
                gameId = "adaptive-invalid",
                initialFen = ChessPosition.START_FEN,
                rules = RulesContractV1.drawless(),
                mode = GameMode.CASUAL,
                timeControl = TimeControl.Untimed,
                humanSide = Side.WHITE,
                engineStrength = EngineStrength.SkillLevel(0),
                engineLimits = EngineLimits(moveTimeMillis = 350),
                opponentLevelId = BotDifficultyCatalog.ADAPTIVE_LEVEL_ID,
            )
        }
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
        assertThat(plan.requests.all { it.limits.multiPv == 3 })
    }
    suite.test("review planner rejects illegal histories") {
        assertThrows<IllegalArgumentException> {
            GameReviewPlanner.plan(
                "review-game", ChessPosition.START_FEN,
                listOf(UciMove("e2e5")), RulesContractV1.drawless(),
            )
        }
    }
    suite.test("player review roots retain their canonical position key across attempt ids") {
        val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5").map(::UciMove)
        val plan = GameReviewPlanner.playerPlan(
            gameId = "review-root-key",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            playerSide = Side.WHITE,
        )

        plan.roots.forEach { root ->
            val expectedPosition = ChessAdapter.replay(root.request.initialFen, root.request.moves)
            assertThat(root.key.positionFen == expectedPosition.fen())
            assertThat(root.key.positionId == root.request.positionId)
        }
        val original = plan.roots.last()
        val retried = original.copy(
            request = original.request.copy(requestId = "review-root-key-retry"),
        )
        assertThat(retried.key === original.key)
    }
    suite.test("player review analyzes only the selected side and retains canonical context") {
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        listOf(
            Side.WHITE to listOf(1, 3),
            Side.BLACK to listOf(2),
        ).forEach { (playerSide, expectedPlies) ->
            val engine = FakeReviewEngine()
            val streamed = mutableListOf<GameReviewMoveResult>()
            var completed: GameReviewResult? = null
            GameReviewRunner(engine).reviewPlayerMoves(
                gameId = "player-scope-${playerSide.name.lowercase()}",
                initialFen = ChessPosition.START_FEN,
                moves = moves,
                rules = RulesContractV1.drawless(),
                outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
                playerSide = playerSide,
                onMoveReviewed = { streamed += it },
                onResult = { completed = it.getOrThrow() },
            )

            expectedPlies.forEach { ply -> engine.respond(moves[ply - 1].value) }

            val result = requireNotNull(completed)
            assertThat(result.scope == GameReviewScope.PlayerMoves(playerSide))
            assertThat(result.gameMoves == moves)
            assertThat(result.moves.map { it.ply } == expectedPlies)
            assertThat(result.moves.all { it.mover == playerSide })
            assertThat(streamed.map { it.move.ply } == expectedPlies)
            assertThat(engine.requests.map { it.request.moves.size } == expectedPlies.map { it - 1 })
            val oppositeSummary = if (playerSide == Side.WHITE) result.summary.black else result.summary.white
            assertThat(oppositeSummary.gradedMoves == 0)
            assertThrows<IllegalArgumentException> { result.copy(scope = GameReviewScope.AllMoves) }
        }
    }
    suite.test("player review with no selected decisions completes without engine work") {
        val engine = FakeReviewEngine()
        val moves = listOf(UciMove("e2e4"))
        val progress = mutableListOf<GameReviewProgress>()
        val streamed = mutableListOf<GameReviewMoveResult>()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-scope-empty",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.BLACK,
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )

        val result = requireNotNull(completed)
        assertThat(engine.requests.isEmpty() && streamed.isEmpty())
        assertThat(result.gameMoves == moves && result.moves.isEmpty() && result.engine == null)
        assertThat(result.scope == GameReviewScope.PlayerMoves(Side.BLACK))
        assertThat(progress.single() == GameReviewProgress(0, 0, 0, 0))
    }
    suite.test("player review streams a natural terminal move without an adjacent helper") {
        val engine = FakeReviewEngine()
        val moves = listOf("f2f3", "e7e5", "g2g4", "d8h4").map(::UciMove)
        val streamed = mutableListOf<GameReviewMoveResult>()
        val progress = mutableListOf<GameReviewProgress>()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-terminal",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.CHECKMATE),
            playerSide = Side.BLACK,
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond("e7e5")
        engine.respond("b8c6")

        val finalMove = requireNotNull(completed).moves.last()
        assertThat(engine.requests.size == 2)
        assertThat(streamed.map { it.move.ply } == listOf(2, 4))
        assertThat(finalMove.playedMove == UciMove("d8h4") && finalMove.bestMove == UciMove("d8h4"))
        assertThat(finalMove.quality == ReviewMoveQuality.BEST && finalMove.expectedPointLoss == 0.0)
        assertThat(finalMove.evidence?.playedLine?.origin == ReviewLineOrigin.AUTHORITATIVE_TERMINAL)
        assertThat(progress.map { it.completedWorkUnits to it.completedMoves } == listOf(0 to 0, 1 to 1, 2 to 2))
        assertThat(progress.all { it.totalWorkUnits == 2 && it.totalMoves == 2 })
        assertThrows<IllegalArgumentException> {
            requireNotNull(completed).copy(outcome = GameOutcome(Side.WHITE, reason = EndReason.CHECKMATE))
        }
    }
    suite.test("player review adds one adjacent helper for an external final move") {
        listOf(EndReason.RESIGNATION, EndReason.TIMEOUT).forEach { reason ->
            val engine = FakeReviewEngine()
            val progress = mutableListOf<GameReviewProgress>()
            val streamed = mutableListOf<GameReviewMoveResult>()
            var completed: GameReviewResult? = null
            GameReviewRunner(engine).reviewPlayerMoves(
                gameId = "player-helper-${reason.name.lowercase()}",
                initialFen = ChessPosition.START_FEN,
                moves = listOf(UciMove("e2e4")),
                rules = RulesContractV1.drawless(),
                outcome = GameOutcome(Side.BLACK, reason = reason),
                playerSide = Side.WHITE,
                onMoveReviewed = { streamed += it },
                onProgress = { progress += it },
                onResult = { completed = it.getOrThrow() },
            )
            engine.respond("d2d4", centipawns = 200)
            assertThat(engine.requests.size == 2 && streamed.isEmpty())
            val helper = engine.requests[1].request
            assertThat(helper.moves == listOf(UciMove("e2e4")))
            assertThat(helper.purpose == EnginePurpose.REVIEW && helper.limits.multiPv == GAME_REVIEW_MULTI_PV)
            engine.respond("e7e5", centipawns = 200)

            val reviewed = requireNotNull(completed).moves.single()
            assertThat(streamed.map { it.move.ply } == listOf(1))
            assertThat(reviewed.playedEvaluation == ReviewEvaluation.Centipawns(-200))
            assertThat(reviewed.quality == ReviewMoveQuality.BLUNDER)
            assertThat(reviewed.evidence?.usedAdjacentFallback == true)
            assertThat(reviewed.evidence?.playedLine?.origin == ReviewLineOrigin.ADJACENT_POSITION)
            assertThat(
                progress.map {
                    listOf(it.completedWorkUnits, it.totalWorkUnits, it.completedMoves, it.totalMoves)
                } == listOf(listOf(0, 1, 0, 1), listOf(1, 2, 0, 1), listOf(2, 2, 1, 1)),
            )
        }
    }
    suite.test("player review grades a forced move without a helper or exact score") {
        val engine = FakeReviewEngine()
        val progress = mutableListOf<GameReviewProgress>()
        val streamed = mutableListOf<GameReviewMoveResult>()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-forced",
            initialFen = "r7/7p/8/8/8/2k5/7P/K7 w - - 0 1",
            moves = listOf(UciMove("a1b1")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond("a1b1", evidenceAvailable = false)

        val reviewed = requireNotNull(completed).moves.single()
        assertThat(engine.requests.size == 1 && streamed.size == 1)
        assertThat(reviewed.quality == ReviewMoveQuality.BEST && reviewed.expectedPointLoss == 0.0)
        assertThat(reviewed.evidence?.forced == true)
        assertThat(reviewed.evidence?.bestLine?.origin == ReviewLineOrigin.FORCED_LEGAL_MOVE)
        assertThat(progress.map { it.completedWorkUnits to it.completedMoves } == listOf(0 to 0, 1 to 1))
    }
    suite.test("player review streams each move only after all of its dynamic work completes") {
        val engine = FakeReviewEngine()
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        val streamed = mutableListOf<GameReviewMoveResult>()
        val progress = mutableListOf<GameReviewProgress>()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-streaming",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond("e2e4")
        assertThat(streamed.map { it.move.ply } == listOf(1))
        engine.respond("d2d4", centipawns = 200)
        assertThat(streamed.map { it.move.ply } == listOf(1))
        engine.respond("b8c6", centipawns = 200)

        assertThat(requireNotNull(completed).moves.map { it.ply } == listOf(1, 3))
        assertThat(streamed.map { it.move.ply } == listOf(1, 3))
        assertThat(engine.requests.map { it.request.moves.size } == listOf(0, 2, 3))
        assertThat(
            progress.map {
                listOf(it.completedWorkUnits, it.totalWorkUnits, it.completedMoves, it.totalMoves)
            } == listOf(
                listOf(0, 2, 0, 2),
                listOf(1, 2, 1, 2),
                listOf(2, 3, 1, 2),
                listOf(3, 3, 2, 2),
            ),
        )
        assertThat(progress.zipWithNext().all { (before, after) -> before.fraction <= after.fraction })
    }
    suite.test("player review cancellation and stale callbacks cannot publish duplicate evidence") {
        val cancelledEngine = FakeReviewEngine()
        val cancelledProgress = mutableListOf<GameReviewProgress>()
        var cancelledStreams = 0
        var cancelledResult = false
        val cancellation = GameReviewRunner(cancelledEngine).reviewPlayerMoves(
            gameId = "player-cancel-helper",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            onMoveReviewed = { cancelledStreams++ },
            onProgress = { cancelledProgress += it },
            onResult = { cancelledResult = true },
        )
        cancelledEngine.respond("d2d4")
        val cancelledHelper = cancelledEngine.requests[1]
        cancellation.cancel()
        assertThat(cancelledHelper.cancelled)
        cancelledHelper.callback(Result.success(reviewResponseFor(cancelledHelper.request, "e7e5")))
        assertThat(cancelledStreams == 0 && !cancelledResult)
        assertThat(cancelledProgress.size == 2)

        val staleEngine = FakeReviewEngine()
        val staleProgress = mutableListOf<GameReviewProgress>()
        var staleStreams = 0
        var staleResult = false
        GameReviewRunner(staleEngine).reviewPlayerMoves(
            gameId = "player-stale-root",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            onMoveReviewed = { staleStreams++ },
            onProgress = { staleProgress += it },
            onResult = { staleResult = it.isSuccess },
        )
        staleEngine.respond("d2d4")
        val staleRoot = staleEngine.requests[0]
        val progressBeforeStale = staleProgress.toList()
        staleRoot.callback(Result.success(reviewResponseFor(staleRoot.request, "d2d4")))
        assertThat(staleEngine.requests.size == 2 && staleProgress == progressBeforeStale)
        staleEngine.respond("e7e5")
        assertThat(staleStreams == 1 && staleResult)
    }
    suite.test("player review consumes only exact typed seeded roots") {
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        val rules = RulesContractV1.drawless()
        val seededRoot = GameReviewPlanner.playerRoot(
            requestId = "foreground-player-root",
            gameId = "player-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        val sameRootWithFreshRequestId = GameReviewPlanner.playerRoot(
            requestId = "foreground-player-root-retry",
            gameId = "player-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        assertThat(seededRoot.key == sameRootWithFreshRequestId.key)
        assertThrows<IllegalArgumentException> {
            seededRoot.seed(
                reviewResponseFor(seededRoot.request, "e2e4").copy(requestId = "wrong-seed-request"),
            )
        }
        val seed = seededRoot.seed(reviewResponseFor(seededRoot.request, "e2e4"))
        val engine = FakeReviewEngine()
        val streamed = mutableListOf<GameReviewMoveResult>()
        val progress = mutableListOf<GameReviewProgress>()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            seededRoots = listOf(seed),
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )
        assertThat(streamed.map { it.move.ply } == listOf(1))
        assertThat(engine.requests.single().request.moves.size == 2)
        engine.respond("d2d4")
        engine.respond("b8c6")

        val result = requireNotNull(completed)
        assertThat(result.moves.map { it.ply } == listOf(1, 3))
        assertThat(result.engine == seed.response.engine)
        assertThat(engine.requests.map { it.request.moves.size } == listOf(2, 3))
        assertThat(streamed.first().rootKey == seededRoot.key)
        val differentPositionRoot = GameReviewPlanner.playerRoot(
            requestId = "different-position",
            gameId = "player-seeded",
            initialFen = ChessPosition.START_FEN.replace(" 0 1", " 5 3"),
            moves = emptyList(),
            rules = rules,
        )
        assertThrows<IllegalArgumentException> {
            streamed.first().copy(rootKey = differentPositionRoot.key)
        }
        assertThat(
            progress.map {
                listOf(it.completedWorkUnits, it.totalWorkUnits, it.completedMoves, it.totalMoves)
            } == listOf(
                listOf(0, 2, 0, 2),
                listOf(1, 2, 1, 2),
                listOf(2, 3, 1, 2),
                listOf(3, 3, 2, 2),
            ),
        )

        val incompatibleRoot = GameReviewPlanner.playerRoot(
            requestId = "wrong-profile",
            gameId = "player-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
            moveTimeMillis = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS + 1,
        )
        val incompatibleSeed = incompatibleRoot.seed(reviewResponseFor(incompatibleRoot.request, "e2e4"))
        assertThrows<IllegalArgumentException> {
            GameReviewRunner(FakeReviewEngine()).reviewPlayerMoves(
                gameId = "player-seeded",
                initialFen = ChessPosition.START_FEN,
                moves = moves,
                rules = rules,
                outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
                playerSide = Side.WHITE,
                seededRoots = listOf(incompatibleSeed),
                onResult = {},
            )
        }
    }
    suite.test("game review rejects seeded and live evidence from a non-v2 engine") {
        val rules = RulesContractV1.drawless()
        val root = GameReviewPlanner.playerRoot(
            requestId = "legacy-seed",
            gameId = "review-patch-gate",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        val legacyIdentity = EngineIdentity("legacy-review-engine", "1", 1)
        assertThrows<IllegalArgumentException> {
            root.seed(reviewResponseFor(root.request, "e2e4", engine = legacyIdentity))
        }

        val playerEngine = FakeReviewEngine()
        var playerCompletion: Result<GameReviewResult>? = null
        GameReviewRunner(playerEngine).reviewPlayerMoves(
            gameId = "review-patch-gate-player",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            onResult = { playerCompletion = it },
        )
        playerEngine.requests.single().let { pending ->
            pending.responded = true
            pending.callback(
                Result.success(reviewResponseFor(pending.request, "e2e4", engine = legacyIdentity)),
            )
        }
        val playerFailure = requireNotNull(playerCompletion).exceptionOrNull()
        assertThat(playerFailure is IllegalArgumentException)
        assertThat(playerFailure?.message?.contains("patch 2") == true)

        val allMovesEngine = FakeReviewEngine()
        var allMovesCompletion: Result<GameReviewResult>? = null
        GameReviewRunner(allMovesEngine).review(
            gameId = "review-patch-gate-all",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { allMovesCompletion = it },
        )
        allMovesEngine.requests.single().let { pending ->
            pending.responded = true
            pending.callback(
                Result.success(reviewResponseFor(pending.request, "e2e4", engine = legacyIdentity)),
            )
        }
        val allMovesFailure = requireNotNull(allMovesCompletion).exceptionOrNull()
        assertThat(allMovesFailure is IllegalStateException)
        assertThat(allMovesFailure?.message?.contains("patch 2") == true)
    }
    suite.test("player review rejects engine identity drift between seeded and live roots") {
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        val rules = RulesContractV1.drawless()
        val seededRoot = GameReviewPlanner.playerRoot(
            requestId = "seeded-engine-root",
            gameId = "player-engine-drift",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        val seededEngine = EngineIdentity("seeded-review-engine", "1", 2)
        val seed = seededRoot.seed(
            reviewResponseFor(seededRoot.request, "e2e4", engine = seededEngine),
        )
        val engine = FakeReviewEngine()
        val streamed = mutableListOf<GameReviewMoveResult>()
        val progress = mutableListOf<GameReviewProgress>()
        var completion: Result<GameReviewResult>? = null

        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-engine-drift",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            seededRoots = listOf(seed),
            onMoveReviewed = { streamed += it },
            onProgress = { progress += it },
            onResult = { completion = it },
        )

        assertThat(streamed.map { it.move.ply } == listOf(1))
        val liveRoot = engine.requests.single()
        liveRoot.responded = true
        liveRoot.callback(
            Result.success(
                reviewResponseFor(
                    liveRoot.request,
                    "g1f3",
                    engine = EngineIdentity("live-review-engine", "2", 2),
                ),
            ),
        )

        val failure = requireNotNull(completion).exceptionOrNull()
        assertThat(failure is IllegalArgumentException)
        assertThat(failure?.message?.contains("different engine builds") == true)
        assertThat(streamed.map { it.move.ply } == listOf(1))
        assertThat(progress.map { it.completedWorkUnits } == listOf(0, 1))
    }
    suite.test("a cancelled player review cannot interfere with a retry") {
        val engine = FakeReviewEngine()
        val runner = GameReviewRunner(engine)
        val rules = RulesContractV1.drawless()
        val moves = listOf(UciMove("e2e4"))
        var firstCompleted = false
        val first = runner.reviewPlayerMoves(
            gameId = "player-cancel-retry",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            onResult = { firstCompleted = true },
        )
        val staleRoot = engine.requests.single()
        first.cancel()
        assertThat(staleRoot.cancelled)

        val retryProgress = mutableListOf<GameReviewProgress>()
        val retryStreams = mutableListOf<GameReviewMoveResult>()
        var retryResult: Result<GameReviewResult>? = null
        runner.reviewPlayerMoves(
            gameId = "player-cancel-retry",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            onMoveReviewed = { retryStreams += it },
            onProgress = { retryProgress += it },
            onResult = { retryResult = it },
        )
        val retryRoot = engine.requests.last()
        assertThat(staleRoot.request.requestId != retryRoot.request.requestId)
        val progressBeforeStale = retryProgress.toList()

        staleRoot.responded = true
        staleRoot.callback(Result.success(reviewResponseFor(staleRoot.request, "e2e4")))
        assertThat(!firstCompleted && retryResult == null)
        assertThat(engine.requests.size == 2 && !retryRoot.responded && !retryRoot.cancelled)
        assertThat(retryStreams.isEmpty() && retryProgress == progressBeforeStale)

        retryRoot.responded = true
        retryRoot.callback(Result.success(reviewResponseFor(retryRoot.request, "e2e4")))
        assertThat(requireNotNull(retryResult).isSuccess)
        assertThat(retryStreams.map { it.move.ply } == listOf(1))
        assertThat(retryProgress.last() == GameReviewProgress(1, 1, 1, 1))
    }
    suite.test("seeded player roots can complete without engine requests or request recursion") {
        val moves = listOf("e2e4", "e7e5", "g1f3").map(::UciMove)
        val rules = RulesContractV1.drawless()
        val plan = GameReviewPlanner.playerPlan(
            gameId = "player-all-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            playerSide = Side.WHITE,
        )
        val seeds = plan.roots.map { root ->
            root.seed(reviewResponseFor(root.request, moves[root.ply - 1].value))
        }
        val engine = FakeReviewEngine()
        val streamed = mutableListOf<GameReviewMoveResult>()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-all-seeded",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            seededRoots = seeds,
            onMoveReviewed = { streamed += it },
            onResult = { completed = it.getOrThrow() },
        )

        assertThat(engine.requests.isEmpty())
        assertThat(streamed.map { it.move.ply } == listOf(1, 3))
        assertThat(requireNotNull(completed).engine == seeds.first().response.engine)
    }
    suite.test("a seeded root still schedules its required adjacent helper") {
        val rules = RulesContractV1.drawless()
        val root = GameReviewPlanner.playerRoot(
            requestId = "seed-needs-helper",
            gameId = "player-seed-helper",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        val seed = root.seed(reviewResponseFor(root.request, "d2d4"))
        val engine = FakeReviewEngine()
        var streams = 0
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-seed-helper",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            seededRoots = listOf(seed),
            onMoveReviewed = { streams++ },
            onResult = { completed = it.getOrThrow() },
        )
        assertThat(engine.requests.single().request.moves == listOf(UciMove("e2e4")))
        assertThat(streams == 0)
        engine.respond("e7e5")
        assertThat(streams == 1 && requireNotNull(completed).moves.single().evidence?.usedAdjacentFallback == true)
    }
    suite.test("seeded root and adjacent evidence complete an off-MultiPV player move without engine work") {
        val rules = RulesContractV1.drawless()
        val root = GameReviewPlanner.playerRoot(
            requestId = "seed-complete-root",
            gameId = "player-complete-seed",
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = rules,
        )
        val rootSeed = root.seed(reviewResponseFor(root.request, "d2d4"))
        val adjacent = GameReviewPlanner.adjacentRoot(
            requestId = "seed-complete-adjacent",
            root = root,
            playedMove = UciMove("e2e4"),
        )
        val adjacentSeed = adjacent.seed(reviewResponseFor(adjacent.request, "e7e5"))
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-complete-seed",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = rules,
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            playerSide = Side.WHITE,
            seededRoots = listOf(rootSeed),
            seededAdjacentRoots = listOf(adjacentSeed),
            onResult = { completed = it.getOrThrow() },
        )

        assertThat(engine.requests.isEmpty())
        assertThat(requireNotNull(completed).moves.single().evidence?.usedAdjacentFallback == true)
    }
    suite.test("player review safely trampolines a synchronously completing engine") {
        val requests = mutableListOf<EngineRequest>()
        val synchronousEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                requests += request
                val bestMove = if (request.moves.isEmpty()) "d2d4" else "e7e5"
                onResult(Result.success(reviewResponseFor(request, bestMove)))
                return EngineCancellation {}
            }
        }
        val progress = mutableListOf<GameReviewProgress>()
        var completed: GameReviewResult? = null
        GameReviewRunner(synchronousEngine).reviewPlayerMoves(
            gameId = "player-synchronous",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            playerSide = Side.WHITE,
            onProgress = { progress += it },
            onResult = { completed = it.getOrThrow() },
        )

        assertThat(requests.map { it.moves.size } == listOf(0, 1))
        assertThat(requireNotNull(completed).moves.single().evidence?.usedAdjacentFallback == true)
        assertThat(progress.last() == GameReviewProgress(2, 2, 1, 1))
    }
    suite.test("player side selection follows a nonstandard FEN side to move") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).reviewPlayerMoves(
            gameId = "player-black-first",
            initialFen = "7k/8/8/8/8/8/r6P/1K6 b - - 0 1",
            moves = listOf(UciMove("a2a8")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.RESIGNATION),
            playerSide = Side.BLACK,
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond("a2a8")

        assertThat(engine.requests.single().request.moves.isEmpty())
        assertThat(requireNotNull(completed).moves.single().mover == Side.BLACK)
    }
    suite.test("review runner prefers same-root MultiPV and WDL evidence for the played move") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-same-root",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { completed = it.getOrThrow() },
        )
        engine.respondWithVariations(
            bestMove = "d2d4",
            variations = listOf(
                reviewVariation("d2d4", centipawns = 1_000, rank = 1, wdl = EngineWdl(600, 0, 400)),
                reviewVariation("e2e4", centipawns = -1_000, rank = 2, wdl = EngineWdl(590, 0, 410)),
            ),
        )
        // External completions still validate the final live-position response, but it must not
        // replace the same-root score for a played move already present in MultiPV.
        engine.respond(bestMove = "e7e5", centipawns = 2_000)

        val reviewed = requireNotNull(completed).moves.single()
        assertThat(reviewed.quality == ReviewMoveQuality.BEST)
        assertThat(reviewed.playedEvaluation == ReviewEvaluation.Centipawns(-1_000))
        assertThat(reviewed.expectedPointLoss?.let { kotlin.math.abs(it - 0.01) < 0.000_001 } == true)
        assertThat(reviewed.evidence?.playedLineRank == 2)
        assertThat(reviewed.evidence?.usedAdjacentFallback == false)
        assertThat(reviewed.evidence?.lines?.all { it.source == ReviewScoreSource.WDL } == true)
        assertThat(reviewed.evidence?.bestLine?.move == UciMove("d2d4"))
        assertThat(reviewed.evidence?.bestLine?.origin == ReviewLineOrigin.ROOT_MULTIPV)
        assertThat(reviewed.evidence?.playedLine?.move == UciMove("e2e4"))
        assertThat(reviewed.evidence?.playedLine?.origin == ReviewLineOrigin.ROOT_MULTIPV)
    }
    suite.test("review runner leaves non-exact and missing evidence ungraded") {
        val boundedEngine = FakeReviewEngine()
        var boundedResult: GameReviewResult? = null
        GameReviewRunner(boundedEngine).review(
            gameId = "review-bounded",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            onResult = { boundedResult = it.getOrThrow() },
        )
        boundedEngine.respond("e2e4", centipawns = 30, bound = EngineScoreBound.LOWER)
        boundedEngine.respond("e7e5", centipawns = 0)

        val bounded = requireNotNull(boundedResult).moves.single()
        assertThat(bounded.quality == null && bounded.expectedPointLoss == null)
        assertThat(bounded.evidence?.lines?.single()?.expectedPoints == null)

        val missingEngine = FakeReviewEngine()
        var missingResult: GameReviewResult? = null
        GameReviewRunner(missingEngine).review(
            gameId = "review-missing",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { missingResult = it.getOrThrow() },
        )
        missingEngine.respond("e2e4", centipawns = 0, evidenceAvailable = false)
        missingEngine.respond("e7e5", centipawns = 0)

        val missing = requireNotNull(missingResult).moves.single()
        assertThat(missing.bestEvaluation == null && missing.playedEvaluation == null)
        assertThat(missing.quality == null && missing.expectedPointLoss == null)
    }
    suite.test("a forced nonterminal move is Best without exact engine evidence") {
        val initialFen = "r7/7p/8/8/8/2k5/7P/K7 w - - 0 1"
        listOf(
            Triple("forced-bounded", EngineScoreBound.LOWER, true),
            Triple("forced-missing", EngineScoreBound.EXACT, false),
        ).forEach { (gameId, bound, evidenceAvailable) ->
            val engine = FakeReviewEngine()
            var completed: GameReviewResult? = null
            GameReviewRunner(engine).review(
                gameId = gameId,
                initialFen = initialFen,
                moves = listOf(UciMove("a1b1")),
                rules = RulesContractV1.drawless(),
                outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
                onResult = { completed = it.getOrThrow() },
            )
            engine.respond(
                bestMove = "a1b1",
                centipawns = 0,
                bound = bound,
                evidenceAvailable = evidenceAvailable,
            )
            engine.respond(bestMove = "h7h6", centipawns = 0)

            val reviewed = requireNotNull(completed).moves.single()
            assertThat(reviewed.quality == ReviewMoveQuality.BEST)
            assertThat(reviewed.expectedPointLoss == 0.0)
            assertThat(reviewed.evidence?.forced == true)
            assertThat(reviewed.evidence?.bestLine?.origin == ReviewLineOrigin.FORCED_LEGAL_MOVE)
            assertThat(reviewed.evidence?.playedLine?.origin == ReviewLineOrigin.FORCED_LEGAL_MOVE)
        }
    }
    suite.test("review runner rejects a primary PV that disagrees with bestmove") {
        val engine = FakeReviewEngine()
        var completed: Result<GameReviewResult>? = null
        GameReviewRunner(engine).review(
            gameId = "review-primary-mismatch",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { completed = it },
        )
        engine.respondWithVariations(
            bestMove = "d2d4",
            variations = listOf(reviewVariation("e2e4", centipawns = 20)),
        )
        engine.respond(bestMove = "e7e5", centipawns = 0)

        val error = requireNotNull(completed).exceptionOrNull()
        assertThat(error is IllegalArgumentException)
        assertThat(error?.message?.contains("does not match best move") == true)
    }
    suite.test("review runner rejects an illegal continuation in any PV rank") {
        val engine = FakeReviewEngine()
        var completed: Result<GameReviewResult>? = null
        GameReviewRunner(engine).review(
            gameId = "review-illegal-pv-continuation",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { completed = it },
        )
        engine.respondWithVariations(
            bestMove = "e2e4",
            variations = listOf(
                reviewVariation("e2e4", rank = 1, continuation = listOf("e7e5")),
                reviewVariation("d2d4", rank = 2, continuation = listOf("d7d5", "d4d5")),
            ),
        )
        engine.respond(bestMove = "e7e5", centipawns = 0)

        val message = requireNotNull(completed).exceptionOrNull()?.message.orEmpty()
        assertThat(message.contains("rank 2"))
        assertThat(message.contains("PV ply 3"))
        assertThat(message.contains("game ply 3"))
        assertThat(message.contains("d4d5"))
    }
    suite.test("review runner rejects an orthodox-legal PV continuation after app adjudication") {
        val engine = FakeReviewEngine()
        var completed: Result<GameReviewResult>? = null
        GameReviewRunner(engine).review(
            gameId = "review-pv-after-terminal",
            initialFen = "7k/8/8/8/8/8/r6P/1K6 b - - 0 1",
            moves = listOf(UciMove("a2a8")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.RESIGNATION),
            onResult = { completed = it },
        )
        engine.respondWithVariations(
            bestMove = "a2h2",
            variations = listOf(reviewVariation(
                move = "a2h2",
                continuation = listOf("b1a1"),
            )),
        )
        engine.respond(bestMove = "b1c1", centipawns = 0)

        val message = requireNotNull(completed).exceptionOrNull()?.message.orEmpty()
        assertThat(message.contains("rank 1"))
        assertThat(message.contains("continues after app terminal"))
        assertThat(message.contains("PV ply 2"))
        assertThat(message.contains("game ply 2"))
    }
    suite.test("review runner analyzes sequentially and inverts the adjacent root score") {
        val engine = FakeReviewEngine()
        val moves = listOf(UciMove("e2e4"))
        val progress = mutableListOf<GameReviewProgress>()
        var completed: Result<GameReviewResult>? = null

        GameReviewRunner(engine).review(
            gameId = "review-resignation",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onProgress = { progress += it },
            onResult = { completed = it },
        )

        assertThat(engine.requests.size == 1)
        assertThat(engine.requests.single().request.limits.moveTimeMillis == 350L)
        engine.respond(bestMove = "d2d4", centipawns = 200)
        assertThat(engine.requests.size == 2)
        assertThat(engine.requests[1].request.moves == moves)
        engine.respond(bestMove = "e7e5", centipawns = 200)

        val reviewed = requireNotNull(completed).getOrThrow().moves.single()
        assertThat(reviewed.bestEvaluation == ReviewEvaluation.Centipawns(200))
        assertThat(reviewed.playedEvaluation == ReviewEvaluation.Centipawns(-200))
        assertThat(reviewed.quality == ReviewMoveQuality.BLUNDER)
        assertThat(reviewed.evidence?.bestLine?.move == UciMove("d2d4"))
        assertThat(reviewed.evidence?.playedLine?.move == UciMove("e2e4"))
        assertThat(reviewed.evidence?.playedLine?.moves == listOf(UciMove("e2e4"), UciMove("e7e5")))
        assertThat(reviewed.evidence?.playedLine?.origin == ReviewLineOrigin.ADJACENT_POSITION)
        assertThat(progress.map { it.completedPositions } == listOf(0, 1, 2))
        assertThat(engine.requests.map { it.request.requestId }.distinct().size == 2)
        val firstRunIds = engine.requests.map { it.request.requestId }.toSet()
        assertThat(firstRunIds.all { it.matches(Regex(".+-run-[0-9]+")) })

        val retry = GameReviewRunner(engine).review(
            gameId = "review-resignation",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = {},
        )
        assertThat(engine.requests.last().request.requestId !in firstRunIds)
        retry.cancel()
    }
    suite.test("adjacent played evidence swaps lower and upper score bounds") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).review(
            gameId = "review-adjacent-bound",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond(bestMove = "d2d4", centipawns = 50)
        engine.respond(bestMove = "e7e5", centipawns = 25, bound = EngineScoreBound.LOWER)

        val playedLine = requireNotNull(completed).moves.single().evidence?.playedLine
        assertThat(playedLine?.bound == EngineScoreBound.UPPER)
        assertThat(playedLine?.source == ReviewScoreSource.CENTIPAWNS)
        assertThat(playedLine?.expectedPoints == null)
        assertThat(playedLine?.origin == ReviewLineOrigin.ADJACENT_POSITION)
    }
    suite.test("review runner inverts mate scores from the following position") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-mate-score",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.TIMEOUT),
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond(bestMove = "d2d4", centipawns = null, mateIn = 3)
        engine.respond(bestMove = "e7e5", centipawns = null, mateIn = 2)

        val reviewed = requireNotNull(completed).moves.single()
        assertThat(reviewed.bestEvaluation == ReviewEvaluation.Mate(3))
        assertThat(reviewed.playedEvaluation == ReviewEvaluation.Mate(-2))
        assertThat(reviewed.quality == ReviewMoveQuality.BLUNDER)
    }
    suite.test("review runner always marks the engine best move as best") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-best-match",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond(bestMove = "e2e4", centipawns = 12)
        engine.respond(bestMove = "e7e5", centipawns = 900)

        val reviewed = requireNotNull(completed).moves.single()
        assertThat(reviewed.quality == ReviewMoveQuality.BEST)
        assertThat(reviewed.expectedPointLoss == 0.0)
    }
    suite.test("review result versions evidence and summarizes each side independently") {
        val engine = FakeReviewEngine()
        var completed: GameReviewResult? = null
        GameReviewRunner(engine).review(
            gameId = "review-summary",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4"), UciMove("e7e5")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.RESIGNATION),
            onResult = { completed = it.getOrThrow() },
        )
        engine.respond(bestMove = "e2e4", centipawns = 20)
        engine.respondWithVariations(
            bestMove = "c7c5",
            variations = listOf(
                reviewVariation("c7c5", centipawns = 300, rank = 1),
                reviewVariation("e7e5", centipawns = -300, rank = 2),
            ),
        )
        engine.respond(bestMove = "g1f3", centipawns = 0)

        val result = requireNotNull(completed)
        assertThat(result.evidenceSchemaVersion == REVIEW_EVIDENCE_SCHEMA_VERSION)
        assertThat(REVIEW_ANALYSIS_VERSION == 2)
        assertThat(result.analysisVersion == REVIEW_ANALYSIS_VERSION)
        assertThat(result.gradingPolicyVersion == ReviewGradingPolicy.CURRENT.version)
        assertThat(
            result.ruleFidelity ==
                ReviewRuleFidelity.FULL_NATIVE_RULES_CONTRACT_V1_PATCH_V2,
        )
        assertThat(result.summary.white.gradedMoves == 1)
        assertThat(result.summary.white.qualityCounts.getValue(ReviewMoveQuality.BEST) == 1)
        assertThat(result.summary.white.meanExpectedPointLoss == 0.0)
        assertThat(result.summary.black.gradedMoves == 1)
        assertThat(result.summary.black.qualityCounts.getValue(ReviewMoveQuality.BLUNDER) == 1)
        assertThat(result.summary.black.meanExpectedPointLoss?.let { it > 0.22 } == true)
        assertThat(result.moves.all { move ->
            requireNotNull(move.evidence).let { evidence ->
                evidence.evidenceSchemaVersion == REVIEW_EVIDENCE_SCHEMA_VERSION &&
                    evidence.analysisVersion == REVIEW_ANALYSIS_VERSION &&
                    evidence.gradingPolicyVersion == ReviewGradingPolicy.CURRENT.version
            }
        })
        assertThrows<IllegalArgumentException> {
            result.copy(evidenceSchemaVersion = REVIEW_EVIDENCE_SCHEMA_VERSION + 1)
        }
        assertThrows<IllegalArgumentException> {
            result.copy(summary = GameReviewSummary.from(emptyList()))
        }
        assertThrows<IllegalArgumentException> {
            result.copy(analysisVersion = 1)
        }
        val staleV1Evidence = requireNotNull(result.moves.first().evidence).copy(
            analysisVersion = 1,
        )
        assertThrows<IllegalArgumentException> {
            result.copy(moves = listOf(result.moves.first().copy(evidence = staleV1Evidence)) + result.moves.drop(1))
        }
    }
    suite.test("review runner uses the authoritative avoidable repetition result without analyzing terminal") {
        val engine = FakeReviewEngine()
        val moves = listOf(
            "g1f3", "g8f6", "f3g1", "f6g8",
            "g1f3", "g8f6", "f3g1", "f6g8",
        ).map(::UciMove)
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-repetition",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.REPETITION),
            onResult = { completed = it.getOrThrow() },
        )
        moves.forEachIndexed { index, move ->
            assertThat(engine.requests.size == index + 1)
            engine.respond(
                bestMove = if (index == moves.lastIndex) "f6h5" else move.value,
                centipawns = 0,
            )
        }

        assertThat(engine.requests.size == moves.size)
        val finalMove = requireNotNull(completed).moves.last()
        assertThat(finalMove.playedEvaluation == ReviewEvaluation.Terminal(Side.WHITE))
        assertThat(finalMove.quality == ReviewMoveQuality.BLUNDER)
        assertThat(finalMove.bestMove == UciMove("f6h5"))
    }
    suite.test("review runner does not call an immediate loss a blunder when every line is lost") {
        val engine = FakeReviewEngine()
        val moves = listOf(
            "g1f3", "g8f6", "f3g1", "f6g8",
            "g1f3", "g8f6", "f3g1", "f6g8",
        ).map(::UciMove)
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-forced-loss",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.REPETITION),
            onResult = { completed = it.getOrThrow() },
        )
        moves.forEachIndexed { index, move ->
            engine.respond(
                bestMove = if (index == moves.lastIndex) "f6h5" else move.value,
                centipawns = if (index == moves.lastIndex) null else 0,
                mateIn = if (index == moves.lastIndex) -3 else null,
            )
        }

        val finalMove = requireNotNull(completed).moves.last()
        assertThat(finalMove.quality == ReviewMoveQuality.BEST)
        assertThat(finalMove.bestMove == finalMove.playedMove)
        assertThat(finalMove.expectedPointLoss == 0.0)
    }
    suite.test("large negative centipawn score does not prove an avoidable terminal loss was forced") {
        val engine = FakeReviewEngine()
        val moves = listOf(
            "g1f3", "g8f6", "f3g1", "f6g8",
            "g1f3", "g8f6", "f3g1", "f6g8",
        ).map(::UciMove)
        var completed: GameReviewResult? = null

        GameReviewRunner(engine).review(
            gameId = "review-cp-is-not-proof",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.WHITE, reason = EndReason.REPETITION),
            onResult = { completed = it.getOrThrow() },
        )
        moves.forEachIndexed { index, move ->
            engine.respond(
                bestMove = if (index == moves.lastIndex) "f6h5" else move.value,
                centipawns = if (index == moves.lastIndex) -100_000 else 0,
            )
        }

        val finalMove = requireNotNull(completed).moves.last()
        assertThat(finalMove.quality == ReviewMoveQuality.BLUNDER)
        assertThat(finalMove.bestMove == UciMove("f6h5"))
    }
    suite.test("review retries use fresh request identities") {
        val engine = FakeReviewEngine()
        val runner = GameReviewRunner(engine)
        val outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION)
        val moves = listOf(UciMove("e2e4"))

        val first = runner.review(
            gameId = "review-retry",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = outcome,
            onResult = {},
        )
        first.cancel()
        val second = runner.review(
            gameId = "review-retry",
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            rules = RulesContractV1.drawless(),
            outcome = outcome,
            onResult = {},
        )

        assertThat(engine.requests.size == 2)
        assertThat(engine.requests[0].request.requestId != engine.requests[1].request.requestId)
        second.cancel()
    }
    suite.test("review cancellation stops the active request and suppresses completion") {
        val engine = FakeReviewEngine()
        var completed = false
        val cancellation = GameReviewRunner(engine).review(
            gameId = "review-cancel",
            initialFen = ChessPosition.START_FEN,
            moves = listOf(UciMove("e2e4")),
            rules = RulesContractV1.drawless(),
            outcome = GameOutcome(Side.BLACK, reason = EndReason.RESIGNATION),
            onResult = { completed = true },
        )

        cancellation.cancel()
        assertThat(engine.requests.single().cancelled)
        engine.respond(bestMove = "e2e4", centipawns = 0)
        assertThat(!completed)
        assertThat(engine.requests.size == 1)
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
