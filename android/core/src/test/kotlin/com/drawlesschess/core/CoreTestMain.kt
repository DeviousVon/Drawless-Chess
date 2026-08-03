package com.drawlesschess.core

import kotlin.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.DeadPositionDetector
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Piece
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.chess.SanNotation
import com.drawlesschess.core.coordinator.*
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.presentation.*

internal class TestSuite {
    private var passed = 0
    private val failures = mutableListOf<String>()

    fun test(name: String, block: () -> Unit) {
        try {
            block()
            passed++
        } catch (error: Throwable) {
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }
    }

    fun finish() {
        if (failures.isNotEmpty()) {
            error("FAILED ${failures.size} tests\n${failures.joinToString("\n")}")
        }
        println("PASSED $passed Kotlin core tests")
    }
}

internal fun assertThat(value: Boolean, message: String = "assertion failed") {
    if (!value) error(message)
}

internal inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return
        throw error
    }
    error("Expected ${T::class.simpleName}")
}

private fun registerJniFairyEnginePortTestsIfPresent(suite: TestSuite) {
    try {
        val testFile = Class.forName("com.drawlesschess.core.JniFairyEnginePortTestsKt")
        val register = testFile.getDeclaredMethod(
            "registerJniFairyEnginePortTests",
            TestSuite::class.java,
        )
        register.isAccessible = true
        register.invoke(null, suite)
    } catch (_: ClassNotFoundException) {
        // The core Gradle module cannot depend on the engine module. The repository's
        // combined Kotlin harness includes the engine test source and runs this suite.
    }
}

private val drawless = RulesContractV1.drawless()
private val adjudicator = DrawlessAdjudicator()

private fun facts(
    mover: Side = Side.WHITE,
    legalMoves: Int = 1,
    inCheck: Boolean = false,
    occurrences: Int = 1,
    repetitionAvoiding: Int = 1,
    halfmove: Int = 0,
    fiftyAvoiding: Int = 1,
    dead: Boolean = false,
    capture: Boolean = false,
    whiteMaterial: Int = 0,
    blackMaterial: Int = 0,
    lastCaptureBy: Side? = null,
) = PositionFacts(
    mover, legalMoves, inCheck, occurrences, repetitionAvoiding, halfmove,
    fiftyAvoiding, dead, capture, MaterialScore(whiteMaterial, blackMaterial), lastCaptureBy,
)

private fun alternative(move: String, key: String, halfmove: Int = 0) = MoveAlternative(
    UciMove(move), PositionKey(key), halfmove,
)

private fun transition(
    move: String,
    mover: Side,
    key: String,
    alternatives: List<MoveAlternative> = listOf(alternative(move, key)),
    legalMovesAfter: Int = 1,
    inCheck: Boolean = false,
    halfmove: Int = 0,
    dead: Boolean = false,
    capture: Boolean = false,
    material: MaterialScore = MaterialScore(0, 0),
) = MoveTransition(
    move = UciMove(move),
    mover = mover,
    resultingPositionKey = PositionKey(key),
    legalMovesAfter = legalMovesAfter,
    sideToMoveInCheck = inCheck,
    legalAlternativesBeforeMove = alternatives,
    halfmoveClockAfter = halfmove,
    deadPositionAfter = dead,
    moveWasCapture = capture,
    materialAfter = material,
)

private class FakeCoordinatorTime(
    var monotonic: Long = 1_000,
    var epoch: Long = 1_700_000_000_000,
) : CoordinatorTimeSource {
    override fun now() = TimeReading(monotonic, epoch)
    fun advance(millis: Long) { monotonic += millis; epoch += millis }
}

private class FakeCheckpointSink : CheckpointSink {
    val saved = mutableListOf<CoordinatorCheckpoint>()
    override fun persist(checkpoint: CoordinatorCheckpoint) { saved += checkpoint }
}

private class FakeCoordinatorIds : CoordinatorIdSource {
    private var next = 0
    override fun nextId(): String = "request-${++next}"
}

private class FakeChessEngine : ChessEngine {
    data class Pending(
        val request: EngineRequest,
        val callback: (Result<EngineResponse>) -> Unit,
        var cancelled: Boolean = false,
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

    fun respond(pending: Pending = requests.last(), move: String) {
        pending.callback(Result.success(engineResponse(pending.request, move)))
    }

    fun fail(pending: Pending = requests.last(), message: String = "engine failed") {
        pending.callback(Result.failure(IllegalStateException(message)))
    }
}

private fun engineResponse(request: EngineRequest, move: String) = EngineResponse(
    requestId = request.requestId,
    gameId = request.gameId,
    positionId = request.positionId,
    bestMove = UciMove(move),
    ponderMove = null,
    depth = 2,
    nodes = 20,
    variations = listOf(PrincipalVariation(10, null, listOf(UciMove(move)))),
    engine = EngineIdentity("fairy-stockfish", "test", 2),
)

private fun coordinatorConfig(
    mode: GameMode = GameMode.CASUAL,
    timeControl: TimeControl = TimeControl.Untimed,
    humanSide: Side = Side.WHITE,
    initialFen: String = ChessPosition.START_FEN,
    rules: RulesContractV1 = drawless,
) = GameConfig(
    gameId = "coordinator-game",
    initialFen = initialFen,
    rules = rules,
    mode = mode,
    timeControl = timeControl,
    humanSide = humanSide,
    engineStrength = EngineStrength.SkillLevel(5),
    engineLimits = EngineLimits(100),
)

private data class CoordinatorFixture(
    val coordinator: GameCoordinator,
    val engine: FakeChessEngine,
    val sink: FakeCheckpointSink,
    val time: FakeCoordinatorTime,
)

private fun coordinatorFixture(
    config: GameConfig = coordinatorConfig(),
    time: FakeCoordinatorTime = FakeCoordinatorTime(),
    botMovePresentationDelayMillis: Long = 0,
    initialAssistance: AssistanceCounts = AssistanceCounts(),
): CoordinatorFixture {
    val engine = FakeChessEngine()
    val sink = FakeCheckpointSink()
    val coordinator = GameCoordinator.newGame(
        config,
        engine,
        sink,
        time,
        FakeCoordinatorIds(),
        botMovePresentationDelayMillis = botMovePresentationDelayMillis,
        initialAssistance = initialAssistance,
    )
    coordinator.start()
    return CoordinatorFixture(coordinator, engine, sink, time)
}

fun main() {
    val suite = TestSuite()
    registerRulesAcceptanceFixtureTests(suite)

    suite.test("ordinary position continues") {
        assertThat(adjudicator.adjudicate(drawless, facts()) == null)
    }
    suite.test("rules presets use material adjudication at the 50-move limit by default") {
        assertThat(RulesContractV1.drawless().fiftyMove == FiftyMovePolicy.MATERIAL_VICTORY)
        assertThat(RulesContractV1.escape().fiftyMove == FiftyMovePolicy.MATERIAL_VICTORY)
    }
    suite.test("checkmate awards mover") {
        assertThat(adjudicator.adjudicate(drawless, facts(mover = Side.BLACK, legalMoves = 0, inCheck = true))?.winner == Side.BLACK)
    }
    suite.test("drawless stalemate defeats trapped player") {
        assertThat(adjudicator.adjudicate(drawless, facts(legalMoves = 0))?.winner == Side.WHITE)
    }
    suite.test("escape stalemate rewards trapped player") {
        assertThat(adjudicator.adjudicate(RulesContractV1.escape(), facts(legalMoves = 0))?.winner == Side.BLACK)
    }
    suite.test("voluntary third repetition defeats mover") {
        assertThat(adjudicator.adjudicate(drawless, facts(occurrences = 3, repetitionAvoiding = 2))?.winner == Side.BLACK)
    }
    suite.test("forced third repetition defeats forcing opponent") {
        assertThat(adjudicator.adjudicate(drawless, facts(occurrences = 3, repetitionAvoiding = 0))?.winner == Side.WHITE)
    }
    suite.test("material victory chooses greater material") {
        assertThat(
            adjudicator.adjudicate(
                drawless,
                facts(dead = true, capture = true, whiteMaterial = 3, blackMaterial = 1),
            )?.winner == Side.WHITE,
        )
    }
    suite.test("equal dead material rewards mover") {
        assertThat(adjudicator.adjudicate(drawless, facts(mover = Side.BLACK, dead = true, capture = true))?.winner == Side.BLACK)
    }
    suite.test("bare king loses immediately to remaining material") {
        val outcome = adjudicator.adjudicate(
            drawless,
            facts(mover = Side.BLACK, whiteMaterial = 0, blackMaterial = 5),
        )
        assertThat(outcome?.winner == Side.BLACK)
        assertThat(outcome?.reason == EndReason.BARE_KING)
    }
    suite.test("legacy bare-king policy can continue an old saved game") {
        val legacyRules = RulesContractV1.drawless().copy(bareKing = BareKingPolicy.CONTINUE)
        assertThat(adjudicator.adjudicate(
            legacyRules,
            facts(whiteMaterial = 0, blackMaterial = 5),
        ) == null)
    }
    suite.test("final capture rewards capturer") {
        val rules = RulesContractV1.drawless(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY)
        assertThat(
            adjudicator.adjudicate(
                rules,
                facts(dead = true, capture = true, whiteMaterial = 1, blackMaterial = 9),
            )?.winner == Side.WHITE,
        )
    }
    suite.test("final capture policy awards a non-capturing dead-position creator") {
        val rules = RulesContractV1.drawless(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY)
        val outcome = adjudicator.adjudicate(rules, facts(dead = true))
        assertThat(outcome == GameOutcome(Side.WHITE, reason = EndReason.DEAD_POSITION_FINAL_CAPTURE))
    }
    suite.test("50-move completion defeats mover") {
        val rules = RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES)
        assertThat(adjudicator.adjudicate(rules, facts(halfmove = 100))?.winner == Side.BLACK)
    }
    suite.test("disabled 50-move rule continues") {
        val rules = RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.DISABLED)
        assertThat(adjudicator.adjudicate(rules, facts(halfmove = 100)) == null)
    }
    suite.test("forced 50-move exception defeats forcing opponent") {
        val rules = RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION)
        assertThat(adjudicator.adjudicate(rules, facts(halfmove = 100, fiftyAvoiding = 0))?.winner == Side.WHITE)
    }
    suite.test("50-move material adjudication chooses greater material") {
        val outcome = adjudicator.adjudicate(
            drawless,
            facts(halfmove = 100, whiteMaterial = 8, blackMaterial = 5),
        )
        assertThat(outcome?.winner == Side.WHITE)
        assertThat(outcome?.reason == EndReason.FIFTY_MOVE_LIMIT)
    }
    suite.test("50-move material tie rewards the last capturing side") {
        val outcome = adjudicator.adjudicate(
            drawless,
            facts(
                mover = Side.WHITE,
                halfmove = 100,
                whiteMaterial = 5,
                blackMaterial = 5,
                lastCaptureBy = Side.BLACK,
            ),
        )
        assertThat(outcome?.winner == Side.BLACK)
    }
    suite.test("50-move material tie without a capture defeats an avoidable completing mover") {
        val outcome = adjudicator.adjudicate(
            drawless,
            facts(mover = Side.WHITE, halfmove = 100, fiftyAvoiding = 1),
        )
        assertThat(outcome?.winner == Side.BLACK)
    }
    suite.test("50-move material tie without a capture rewards a forced completing mover") {
        val outcome = adjudicator.adjudicate(
            drawless,
            facts(mover = Side.WHITE, halfmove = 100, fiftyAvoiding = 0),
        )
        assertThat(outcome?.winner == Side.WHITE)
    }
    suite.test("checkmate outranks repetition") {
        assertThat(adjudicator.adjudicate(drawless, facts(legalMoves = 0, inCheck = true, occurrences = 3))?.reason == EndReason.CHECKMATE)
    }
    suite.test("repetition outranks dead position") {
        assertThat(adjudicator.adjudicate(drawless, facts(occurrences = 3, dead = true, capture = true))?.reason == EndReason.REPETITION)
    }
    suite.test("rules v1 rejects preset mismatch") {
        assertThrows<IllegalArgumentException> {
            RulesContractV1(RulesContractV1.Preset.ESCAPE, StalematePolicy.TRAPPED_PLAYER_LOSES,
                DeadPositionPolicy.MATERIAL_VICTORY, FiftyMovePolicy.DISABLED)
        }
    }
    suite.test("rules v1 rejects changed repetition threshold") {
        assertThrows<IllegalArgumentException> { drawless.copy(repetitionThreshold = 4) }
    }
    suite.test("UCI move validation rejects notation drift") {
        assertThrows<IllegalArgumentException> { UciMove("knight-f3") }
    }
    suite.test("history counts immutable occurrences") {
        val start = PositionHistory.startingAt(PositionKey("A"))
        val next = start.record(PositionKey("B")).record(PositionKey("A"))
        assertThat(start.occurrences(PositionKey("A")) == 1)
        assertThat(next.occurrences(PositionKey("A")) == 2 && next.size == 3)
    }
    suite.test("session derives avoidable repetition loss from alternatives") {
        var game = GameSession.newGame("g1", drawless, PositionKey("A"))
        game = game.apply(transition("g1f3", Side.WHITE, "B"))
        game = game.apply(transition("g8f6", Side.BLACK, "A"))
        game = game.apply(transition("f3g1", Side.WHITE, "B"))
        game = game.apply(transition(
            move = "f6g8", mover = Side.BLACK, key = "A",
            alternatives = listOf(alternative("f6g8", "A"), alternative("f6h5", "C")),
        ))
        assertThat(game.outcome?.reason == EndReason.REPETITION)
        assertThat(game.outcome?.winner == Side.WHITE)
        assertThat(game.adjudicationFacts?.repetitionAvoidingAlternativesBeforeMove == 1)
        assertThat(game.adjudicationFacts?.mover == Side.BLACK)
    }
    suite.test("session derives forced repetition win for mover") {
        var game = GameSession.newGame("g2", drawless, PositionKey("A"))
        game = game.apply(transition("g1f3", Side.WHITE, "B"))
        game = game.apply(transition("g8f6", Side.BLACK, "A"))
        game = game.apply(transition("f3g1", Side.WHITE, "B"))
        game = game.apply(transition(
            move = "f6g8", mover = Side.BLACK, key = "A",
            alternatives = listOf(alternative("f6g8", "A")),
        ))
        assertThat(game.outcome?.winner == Side.BLACK)
        assertThat(game.adjudicationFacts?.repetitionAvoidingAlternativesBeforeMove == 0)
    }
    suite.test("session retains terminal material and 50-move explanation facts") {
        val materialGame = GameSession.newGame("material-facts", drawless, PositionKey("A"))
            .apply(transition(
                move = "g1f3",
                mover = Side.WHITE,
                key = "B",
                dead = true,
                material = MaterialScore(3, 3),
            ))
        assertThat(materialGame.outcome?.reason == EndReason.DEAD_POSITION_MATERIAL)
        assertThat(materialGame.adjudicationFacts?.materialAfter == MaterialScore(3, 3))

        val fiftyRules = RulesContractV1.drawless(
            fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION,
        )
        val fiftyGame = GameSession.newGame("fifty-facts", fiftyRules, PositionKey("A"))
            .apply(transition(
                move = "g1f3",
                mover = Side.WHITE,
                key = "B",
                alternatives = listOf(
                    alternative("g1f3", "B", halfmove = 100),
                    alternative("g1h3", "C", halfmove = 0),
                ),
                halfmove = 100,
            ))
        assertThat(fiftyGame.outcome?.reason == EndReason.FIFTY_MOVE_LIMIT)
        assertThat(fiftyGame.adjudicationFacts?.fiftyMoveAvoidingAlternativesBeforeMove == 1)
    }
    suite.test("session carries the last capturing side across quiet moves") {
        val legacyRules = drawless.copy(
            bareKing = BareKingPolicy.CONTINUE,
            fiftyMove = FiftyMovePolicy.DISABLED,
        )
        var game = GameSession.newGame("last-capture", legacyRules, PositionKey("A"))
        game = game.apply(transition(
            move = "a1a2",
            mover = Side.WHITE,
            key = "B",
            capture = true,
            material = MaterialScore(5, 5),
        ))
        game = game.apply(transition(
            move = "a8a7",
            mover = Side.BLACK,
            key = "C",
            material = MaterialScore(5, 5),
        ))
        assertThat(game.lastCaptureBy == Side.WHITE)
    }
    suite.test("session rejects wrong mover") {
        val game = GameSession.newGame("g3", drawless, PositionKey("A"))
        assertThrows<IllegalArgumentException> {
            game.apply(transition("g8f6", Side.BLACK, "B"))
        }
    }
    suite.test("transition rejects selected move absent from alternatives") {
        assertThrows<IllegalArgumentException> {
            transition("g1f3", Side.WHITE, "B", alternatives = listOf(alternative("b1c3", "C")))
        }
    }
    suite.test("terminal session rejects additional moves") {
        val game = GameSession.newGame("g4", drawless, PositionKey("A"))
            .apply(transition("g1f3", Side.WHITE, "B", legalMovesAfter = 0, inCheck = true))
        assertThrows<IllegalStateException> {
            game.apply(transition("g8f6", Side.BLACK, "C"))
        }
    }
    suite.test("position ID changes on each committed move") {
        val game = GameSession.newGame("g5", drawless, PositionKey("A"))
        val next = game.apply(transition("g1f3", Side.WHITE, "B"))
        assertThat(game.positionId != next.positionId)
        assertThat(next.adjudicationFacts == null)
    }
    suite.test("saved rated game rejects assistance") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g6", Instant.fromEpochMilliseconds(0), GameMode.RATED, "start-fen", drawless,
                TimeControl.Untimed, emptyList(), EngineIdentity("fairy", "build", 0),
                assistance = AssistanceCounts(hints = 1),
            )
        }
    }
    suite.test("saved casual game accepts assistance") {
        val saved = SavedGameV1(
            "g7", Instant.fromEpochMilliseconds(0), GameMode.CASUAL, "start-fen", drawless,
            TimeControl.Untimed, emptyList(), EngineIdentity("fairy", "build", 0),
            assistance = AssistanceCounts(undos = 1),
        )
        assertThat(saved.schemaVersion == 1)
    }
    suite.test("game scoring v1 applies an explicit assistance breakdown only to wins") {
        val assisted = AssistanceCounts(hints = 1, undos = 1, pauses = 1, threatIndication = true)
        assertThat(assisted.wasUsed)
        val timedWin = GameScoring.forResult(true, assisted, TimeControl.Clock(60_000))
        assertThat(timedWin == GameScore(
            points = 70,
            maximumPoints = 100,
            threatIndicationPenalty = 5,
            hintPenalty = 10,
            undoPenalty = 10,
            timedPausePenalty = 5,
            scoringVersion = 1,
        ))
        assertThat(timedWin.totalPenalty == 30)
        assertThat(GameScoring.forResult(true, assisted, TimeControl.Untimed).points == 75)
        assertThat(GameScoring.forResult(false, assisted) == GameScore(0, 100, 0))
        assertThat(GameScoring.forResult(true, AssistanceCounts()) == GameScore(100, 100, 0))
    }
    suite.test("game scoring clamps very large assistance counts without overflow") {
        val score = GameScoring.forResult(
            playerWon = true,
            assistance = AssistanceCounts(
                hints = Int.MAX_VALUE,
                undos = Int.MAX_VALUE,
                pauses = Int.MAX_VALUE,
                threatIndication = true,
            ),
            timeControl = TimeControl.Clock(60_000),
        )
        assertThat(score.points == 0)
        assertThat(score.hintPenalty == 100)
        assertThat(score.undoPenalty == 100)
        assertThat(score.timedPausePenalty == 100)
        assertThat(score.threatIndicationPenalty == 5)
        assertThat(score.totalPenalty == 100)
    }
    suite.test("untimed save rejects clock snapshots") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g8", Instant.fromEpochMilliseconds(0), GameMode.CASUAL, "start-fen", drawless,
                TimeControl.Untimed,
                listOf(SavedMoveV1(UciMove("g1f3"), whiteRemainingMillis = 5)),
                EngineIdentity("fairy", "build", 0),
            )
        }
    }
    suite.test("saved result cannot exceed replay history") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g9", Instant.fromEpochMilliseconds(0), GameMode.CASUAL, "start-fen", drawless,
                TimeControl.Untimed, emptyList(), EngineIdentity("fairy", "build", 0),
                result = SavedResultV1(Side.WHITE, EndReason.CHECKMATE, 1),
            )
        }
    }
    suite.test("engine response rejects stale position identity") {
        val request = EngineRequest(
            "r1", "g10", "p1", "start-fen", emptyList(), drawless,
            EngineStrength.SkillLevel(10), EngineLimits(100),
        )
        val response = EngineResponse(
            "r1", "g10", "p0", UciMove("g1f3"), null, 1, 10,
            listOf(PrincipalVariation(10, null, listOf(UciMove("g1f3")))),
            EngineIdentity("fairy", "build", 0),
        )
        assertThat(!response.matches(request))
    }
    suite.test("starting FEN round trips exactly") {
        assertThat(ChessPosition.starting().fen() == ChessPosition.START_FEN)
    }
    suite.test("invalid FEN rank is rejected") {
        assertThrows<IllegalArgumentException> {
            ChessPosition.fromFen("8/8/8/8/8/8/8/3K3 w - - 0 1")
        }
    }
    suite.test("starting position has 20 legal moves") {
        assertThat(ChessRules.legalMoves(ChessPosition.starting()).size == 20)
    }
    suite.test("starting-position perft depth 2 is 400") {
        assertThat(ChessAdapter.perft(ChessPosition.starting(), 2) == 400L)
    }
    suite.test("starting-position perft depth 3 is 8902") {
        assertThat(ChessAdapter.perft(ChessPosition.starting(), 3) == 8_902L)
    }
    suite.test("Kiwipete perft depth 1 is 48") {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )
        val count = ChessAdapter.perft(position, 1)
        assertThat(count == 48L, "expected 48, got $count")
    }
    suite.test("Kiwipete perft depth 2 is 2039") {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )
        val count = ChessAdapter.perft(position, 2)
        assertThat(count == 2_039L, "expected 2039, got $count")
    }
    suite.test("Kiwipete perft depth 3 is 97862") {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )
        val count = ChessAdapter.perft(position, 3)
        assertThat(count == 97_862L, "expected 97862, got $count")
    }
    suite.test("perft position 3 depth 3 is 2812") {
        val position = ChessPosition.fromFen(
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        )
        val count = ChessAdapter.perft(position, 3)
        assertThat(count == 2_812L, "expected 2812, got $count")
    }
    suite.test("perft position 4 depth 2 is 264") {
        val position = ChessPosition.fromFen(
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        )
        val count = ChessAdapter.perft(position, 2)
        assertThat(count == 264L, "expected 264, got $count")
    }
    suite.test("perft position 5 depth 2 is 1486") {
        val position = ChessPosition.fromFen(
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        )
        val count = ChessAdapter.perft(position, 2)
        assertThat(count == 1_486L, "expected 1486, got $count")
    }
    suite.test("perft position 6 depth 2 is 2079") {
        val position = ChessPosition.fromFen(
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        )
        val count = ChessAdapter.perft(position, 2)
        assertThat(count == 2_079L, "expected 2079, got $count")
    }
    suite.test("en-passant capture removes the bypassed pawn") {
        val position = ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        val after = ChessRules.apply(position, UciMove("e5d6"))
        assertThat(after[Square.parse("d5")] == null)
        assertThat(after[Square.parse("d6")]?.type == PieceType.PAWN)
    }
    suite.test("en-passant exposing own king is illegal") {
        val position = ChessPosition.fromFen("k3r3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertThat(UciMove("e5d6") !in ChessRules.legalUciMoves(position))
    }
    suite.test("promotion generates four legal choices") {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val promotions = ChessRules.legalUciMoves(position).filter { it.value.startsWith("a7a8") }
        assertThat(promotions.map { it.value.last() }.toSet() == setOf('q', 'r', 'b', 'n'))
    }
    suite.test("castling through an attacked transit square is illegal") {
        val position = ChessPosition.fromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1")
        assertThat(UciMove("e1g1") !in ChessRules.legalUciMoves(position))
    }
    suite.test("castling moves rook and clears king rights") {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        val after = ChessRules.apply(position, UciMove("e1g1"))
        assertThat(after[Square.parse("f1")]?.type == PieceType.ROOK)
        assertThat(after[Square.parse("g1")]?.type == PieceType.KING)
        assertThat(after.castlingRights.fen() == "-")
    }
    suite.test("double pawn move records raw en-passant target") {
        val after = ChessRules.apply(ChessPosition.starting(), UciMove("e2e4"))
        assertThat(after.enPassantTarget == Square.parse("e3"))
    }
    suite.test("repetition key omits ineffective en-passant target") {
        val after = ChessRules.apply(ChessPosition.starting(), UciMove("e2e4"))
        assertThat(RepetitionKey.of(after).value.endsWith("KQkq -"))
    }
    suite.test("repetition key omits a pinned en-passant target") {
        var position = ChessPosition.fromFen("k3r1n1/3p4/8/4P3/8/8/8/4K1N1 b - - 0 1")
        var session = GameSession.newGame(
            "pinned-en-passant-repetition",
            RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.DISABLED),
            RepetitionKey.of(position),
            position.sideToMove,
        )
        val moves = listOf(
            "d7d5",
            "g1f3", "g8f6", "f3g1", "f6g8",
            "g1f3", "g8f6", "f3g1", "f6g8",
        ).map(::UciMove)
        moves.forEachIndexed { index, move ->
            val transition = ChessAdapter.transition(position, move)
            session = session.apply(transition)
            position = ChessRules.apply(position, move)
            if (index == 0) {
                assertThat(position.enPassantTarget == Square.parse("d6"))
                assertThat(UciMove("e5d6") !in ChessRules.legalUciMoves(position))
                assertThat(RepetitionKey.of(position).value.endsWith("- -"))
            }
        }
        assertThat(session.outcome == GameOutcome(Side.WHITE, reason = EndReason.REPETITION))
    }
    suite.test("repetition key retains a legal en-passant target") {
        val position = ChessPosition.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        assertThat(RepetitionKey.of(position).value.endsWith("- d6"))
    }
    suite.test("bare kings are a known dead position") {
        assertThat(DeadPositionDetector.isKnownDead(
            ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1"),
        ))
    }
    suite.test("single bishop versus king is dead") {
        assertThat(DeadPositionDetector.isKnownDead(
            ChessPosition.fromFen("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1"),
        ))
    }
    suite.test("two knights are not automatically dead") {
        assertThat(!DeadPositionDetector.isKnownDead(
            ChessPosition.fromFen("4k3/8/8/8/8/8/8/1NN1K3 w - - 0 1"),
        ))
    }
    suite.test("bishops confined to one color are dead") {
        assertThat(DeadPositionDetector.isKnownDead(
            ChessPosition.fromFen("4k3/8/8/8/4B3/8/2B5/4K3 w - - 0 1"),
        ))
    }
    suite.test("capturing a player's final piece produces an immediate bare-king loss") {
        val position = ChessPosition.fromFen("r3k3/8/8/8/8/8/8/R3K3 w - - 0 1")
        val session = GameSession.newGame(
            "bare-king-capture",
            RulesContractV1.drawless(),
            RepetitionKey.of(position),
            position.sideToMove,
        ).apply(ChessAdapter.transition(position, UciMove("a1a8")))
        assertThat(session.outcome?.winner == Side.WHITE)
        assertThat(session.outcome?.reason == EndReason.BARE_KING)
        assertThat(session.adjudicationFacts?.lastCaptureBy == Side.WHITE)
    }
    suite.test("replay detects Fool's Mate") {
        val result = ChessAdapter.replay(
            ChessPosition.START_FEN,
            listOf("f2f3", "e7e5", "g2g4", "d8h4").map(::UciMove),
        )
        assertThat(ChessRules.isCheckmate(result))
    }
    suite.test("replay reports the illegal ply") {
        val error = try {
            ChessAdapter.replay(ChessPosition.START_FEN, listOf(UciMove("e2e5")))
            null
        } catch (caught: IllegalArgumentException) {
            caught
        }
        assertThat(error?.message?.contains("ply 1") == true)
    }
    suite.test("adapter supplies every starting alternative") {
        val transition = ChessAdapter.transition(ChessPosition.starting(), UciMove("e2e4"))
        assertThat(transition.legalAlternativesBeforeMove.size == 20)
        assertThat(transition.resultingPositionKey == RepetitionKey.of(
            ChessRules.apply(ChessPosition.starting(), UciMove("e2e4")),
        ))
    }
    suite.test("adapter transition commits through Drawless session") {
        val position = ChessPosition.starting()
        val session = GameSession.newGame("chess-1", drawless, RepetitionKey.of(position))
            .apply(ChessAdapter.transition(position, UciMove("e2e4")))
        assertThat(session.moves.single().move == UciMove("e2e4"))
        assertThat(session.outcome == null)
    }
    suite.test("coordinator persists its initial checkpoint") {
        val fixture = coordinatorFixture()
        assertThat(fixture.sink.saved.size == 1)
        assertThat(fixture.sink.saved.last().moves.isEmpty())
    }
    suite.test("coordinator forwards every visible opponent strength for new and restored games") {
        val visibleStrengths = BotDifficultyCatalog.namedLevels.map { level ->
            level.id to level.approximateElo
        } + listOf(
            "adaptive-minimum" to 500,
            "adaptive-starting" to 800,
            "adaptive-interior" to 973,
            "adaptive-maximum" to 2_850,
        )

        visibleStrengths.forEach { (caseId, elo) ->
            val opponentLevelId = if (caseId.startsWith("adaptive-")) {
                BotDifficultyCatalog.ADAPTIVE_LEVEL_ID
            } else {
                caseId
            }
            val config = coordinatorConfig(humanSide = Side.BLACK).copy(
                gameId = "visible-$caseId-new",
                engineStrength = EngineStrength.ApproximateElo(elo),
                engineLimits = EngineLimits(moveTimeMillis = 350, multiPv = 1),
                opponentLevelId = opponentLevelId,
            )
            val fresh = coordinatorFixture(config)
            val freshRequest = fresh.engine.requests.single()
            assertThat(freshRequest.request.purpose == EnginePurpose.BOT_MOVE, "$caseId was not a bot request")
            assertThat(freshRequest.request.strength == config.engineStrength, "$caseId changed strength")
            assertThat(freshRequest.request.limits == config.engineLimits, "$caseId changed search limits")
            assertThat(fresh.coordinator.checkpoint().config == config, "$caseId changed its checkpoint tuple")
            fresh.coordinator.close()

            val original = GameCoordinator.newGame(
                config.copy(gameId = "visible-$caseId-restored"),
                FakeChessEngine(),
                FakeCheckpointSink(),
                FakeCoordinatorTime(),
                FakeCoordinatorIds(),
            )
            val checkpoint = original.checkpoint()
            original.close()
            val restoredEngine = FakeChessEngine()
            val restored = GameCoordinator.restore(
                checkpoint,
                restoredEngine,
                FakeCheckpointSink(),
                FakeCoordinatorTime(),
                FakeCoordinatorIds(),
            )
            restored.start()
            val restoredRequest = restoredEngine.requests.single()
            assertThat(
                restoredRequest.request.purpose == EnginePurpose.BOT_MOVE,
                "$caseId restore was not a bot request",
            )
            assertThat(restoredRequest.request.strength == config.engineStrength, "$caseId restore changed strength")
            assertThat(restoredRequest.request.limits == config.engineLimits, "$caseId restore changed search limits")
            assertThat(restored.checkpoint().config == checkpoint.config, "$caseId restore changed its tuple")
            restored.close()
        }
    }
    suite.test("coordinator persists threat indication availability from game start") {
        val fixture = coordinatorFixture(
            initialAssistance = AssistanceCounts(threatIndication = true),
        )
        assertThat(fixture.coordinator.snapshot().assistance.threatIndication)
        assertThat(fixture.sink.saved.last().assistance.threatIndication)
    }
    suite.test("rated game cannot start with threat indication assistance") {
        assertThrows<IllegalArgumentException> {
            GameCoordinator.newGame(
                coordinatorConfig(mode = GameMode.RATED),
                FakeChessEngine(),
                FakeCheckpointSink(),
                FakeCoordinatorTime(),
                FakeCoordinatorIds(),
                initialAssistance = AssistanceCounts(threatIndication = true),
            )
        }
    }
    suite.test("cached review positions and root keys preserve planner identity") {
        val initialFen = "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4"
        val moves = listOf(UciMove("b5a4"), UciMove("g8f6"), UciMove("e1g1"))
        val replayed = ChessAdapter.replay(initialFen, moves)
        val replayRoot = GameReviewPlanner.playerRoot(
            requestId = "cached-root",
            gameId = "cached-game",
            initialFen = initialFen,
            moves = moves,
            rules = drawless,
        )
        val cachedRoot = GameReviewPlanner.playerRootAtPosition(
            requestId = "cached-root",
            gameId = "cached-game",
            initialFen = initialFen,
            moves = moves,
            rules = drawless,
            position = replayed,
        )

        assertThat(cachedRoot == replayRoot)
        assertThat(
            GameReviewPlanner.adjacentRoot(
                requestId = "cached-adjacent",
                root = replayRoot,
                playedMove = UciMove("f8e7"),
            ) == GameReviewPlanner.adjacentRoot(
                requestId = "cached-adjacent",
                rootKey = replayRoot.key,
                playedMove = UciMove("f8e7"),
            ),
        )
    }
    suite.test("foreground review prefetch warms the exact player root without changing phase") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)

        val pending = fixture.engine.requests.single()
        assertThat(pending.request.purpose == EnginePurpose.REVIEW)
        assertThat(pending.request.moves.isEmpty())
        assertThat(pending.request.strength == EngineStrength.SkillLevel(20))
        assertThat(pending.request.limits == EngineLimits(350, 3))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        fixture.engine.respond(pending, "e2e4")
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().single().key.ply == 1)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(fixture.coordinator.snapshot().engineError == null)
    }
    suite.test("completed review prefetch is persisted immediately and survives saved-game restore") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.respond(fixture.engine.requests.single(), "e2e4")

        val durable = fixture.sink.saved.last()
        assertThat(durable.moves.isEmpty())
        assertThat(durable.reviewPrefetchRoots.size == 1)
        assertThat(durable.revision == 1L)
        fixture.coordinator.close()

        val restoredEngine = FakeChessEngine()
        val restored = GameCoordinator.restore(
            checkpoint = durable,
            engine = restoredEngine,
            checkpointSink = FakeCheckpointSink(),
            timeSource = fixture.time,
            idSource = FakeCoordinatorIds(),
        )
        try {
            restored.start()
            restored.setReviewPrefetchEnabled(true)
            assertThat(restored.completedReviewPrefetchRoots() == durable.reviewPrefetchRoots)
            assertThat(
                restoredEngine.requests.isEmpty(),
                "The exact current root was searched again after saved-game restore",
            )
        } finally {
            restored.close()
        }
    }
    suite.test("played review roots and adjacent evidence survive saved-game restore") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.respond(fixture.engine.requests.single(), "d2d4")
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(fixture.engine.requests.last(), "e7e5")
        fixture.engine.respond(fixture.engine.requests.last(), "g1f3")
        val adjacent = fixture.engine.requests.last()
        assertThat(adjacent.request.moves.map { it.value } == listOf("e2e4"))
        fixture.engine.respond(adjacent, "e7e5")

        val durable = fixture.coordinator.checkpoint()
        assertThat(durable.reviewPrefetchRoots.size == 2)
        assertThat(durable.reviewPrefetchAdjacentRoots.size == 1)
        fixture.coordinator.close()

        val restoredEngine = FakeChessEngine()
        val restored = GameCoordinator.restore(
            checkpoint = durable,
            engine = restoredEngine,
            checkpointSink = FakeCheckpointSink(),
            timeSource = fixture.time,
            idSource = FakeCoordinatorIds(),
        )
        try {
            restored.start()
            restored.setReviewPrefetchEnabled(true)
            assertThat(restored.completedReviewPrefetchRoots() == durable.reviewPrefetchRoots)
            assertThat(
                restored.completedReviewPrefetchAdjacentRoots() ==
                    durable.reviewPrefetchAdjacentRoots,
            )
            assertThat(
                restoredEngine.requests.isEmpty(),
                "Restored exact roots or their completed fallback were searched again",
            )
        } finally {
            restored.close()
        }
    }
    suite.test("saved-game restore rejects review evidence from a different exact game key") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.respond(fixture.engine.requests.single(), "e2e4")
        val stale = fixture.coordinator.checkpoint().copy(
            config = fixture.coordinator.checkpoint().config.copy(gameId = "different-game"),
        )
        fixture.coordinator.close()

        val restoredEngine = FakeChessEngine()
        val restored = GameCoordinator.restore(
            checkpoint = stale,
            engine = restoredEngine,
            checkpointSink = FakeCheckpointSink(),
            timeSource = fixture.time,
            idSource = FakeCoordinatorIds(),
        )
        try {
            restored.start()
            restored.setReviewPrefetchEnabled(true)
            assertThat(restored.completedReviewPrefetchRoots().isEmpty())
            assertThat(restoredEngine.requests.single().request.purpose == EnginePurpose.REVIEW)
        } finally {
            restored.close()
        }
    }
    suite.test("idle review prefetch warms an off-MultiPV played-position fallback after the current root") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val firstRoot = fixture.engine.requests.single()
        fixture.engine.respond(firstRoot, "d2d4")

        fixture.coordinator.playHuman(UciMove("e2e4"))
        val bot = fixture.engine.requests.last()
        fixture.engine.respond(bot, "e7e5")

        val currentRoot = fixture.engine.requests.last()
        assertThat(currentRoot.request.purpose == EnginePurpose.REVIEW)
        assertThat(currentRoot.request.moves.map { it.value } == listOf("e2e4", "e7e5"))
        fixture.engine.respond(currentRoot, "g1f3")

        val adjacent = fixture.engine.requests.last()
        assertThat(adjacent !== currentRoot)
        assertThat(adjacent.request.purpose == EnginePurpose.REVIEW)
        assertThat(adjacent.request.moves.map { it.value } == listOf("e2e4"))
        assertThat(adjacent.request.positionId.contains(":review:1:"))
        fixture.engine.respond(adjacent, "e7e5")

        assertThat(fixture.coordinator.completedReviewPrefetchRoots().size == 2)
        assertThat(fixture.coordinator.completedReviewPrefetchAdjacentRoots().size == 1)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(fixture.engine.requests.last() === adjacent)
    }
    suite.test("foregrounding retries an adjacent prefetch interrupted while backgrounded") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.respond(fixture.engine.requests.single(), "d2d4")
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(fixture.engine.requests.last(), "e7e5")
        fixture.engine.respond(fixture.engine.requests.last(), "g1f3")
        val interruptedAdjacent = fixture.engine.requests.last()
        assertThat(interruptedAdjacent.request.moves.map { it.value } == listOf("e2e4"))

        fixture.coordinator.setReviewPrefetchEnabled(false)
        fixture.coordinator.setReviewPrefetchEnabled(true)

        val retriedAdjacent = fixture.engine.requests.last()
        assertThat(interruptedAdjacent.cancelled)
        assertThat(retriedAdjacent !== interruptedAdjacent)
        assertThat(retriedAdjacent.request.purpose == EnginePurpose.REVIEW)
        assertThat(retriedAdjacent.request.moves == interruptedAdjacent.request.moves)
    }
    suite.test("idle review prefetch runs at most one historical fallback per player turn") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.respond(fixture.engine.requests.single(), "d2d4")

        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(fixture.engine.requests.last(), "e7e5")
        fixture.engine.respond(fixture.engine.requests.last(), "b1c3")
        val firstAdjacentAttempt = fixture.engine.requests.last()
        assertThat(firstAdjacentAttempt.request.moves.map { it.value } == listOf("e2e4"))

        // Moving preempts the first fallback but retains it as the oldest queued candidate. The
        // newly completed current root contributes a second off-MultiPV candidate.
        fixture.coordinator.playHuman(UciMove("g1f3"))
        assertThat(firstAdjacentAttempt.cancelled)
        fixture.engine.respond(fixture.engine.requests.last(), "b8c6")
        fixture.engine.respond(fixture.engine.requests.last(), "f1b5")
        val retriedAdjacent = fixture.engine.requests.last()
        assertThat(retriedAdjacent.request.moves.map { it.value } == listOf("e2e4"))

        val requestCount = fixture.engine.requests.size
        fixture.engine.respond(retriedAdjacent, "e7e5")
        assertThat(
            fixture.engine.requests.size == requestCount,
            "A second historical fallback started in the same position revision",
        )
    }
    suite.test("human move preempts review prefetch and bot then starts the next player root") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val stale = fixture.engine.requests.single()

        fixture.coordinator.playHuman(UciMove("e2e4"))

        assertThat(stale.cancelled)
        val bot = fixture.engine.requests.last()
        assertThat(bot.request.purpose == EnginePurpose.BOT_MOVE)
        stale.callback(Result.success(engineResponse(stale.request, "e2e4")))
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().isEmpty())

        fixture.engine.respond(bot, "e7e5")
        val nextRoot = fixture.engine.requests.last()
        assertThat(nextRoot.request.purpose == EnginePurpose.REVIEW)
        assertThat(nextRoot.request.moves.map { it.value } == listOf("e2e4", "e7e5"))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("hint preempts review prefetch and review resumes after the hint") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val prefetch = fixture.engine.requests.single()
        var hintDelivered = false

        fixture.coordinator.requestHint(fixture.coordinator.snapshot().session.positionId) {
            hintDelivered = it.isSuccess
        }

        assertThat(prefetch.cancelled)
        val hint = fixture.engine.requests.last()
        assertThat(hint.request.purpose == EnginePurpose.HINT)
        fixture.engine.respond(hint, "e2e4")
        assertThat(hintDelivered)
        assertThat(fixture.engine.requests.last().request.purpose == EnginePurpose.REVIEW)
        assertThat(fixture.engine.requests.last().request.requestId != prefetch.request.requestId)
    }
    suite.test("black player review prefetch waits for the opening bot move") {
        val fixture = coordinatorFixture(coordinatorConfig(humanSide = Side.BLACK))
        val openingBot = fixture.engine.requests.single()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        assertThat(fixture.engine.requests.size == 1)

        fixture.engine.respond(openingBot, "e2e4")

        val playerRoot = fixture.engine.requests.last()
        assertThat(playerRoot.request.purpose == EnginePurpose.REVIEW)
        assertThat(playerRoot.request.moves.map { it.value } == listOf("e2e4"))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("backgrounding cancels a prefetch and foregrounding retries with a fresh identity") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val first = fixture.engine.requests.single()

        fixture.coordinator.setReviewPrefetchEnabled(false)
        assertThat(first.cancelled)
        first.callback(Result.success(engineResponse(first.request, "e2e4")))

        fixture.coordinator.setReviewPrefetchEnabled(true)
        val retry = fixture.engine.requests.last()
        assertThat(retry.request.requestId != first.request.requestId)
        fixture.engine.respond(retry, "e2e4")
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().size == 1)
    }
    suite.test("concurrent disable then enable retries after the in-flight prefetch gate drains") {
        val firstAnalyzeEntered = CountDownLatch(1)
        val releaseFirstAnalyze = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val calls = CopyOnWriteArrayList<EngineRequest>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val engine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                calls += request
                if (calls.size == 1) {
                    firstAnalyzeEntered.countDown()
                    check(releaseFirstAnalyze.await(5, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the first toggle prefetch"
                    }
                }
                return EngineCancellation {
                    if (calls.firstOrNull() == request) firstCancelled.set(true)
                }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), engine, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        val firstLaunch = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let { failures += it }
        }.also { it.isDaemon = true }
        val disable = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(false) }
                .exceptionOrNull()?.let { failures += it }
        }.also { it.isDaemon = true }
        firstLaunch.start()
        try {
            assertThat(firstAnalyzeEntered.await(5, TimeUnit.SECONDS))
            disable.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (disable.state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertThat(disable.state == Thread.State.WAITING)

            // The enable sees the final desired state but deliberately drops its speculative
            // tryLock while disable is queued. Disable's post-drain recheck must restore it.
            coordinator.setReviewPrefetchEnabled(true)
            assertThat(calls.size == 1)
            releaseFirstAnalyze.countDown()
            firstLaunch.join(5_000)
            disable.join(5_000)

            assertThat(!firstLaunch.isAlive && !disable.isAlive)
            failures.firstOrNull()?.let { throw it }
            assertThat(calls.size == 2)
            assertThat(calls.all { it.purpose == EnginePurpose.REVIEW })
            assertThat(calls[0].requestId != calls[1].requestId)
            assertThat(firstCancelled.get())
            assertThat(coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        } finally {
            releaseFirstAnalyze.countDown()
            if (!firstLaunch.isAlive && !disable.isAlive) coordinator.close()
        }
    }
    suite.test("review prefetch failure stays invisible and can retry") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        fixture.engine.fail(message = "speculative failure")
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(fixture.coordinator.snapshot().engineError == null)

        fixture.coordinator.setReviewPrefetchEnabled(false)
        fixture.coordinator.setReviewPrefetchEnabled(true)
        assertThat(fixture.engine.requests.size == 2)
    }
    suite.test("pause cancels review prefetch and resume retries the same root") {
        val fixture = coordinatorFixture(
            coordinatorConfig(timeControl = TimeControl.Clock(10_000)),
        )
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val first = fixture.engine.requests.single()

        fixture.coordinator.pause()
        assertThat(first.cancelled)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.PAUSED)
        fixture.coordinator.resume()

        assertThat(fixture.engine.requests.size == 2)
        assertThat(fixture.engine.requests.last().request.purpose == EnginePurpose.REVIEW)
    }
    suite.test("timeout cancels review prefetch and rejects its stale result") {
        val fixture = coordinatorFixture(
            coordinatorConfig(timeControl = TimeControl.Clock(1_000)),
        )
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val stale = fixture.engine.requests.single()
        fixture.time.advance(1_000)

        fixture.coordinator.tick()
        stale.callback(Result.success(engineResponse(stale.request, "e2e4")))

        assertThat(stale.cancelled)
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().isEmpty())
        assertThat(fixture.coordinator.snapshot().session.outcome?.reason == EndReason.TIMEOUT)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.COMPLETED)
    }
    suite.test("resigning and closing both cancel active review prefetch") {
        val resigned = coordinatorFixture()
        resigned.coordinator.setReviewPrefetchEnabled(true)
        val resignedRequest = resigned.engine.requests.single()
        resigned.coordinator.resignHuman()
        assertThat(resignedRequest.cancelled)
        assertThat(resigned.coordinator.snapshot().phase == CoordinatorPhase.COMPLETED)

        val closed = coordinatorFixture()
        closed.coordinator.setReviewPrefetchEnabled(true)
        val closedRequest = closed.engine.requests.single()
        closed.coordinator.close()
        assertThat(closedRequest.cancelled)
    }
    suite.test("synchronous review prefetch publishes once without leaking engine work") {
        var cancellationCalled = false
        val immediate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(engineResponse(request, "e2e4")))
                return EngineCancellation { cancellationCalled = true }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), immediate, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        coordinator.setReviewPrefetchEnabled(true)
        assertThat(coordinator.completedReviewPrefetchRoots().size == 1)
        assertThat(cancellationCalled)
        assertThat(coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("review callback completing on another thread before analyze returns continues fallback work") {
        val gameplayEngine = FakeChessEngine()
        val reviewRequests = CopyOnWriteArrayList<EngineRequest>()
        val immediateFromWorker = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewRequests += request
                val callbackCompleted = CountDownLatch(1)
                Thread {
                    val bestMove = when (request.moves.map { it.value }) {
                        emptyList<String>() -> "d2d4"
                        listOf("e2e4", "e7e5") -> "g1f3"
                        listOf("e2e4") -> "e7e5"
                        else -> error("Unexpected review root ${request.moves}")
                    }
                    onResult(Result.success(engineResponse(request, bestMove)))
                    callbackCompleted.countDown()
                }.start()
                check(callbackCompleted.await(5, TimeUnit.SECONDS)) {
                    "Review callback did not complete before analyze returned"
                }
                return EngineCancellation {}
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), gameplayEngine, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(), reviewEngine = immediateFromWorker,
        )
        coordinator.start()
        coordinator.setReviewPrefetchEnabled(true)
        coordinator.playHuman(UciMove("e2e4"))
        gameplayEngine.respond(gameplayEngine.requests.single(), "e7e5")

        assertThat(
            reviewRequests.map { request -> request.moves.map { it.value } } ==
                listOf(emptyList(), listOf("e2e4", "e7e5"), listOf("e2e4")),
        )
        assertThat(coordinator.completedReviewPrefetchRoots().size == 2)
        assertThat(coordinator.completedReviewPrefetchAdjacentRoots().size == 1)
    }
    suite.test("synchronous failed review prefetch does not recursively relaunch") {
        var analyzeCalls = 0
        var cancellationCalled = false
        val immediateFailure = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                analyzeCalls++
                onResult(Result.failure(IllegalStateException("review failed")))
                return EngineCancellation { cancellationCalled = true }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), immediateFailure, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()

        coordinator.setReviewPrefetchEnabled(true)

        assertThat(analyzeCalls == 1)
        assertThat(cancellationCalled)
        assertThat(coordinator.completedReviewPrefetchRoots().isEmpty())
        assertThat(coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("enabling review prefetch before coordinator start launches after start") {
        val engine = FakeChessEngine()
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), engine, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )

        coordinator.setReviewPrefetchEnabled(true)
        assertThat(engine.requests.isEmpty())
        coordinator.start()

        assertThat(engine.requests.single().request.purpose == EnginePurpose.REVIEW)
    }
    suite.test("an illegal human move leaves the active review prefetch attached") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val prefetch = fixture.engine.requests.single()

        assertThrows<IllegalArgumentException> {
            fixture.coordinator.playHuman(UciMove("e2e5"))
        }

        assertThat(!prefetch.cancelled)
        assertThat(fixture.engine.requests.size == 1)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        fixture.engine.respond(prefetch, "e2e4")
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().size == 1)
    }
    suite.test("marking hint use replaces review prefetch without accepting its stale result") {
        val fixture = coordinatorFixture()
        fixture.coordinator.setReviewPrefetchEnabled(true)
        val stale = fixture.engine.requests.single()

        fixture.coordinator.markHintUsed()

        val replacement = fixture.engine.requests.last()
        assertThat(stale.cancelled)
        assertThat(replacement.request.purpose == EnginePurpose.REVIEW)
        assertThat(replacement.request.requestId != stale.request.requestId)
        assertThat(fixture.coordinator.snapshot().assistance.hints == 1)
        stale.callback(Result.success(engineResponse(stale.request, "e2e4")))
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().isEmpty())
        fixture.engine.respond(replacement, "e2e4")
        assertThat(fixture.coordinator.completedReviewPrefetchRoots().size == 1)
    }
    suite.test("a throwing prefetch cancellation cannot strand a bot move or hint") {
        class ThrowingReviewCancellationEngine : ChessEngine {
            val purposes = mutableListOf<EnginePurpose>()
            val cancelAttempts = mutableListOf<EnginePurpose>()

            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                purposes += request.purpose
                return EngineCancellation {
                    cancelAttempts += request.purpose
                    if (request.purpose == EnginePurpose.REVIEW) {
                        throw IllegalStateException("speculative cancellation failed")
                    }
                }
            }
        }

        val moveEngine = ThrowingReviewCancellationEngine()
        val moveCoordinator = GameCoordinator.newGame(
            coordinatorConfig(), moveEngine, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        moveCoordinator.start()
        moveCoordinator.setReviewPrefetchEnabled(true)
        moveCoordinator.playHuman(UciMove("e2e4"))
        assertThat(moveEngine.cancelAttempts == listOf(EnginePurpose.REVIEW))
        assertThat(moveEngine.purposes == listOf(EnginePurpose.REVIEW, EnginePurpose.BOT_MOVE))
        assertThat(moveCoordinator.snapshot().phase == CoordinatorPhase.BOT_THINKING)
        moveCoordinator.close()

        val hintEngine = ThrowingReviewCancellationEngine()
        val hintCoordinator = GameCoordinator.newGame(
            coordinatorConfig(), hintEngine, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        hintCoordinator.start()
        hintCoordinator.setReviewPrefetchEnabled(true)
        hintCoordinator.requestHint(hintCoordinator.snapshot().session.positionId) {}
        assertThat(hintEngine.cancelAttempts == listOf(EnginePurpose.REVIEW))
        assertThat(hintEngine.purposes == listOf(EnginePurpose.REVIEW, EnginePurpose.HINT))
        assertThat(hintCoordinator.snapshot().phase == CoordinatorPhase.HINT_THINKING)
        hintCoordinator.close()
    }
    suite.test("bot launch waits for a preempted review analyze call to publish cancellation") {
        data class Call(
            val request: EngineRequest,
            val cancelled: AtomicBoolean = AtomicBoolean(false),
        )

        val firstAnalyzeEntered = CountDownLatch(1)
        val releaseFirstAnalyze = CountDownLatch(1)
        val movePersisted = CountDownLatch(1)
        val analyzeCount = AtomicInteger(0)
        val calls = CopyOnWriteArrayList<Call>()
        val failure = AtomicReference<Throwable?>(null)
        val engine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                val call = Call(request)
                calls += call
                if (analyzeCount.incrementAndGet() == 1) {
                    firstAnalyzeEntered.countDown()
                    check(releaseFirstAnalyze.await(5, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the first analyze call"
                    }
                }
                return EngineCancellation { call.cancelled.set(true) }
            }
        }
        val sink = CheckpointSink { checkpoint ->
            if (checkpoint.moves.size == 1) movePersisted.countDown()
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), engine, sink, FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(firstAnalyzeEntered.await(5, TimeUnit.SECONDS))
            val moveThread = Thread {
                runCatching { coordinator.playHuman(UciMove("e2e4")) }
                    .exceptionOrNull()?.let(failure::set)
            }
            moveThread.start()
            assertThat(movePersisted.await(5, TimeUnit.SECONDS))

            assertThat(analyzeCount.get() == 1, "Bot analysis overlapped review startup")
            releaseFirstAnalyze.countDown()
            prefetchThread.join(5_000)
            moveThread.join(5_000)
            assertThat(!prefetchThread.isAlive && !moveThread.isAlive)
            failure.get()?.let { throw it }
            assertThat(analyzeCount.get() == 2)
            assertThat(calls.map { it.request.purpose } == listOf(EnginePurpose.REVIEW, EnginePurpose.BOT_MOVE))
            assertThat(calls.first().cancelled.get())
        } finally {
            releaseFirstAnalyze.countDown()
            coordinator.close()
        }
    }
    suite.test("separate review startup cannot block a gameplay launch") {
        val reviewEntered = CountDownLatch(1)
        val releaseReview = CountDownLatch(1)
        val moveCompleted = CountDownLatch(1)
        val reviewCancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val gameplayEngine = FakeChessEngine()
        val reviewEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEntered.countDown()
                check(releaseReview.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release isolated review startup"
                }
                return EngineCancellation { reviewCancelled.set(true) }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(),
            gameplayEngine,
            FakeCheckpointSink(),
            FakeCoordinatorTime(),
            FakeCoordinatorIds(),
            reviewEngine = reviewEngine,
        )
        coordinator.start()
        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(reviewEntered.await(5, TimeUnit.SECONDS))
            val moveThread = Thread {
                runCatching { coordinator.playHuman(UciMove("e2e4")) }
                    .exceptionOrNull()?.let(failure::set)
                moveCompleted.countDown()
            }
            moveThread.start()

            assertThat(
                moveCompleted.await(5, TimeUnit.SECONDS),
                "Gameplay waited for the separate review engine to publish cancellation",
            )
            assertThat(gameplayEngine.requests.single().request.purpose == EnginePurpose.BOT_MOVE)
            assertThat(prefetchThread.isAlive)

            releaseReview.countDown()
            prefetchThread.join(5_000)
            moveThread.join(5_000)
            failure.get()?.let { throw it }
            assertThat(reviewCancelled.get())
        } finally {
            releaseReview.countDown()
            coordinator.close()
        }
    }
    suite.test("separate review startup cannot block undo") {
        val reviewEntered = CountDownLatch(1)
        val releaseReview = CountDownLatch(1)
        val undoCompleted = CountDownLatch(1)
        val reviewCancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val gameplayEngine = FakeChessEngine()
        val reviewEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEntered.countDown()
                check(releaseReview.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release isolated review startup during undo"
                }
                return EngineCancellation { reviewCancelled.set(true) }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(),
            gameplayEngine,
            FakeCheckpointSink(),
            FakeCoordinatorTime(),
            FakeCoordinatorIds(),
            reviewEngine = reviewEngine,
        )
        coordinator.start()
        coordinator.playHuman(UciMove("e2e4"))
        gameplayEngine.respond(gameplayEngine.requests.single(), "e7e5")

        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(reviewEntered.await(5, TimeUnit.SECONDS))
            val undoThread = Thread {
                runCatching { coordinator.undoLastHumanTurn() }
                    .exceptionOrNull()?.let(failure::set)
                undoCompleted.countDown()
            }
            undoThread.start()

            assertThat(
                undoCompleted.await(5, TimeUnit.SECONDS),
                "Undo waited for the separate review engine to publish cancellation",
            )
            assertThat(coordinator.snapshot().session.moves.isEmpty())
            assertThat(prefetchThread.isAlive)

            releaseReview.countDown()
            prefetchThread.join(5_000)
            undoThread.join(5_000)
            failure.get()?.let { throw it }
            assertThat(reviewCancelled.get())
        } finally {
            releaseReview.countDown()
            coordinator.close()
        }
    }
    suite.test("background disable drains a separate unpublished review launch") {
        val reviewEntered = CountDownLatch(1)
        val releaseReview = CountDownLatch(1)
        val disableCompleted = CountDownLatch(1)
        val reviewCancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val reviewEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEntered.countDown()
                check(releaseReview.await(5, TimeUnit.SECONDS))
                return EngineCancellation { reviewCancelled.set(true) }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), FakeChessEngine(), FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(), reviewEngine = reviewEngine,
        )
        coordinator.start()
        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(reviewEntered.await(5, TimeUnit.SECONDS))
            val disableThread = Thread {
                runCatching { coordinator.setReviewPrefetchEnabled(false) }
                    .exceptionOrNull()?.let(failure::set)
                disableCompleted.countDown()
            }
            disableThread.start()

            assertThat(
                !disableCompleted.await(100, TimeUnit.MILLISECONDS),
                "Background disable returned before the unpublished review launch drained",
            )
            releaseReview.countDown()
            assertThat(disableCompleted.await(5, TimeUnit.SECONDS))
            prefetchThread.join(5_000)
            disableThread.join(5_000)
            failure.get()?.let { throw it }
            assertThat(reviewCancelled.get())
        } finally {
            releaseReview.countDown()
            coordinator.close()
        }
    }
    suite.test("close drains a separate unpublished review launch") {
        val reviewEntered = CountDownLatch(1)
        val releaseReview = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val reviewCancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val reviewEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEntered.countDown()
                check(releaseReview.await(5, TimeUnit.SECONDS))
                return EngineCancellation { reviewCancelled.set(true) }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), FakeChessEngine(), FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(), reviewEngine = reviewEngine,
        )
        coordinator.start()
        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(reviewEntered.await(5, TimeUnit.SECONDS))
            val closeThread = Thread {
                runCatching { coordinator.close() }.exceptionOrNull()?.let(failure::set)
                closeCompleted.countDown()
            }
            closeThread.start()

            assertThat(
                !closeCompleted.await(100, TimeUnit.MILLISECONDS),
                "Coordinator close returned before the unpublished review launch drained",
            )
            releaseReview.countDown()
            assertThat(closeCompleted.await(5, TimeUnit.SECONDS))
            prefetchThread.join(5_000)
            closeThread.join(5_000)
            failure.get()?.let { throw it }
            assertThat(reviewCancelled.get())
        } finally {
            releaseReview.countDown()
            coordinator.close()
        }
    }
    suite.test("failed human commit drains its separate unpublished review launch") {
        val reviewEntered = CountDownLatch(1)
        val releaseReview = CountDownLatch(1)
        val moveCompleted = CountDownLatch(1)
        val reviewCancelled = AtomicBoolean(false)
        val moveFailure = AtomicReference<Throwable?>(null)
        val reviewEngine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                reviewEntered.countDown()
                check(releaseReview.await(5, TimeUnit.SECONDS))
                return EngineCancellation { reviewCancelled.set(true) }
            }
        }
        val sink = CheckpointSink { checkpoint ->
            if (checkpoint.moves.isNotEmpty()) error("checkpoint write failed")
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), FakeChessEngine(), sink,
            FakeCoordinatorTime(), FakeCoordinatorIds(), reviewEngine = reviewEngine,
        )
        coordinator.start()
        val prefetchThread = Thread { coordinator.setReviewPrefetchEnabled(true) }
        prefetchThread.start()
        try {
            assertThat(reviewEntered.await(5, TimeUnit.SECONDS))
            val moveThread = Thread {
                moveFailure.set(
                    runCatching { coordinator.playHuman(UciMove("e2e4")) }.exceptionOrNull(),
                )
                moveCompleted.countDown()
            }
            moveThread.start()

            assertThat(
                !moveCompleted.await(100, TimeUnit.MILLISECONDS),
                "Failed move returned before its unpublished review launch drained",
            )
            releaseReview.countDown()
            assertThat(moveCompleted.await(5, TimeUnit.SECONDS))
            prefetchThread.join(5_000)
            moveThread.join(5_000)
            assertThat(moveFailure.get()?.message == "checkpoint write failed")
            assertThat(reviewCancelled.get())
        } finally {
            releaseReview.countDown()
            coordinator.close()
        }
    }
    suite.test("terminal human move drains an unpublished prefetch before postgame handoff") {
        val firstAnalyzeEntered = CountDownLatch(1)
        val releaseFirstAnalyze = CountDownLatch(1)
        val outcomePersisted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val engine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                firstAnalyzeEntered.countDown()
                check(releaseFirstAnalyze.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release terminal-position prefetch"
                }
                return EngineCancellation { firstCancelled.set(true) }
            }
        }
        val sink = CheckpointSink { checkpoint ->
            if (checkpoint.outcome != null) outcomePersisted.countDown()
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(
                initialFen = "7k/p4Q2/6K1/8/8/8/8/8 w - - 0 1",
            ),
            engine,
            sink,
            FakeCoordinatorTime(),
            FakeCoordinatorIds(),
        )
        coordinator.start()
        val prefetchThread = Thread {
            runCatching { coordinator.setReviewPrefetchEnabled(true) }
                .exceptionOrNull()?.let(failure::set)
        }
        prefetchThread.start()
        try {
            assertThat(firstAnalyzeEntered.await(5, TimeUnit.SECONDS))
            val moveThread = Thread {
                runCatching { coordinator.playHuman(UciMove("f7f8")) }
                    .exceptionOrNull()?.let(failure::set)
            }
            moveThread.start()
            assertThat(outcomePersisted.await(5, TimeUnit.SECONDS))
            assertThat(moveThread.isAlive, "Terminal move returned before prefetch launch drained")

            releaseFirstAnalyze.countDown()
            prefetchThread.join(5_000)
            moveThread.join(5_000)
            assertThat(!prefetchThread.isAlive && !moveThread.isAlive)
            failure.get()?.let { throw it }
            assertThat(firstCancelled.get())
            assertThat(coordinator.snapshot().phase == CoordinatorPhase.COMPLETED)
            assertThat(coordinator.snapshot().session.outcome?.winner == Side.WHITE)
        } finally {
            releaseFirstAnalyze.countDown()
            coordinator.close()
        }
    }
    suite.test("callback prefetch never deadlocks with a concurrent foreground hint launch") {
        data class Pending(
            val request: EngineRequest,
            val callback: (Result<EngineResponse>) -> Unit,
        )

        val engineMonitor = Any()
        val botPersisted = CountDownLatch(1)
        val hintThreadStarted = CountDownLatch(1)
        val hintAnalyzeEntered = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val purposes = CopyOnWriteArrayList<EnginePurpose>()
        val botPending = AtomicReference<Pending?>(null)
        val expectedPositionId = ChessRules.apply(
            ChessPosition.starting(),
            UciMove("e2e4"),
        ).let { after ->
            "coordinator-game:1:${RepetitionKey.of(after).value}"
        }
        lateinit var hintThread: Thread
        val engine = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                if (request.purpose == EnginePurpose.HINT) hintAnalyzeEntered.countDown()
                synchronized(engineMonitor) {
                    purposes += request.purpose
                    if (request.purpose == EnginePurpose.BOT_MOVE) {
                        botPending.set(Pending(request, onResult))
                    }
                }
                return EngineCancellation {}
            }
        }
        val sink = CheckpointSink { checkpoint ->
            if (checkpoint.moves.size == 1) {
                botPersisted.countDown()
                check(hintThreadStarted.await(5, TimeUnit.SECONDS)) {
                    "Hint thread did not start during bot completion"
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (hintThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                check(hintThread.state == Thread.State.BLOCKED) {
                    "Hint did not acquire the foreground engine gate before callback prefetch"
                }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(humanSide = Side.BLACK),
            engine,
            sink,
            FakeCoordinatorTime(),
            FakeCoordinatorIds(),
        )
        coordinator.start()
        coordinator.setReviewPrefetchEnabled(true)
        hintThread = Thread {
            try {
                check(botPersisted.await(5, TimeUnit.SECONDS))
                hintThreadStarted.countDown()
                coordinator.requestHint(expectedPositionId) {}
            } catch (error: Throwable) {
                failures += error
            }
        }
        val botThread = Thread {
            try {
                val pending = requireNotNull(botPending.get())
                synchronized(engineMonitor) {
                    pending.callback(Result.success(engineResponse(pending.request, "e2e4")))
                }
            } catch (error: Throwable) {
                failures += error
            }
        }
        hintThread.isDaemon = true
        botThread.isDaemon = true

        hintThread.start()
        botThread.start()
        botThread.join(5_000)
        hintThread.join(5_000)
        try {
            assertThat(!botThread.isAlive && !hintThread.isAlive, "Foreground/prefetch lock inversion deadlocked")
            failures.firstOrNull()?.let { throw it }
            assertThat(hintAnalyzeEntered.count == 0L)
            assertThat(purposes == listOf(EnginePurpose.BOT_MOVE, EnginePurpose.HINT))
            assertThat(coordinator.snapshot().phase == CoordinatorPhase.HINT_THINKING)
        } finally {
            if (!botThread.isAlive && !hintThread.isAlive) coordinator.close()
        }
    }
    suite.test("human move launches a position-tagged bot request") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val request = fixture.engine.requests.single().request
        assertThat(request.moves == listOf(UciMove("e2e4")))
        assertThat(request.positionId == fixture.coordinator.snapshot().session.positionId)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.BOT_THINKING)
    }
    suite.test("valid bot response commits and returns the human turn") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.session.moves.map { it.move.value } == listOf("e2e4", "e7e5"))
        assertThat(snapshot.phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("opponent presentation delay does not charge the human clock") {
        val fixture = coordinatorFixture(
            config = coordinatorConfig(timeControl = TimeControl.Clock(10_000)),
            botMovePresentationDelayMillis = 500,
        )
        fixture.time.advance(1_000)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.time.advance(300)
        fixture.engine.respond(move = "e7e5")

        val afterBotMove = fixture.coordinator.snapshot()
        assertThat(afterBotMove.clock.whiteRemainingMillis == 9_000L)
        assertThat(afterBotMove.clock.blackRemainingMillis == 9_700L)
        fixture.time.advance(499)
        assertThat(fixture.coordinator.snapshot().clock.whiteRemainingMillis == 9_000L)
        fixture.time.advance(2)
        assertThat(fixture.coordinator.snapshot().clock.whiteRemainingMillis == 8_999L)
    }
    suite.test("synchronous engine callback is committed without leaking work") {
        var cancellationCalled = false
        val immediate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(engineResponse(request, "e2e4")))
                return EngineCancellation { cancellationCalled = true }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(humanSide = Side.BLACK), immediate, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        assertThat(coordinator.snapshot().session.moves.map { it.move.value } == listOf("e2e4"))
        assertThat(coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(cancellationCalled)
    }
    suite.test("duplicate engine callback cannot commit twice") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val pending = fixture.engine.requests.single()
        val response = Result.success(engineResponse(pending.request, "e7e5"))
        pending.callback(response)
        pending.callback(response)
        assertThat(fixture.coordinator.snapshot().session.moves.size == 2)
    }
    suite.test("cancelled stale engine callback cannot mutate an undone game") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val stale = fixture.engine.requests.single()
        fixture.coordinator.undoLastHumanTurn()
        assertThat(stale.cancelled)
        stale.callback(Result.success(engineResponse(stale.request, "e7e5")))
        assertThat(fixture.coordinator.snapshot().session.moves.isEmpty())
    }
    suite.test("closing the coordinator cancels and rejects a late bot callback") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val pending = fixture.engine.requests.single()
        val savedBeforeClose = fixture.sink.saved.size

        fixture.coordinator.close()
        assertThat(pending.cancelled)
        pending.callback(Result.success(engineResponse(pending.request, "e7e5")))

        assertThat(fixture.coordinator.snapshot().session.moves.map { it.move.value } == listOf("e2e4"))
        assertThat(fixture.sink.saved.size == savedBeforeClose)
    }
    suite.test("engine failure enters retryable bot error state") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.fail(message = "boom")
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.BOT_ERROR)
        fixture.coordinator.retryBot()
        assertThat(fixture.engine.requests.size == 2)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.BOT_THINKING)
    }
    suite.test("synchronous engine launch exception becomes bot error") {
        val throwing = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation = throw IllegalStateException("launch failed")
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(humanSide = Side.BLACK), throwing, FakeCheckpointSink(),
            FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        assertThat(coordinator.snapshot().phase == CoordinatorPhase.BOT_ERROR)
        assertThat(coordinator.snapshot().engineError == "launch failed")
    }
    suite.test("illegal engine move is rejected without changing the board") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e4")
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.session.moves.size == 1)
        assertThat(snapshot.phase == CoordinatorPhase.BOT_ERROR)
    }
    suite.test("mismatched engine response identity becomes a visible error") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val pending = fixture.engine.requests.single()
        pending.callback(Result.success(engineResponse(pending.request, "e7e5").copy(positionId = "stale")))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.BOT_ERROR)
        assertThat(fixture.coordinator.snapshot().session.moves.size == 1)
    }
    suite.test("rated mode rejects pause and hints") {
        val fixture = coordinatorFixture(coordinatorConfig(mode = GameMode.RATED))
        assertThrows<IllegalArgumentException> { fixture.coordinator.pause() }
        assertThrows<IllegalArgumentException> { fixture.coordinator.markHintUsed() }
    }
    suite.test("rated mode rejects undo after a move") {
        val fixture = coordinatorFixture(coordinatorConfig(mode = GameMode.RATED))
        fixture.coordinator.playHuman(UciMove("e2e4"))
        assertThrows<IllegalArgumentException> { fixture.coordinator.undoLastHumanTurn() }
    }
    suite.test("casual pause freezes the projected clock") {
        val fixture = coordinatorFixture(coordinatorConfig(
            timeControl = TimeControl.Clock(10_000),
        ))
        fixture.time.advance(2_000)
        fixture.coordinator.pause()
        val paused = fixture.coordinator.snapshot().clock.whiteRemainingMillis
        fixture.time.advance(5_000)
        assertThat(fixture.coordinator.snapshot().clock.whiteRemainingMillis == paused)
        fixture.coordinator.resume()
        fixture.time.advance(1_000)
        assertThat(fixture.coordinator.snapshot().clock.whiteRemainingMillis == paused!! - 1_000)
    }
    suite.test("pausing a bot turn cancels and resume relaunches it") {
        val fixture = coordinatorFixture(coordinatorConfig(
            timeControl = TimeControl.Clock(10_000), humanSide = Side.BLACK,
        ))
        val first = fixture.engine.requests.single()
        fixture.coordinator.pause()
        assertThat(first.cancelled)
        fixture.coordinator.resume()
        assertThat(fixture.engine.requests.size == 2)
    }
    suite.test("move consumes time and then applies increment") {
        val fixture = coordinatorFixture(coordinatorConfig(
            timeControl = TimeControl.Clock(10_000, incrementMillis = 500),
        ))
        fixture.time.advance(2_000)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val clock = fixture.coordinator.snapshot().clock
        assertThat(clock.whiteRemainingMillis == 8_500L)
        assertThat(clock.runningSide == Side.BLACK)
    }
    suite.test("tick adjudicates timeout and cancels a bot request") {
        val fixture = coordinatorFixture(coordinatorConfig(
            timeControl = TimeControl.Clock(1_000), humanSide = Side.BLACK,
        ))
        val request = fixture.engine.requests.single()
        fixture.time.advance(1_000)
        fixture.coordinator.tick()
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.session.outcome?.reason == EndReason.TIMEOUT)
        assertThat(snapshot.session.outcome?.winner == Side.BLACK)
        assertThat(request.cancelled)
    }
    suite.test("human resignation ends and persists the game") {
        val fixture = coordinatorFixture()
        fixture.coordinator.resignHuman()
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.session.outcome?.reason == EndReason.RESIGNATION)
        assertThat(snapshot.session.outcome?.winner == Side.BLACK)
        assertThat(fixture.sink.saved.last().outcome == snapshot.session.outcome)
    }
    suite.test("deliberately replacing a live game creates a stopped forfeit loss") {
        val fixture = coordinatorFixture(
            coordinatorConfig(timeControl = TimeControl.Clock(10_000L), humanSide = Side.WHITE),
        )
        val live = fixture.coordinator.checkpoint()
        fixture.time.advance(2_000L)

        val forfeited = live.forfeitByHuman(fixture.time.now())

        assertThat(live.outcome == null, "conversion must not mutate the live checkpoint")
        assertThat(forfeited.revision == live.revision + 1L)
        assertThat(forfeited.outcome?.winner == Side.BLACK)
        assertThat(forfeited.outcome?.reason == EndReason.RESIGNATION)
        assertThat(forfeited.outcome?.loser == Side.WHITE)
        assertThat(forfeited.clock.whiteRemainingMillis == 8_000L)
        assertThat(forfeited.clock.runningSide == null)
        assertThat(!forfeited.clock.paused)
    }
    suite.test("a completed checkpoint cannot be forfeited again") {
        val fixture = coordinatorFixture()
        fixture.coordinator.resignHuman()
        assertThrows<IllegalArgumentException> {
            fixture.coordinator.checkpoint().forfeitByHuman(fixture.time.now())
        }
    }
    suite.test("hint use is persisted as casual assistance") {
        val fixture = coordinatorFixture()
        fixture.coordinator.markHintUsed()
        assertThat(fixture.sink.saved.last().assistance.hints == 1)
    }
    suite.test("coordinator serializes a real full-strength hint request") {
        val fixture = coordinatorFixture()
        val positionId = fixture.coordinator.snapshot().session.positionId
        var result: Result<EngineResponse>? = null
        fixture.coordinator.requestHint(positionId) { result = it }
        val pending = fixture.engine.requests.single()
        assertThat(pending.request.purpose == EnginePurpose.HINT)
        assertThat(pending.request.strength == EngineStrength.SkillLevel(20))
        assertThat(pending.request.limits.multiPv == 3)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HINT_THINKING)
        assertThat(fixture.coordinator.snapshot().assistance.hints == 0)
        fixture.engine.respond(pending, "e2e4")
        assertThat(result?.getOrNull()?.bestMove == UciMove("e2e4"))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(fixture.coordinator.snapshot().assistance.hints == 1)
        assertThat(fixture.sink.saved.last().assistance.hints == 1)
    }
    suite.test("synchronous hint callback completes without leaking engine work") {
        var cancellationCalled = false
        val immediate = object : ChessEngine {
            override fun analyze(
                request: EngineRequest,
                onResult: (Result<EngineResponse>) -> Unit,
            ): EngineCancellation {
                onResult(Result.success(engineResponse(request, "e2e4")))
                return EngineCancellation { cancellationCalled = true }
            }
        }
        val coordinator = GameCoordinator.newGame(
            coordinatorConfig(), immediate, FakeCheckpointSink(), FakeCoordinatorTime(), FakeCoordinatorIds(),
        )
        coordinator.start()
        var response: EngineResponse? = null
        coordinator.requestHint(coordinator.snapshot().session.positionId) { response = it.getOrThrow() }
        assertThat(response?.bestMove == UciMove("e2e4"))
        assertThat(coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(coordinator.snapshot().assistance.hints == 1)
        assertThat(cancellationCalled)
    }
    suite.test("stale hint response is ignored after cancellation") {
        val fixture = coordinatorFixture()
        val positionId = fixture.coordinator.snapshot().session.positionId
        var delivered = false
        fixture.coordinator.requestHint(positionId) { delivered = true }
        val stale = fixture.engine.requests.single()
        fixture.coordinator.pause()
        assertThat(stale.cancelled)
        stale.callback(Result.success(engineResponse(stale.request, "e2e4")))
        assertThat(!delivered)
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.PAUSED)
        assertThat(fixture.coordinator.snapshot().assistance.hints == 0)
    }
    suite.test("hint failure returns to the human turn without a bot error") {
        val fixture = coordinatorFixture()
        var failure: Throwable? = null
        fixture.coordinator.requestHint(fixture.coordinator.snapshot().session.positionId) {
            failure = it.exceptionOrNull()
        }
        fixture.engine.fail(message = "hint failed")
        val snapshot = fixture.coordinator.snapshot()
        assertThat(failure?.message == "hint failed")
        assertThat(snapshot.phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(snapshot.engineError == null)
        assertThat(snapshot.assistance.hints == 0)
    }
    suite.test("hint request rejects a stale position marker") {
        val fixture = coordinatorFixture()
        assertThrows<IllegalArgumentException> {
            fixture.coordinator.requestHint("stale-position") {}
        }
        assertThat(fixture.engine.requests.isEmpty())
        assertThat(fixture.coordinator.snapshot().assistance.hints == 0)
    }
    suite.test("undo removes the last human move and later bot reply") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        fixture.coordinator.undoLastHumanTurn()
        val snapshot = fixture.coordinator.snapshot()
        assertThat(snapshot.session.moves.isEmpty())
        assertThat(snapshot.assistance.undos == 1)
        assertThat(snapshot.phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("black-player undo retains the preceding bot move") {
        val fixture = coordinatorFixture(coordinatorConfig(humanSide = Side.BLACK))
        fixture.engine.respond(move = "e2e4")
        fixture.coordinator.playHuman(UciMove("e7e5"))
        fixture.engine.respond(move = "g1f3")
        fixture.coordinator.undoLastHumanTurn()
        assertThat(fixture.coordinator.snapshot().session.moves.map { it.move.value } == listOf("e2e4"))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
    }
    suite.test("completed game cannot be undone or reopened") {
        val config = coordinatorConfig(humanSide = Side.BLACK)
        val fixture = coordinatorFixture(config)
        fixture.engine.respond(move = "f2f3")
        fixture.coordinator.playHuman(UciMove("e7e5"))
        fixture.engine.respond(move = "g2g4")
        fixture.coordinator.playHuman(UciMove("d8h4"))
        val completed = fixture.coordinator.snapshot()
        val savedCount = fixture.sink.saved.size

        assertThat(completed.phase == CoordinatorPhase.COMPLETED)
        assertThrows<IllegalArgumentException> { fixture.coordinator.undoLastHumanTurn() }

        val afterRejectedUndo = fixture.coordinator.snapshot()
        assertThat(afterRejectedUndo.session.moves == completed.session.moves)
        assertThat(afterRejectedUndo.session.outcome == completed.session.outcome)
        assertThat(afterRejectedUndo.assistance.undos == 0)
        assertThat(fixture.sink.saved.size == savedCount)
        assertThat(!GameScreenController(fixture.coordinator, config).model().controls.canUndo)
    }
    suite.test("checkpoint restore replays moves and preserves position") {
        val fixture = coordinatorFixture()
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        val checkpoint = fixture.coordinator.checkpoint()
        val restored = GameCoordinator.restore(
            checkpoint, FakeChessEngine(), FakeCheckpointSink(), fixture.time, FakeCoordinatorIds(),
        )
        restored.start()
        assertThat(restored.snapshot().currentFen == fixture.coordinator.snapshot().currentFen)
        assertThat(restored.snapshot().session.moves == fixture.coordinator.snapshot().session.moves)
    }
    suite.test("completed checkpoint restore regenerates exact forced-repetition facts") {
        val config = coordinatorConfig(
            initialFen = "6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1",
        )
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("f6f7"))
        fixture.engine.respond(move = "g8h8")
        fixture.coordinator.playHuman(UciMove("f7f6"))
        fixture.engine.respond(move = "h8g8")
        fixture.coordinator.playHuman(UciMove("f6f7"))
        fixture.engine.respond(move = "g8h8")
        fixture.coordinator.playHuman(UciMove("f7f6"))
        fixture.engine.respond(move = "h8g8")

        val checkpoint = fixture.coordinator.checkpoint()
        val originalFacts = fixture.coordinator.snapshot().session.adjudicationFacts
        assertThat(checkpoint.outcome?.reason == EndReason.REPETITION)
        assertThat(originalFacts?.repetitionAvoidingAlternativesBeforeMove == 0)

        val restored = GameCoordinator.restore(
            checkpoint, FakeChessEngine(), FakeCheckpointSink(), fixture.time, FakeCoordinatorIds(),
        )
        restored.start()
        assertThat(restored.snapshot().session.adjudicationFacts == originalFacts)
        assertThat(restored.snapshot().session.outcome == checkpoint.outcome)
    }
    suite.test("restore falls back to wall time after monotonic reset") {
        val fixture = coordinatorFixture(coordinatorConfig(
            timeControl = TimeControl.Clock(10_000),
        ))
        fixture.time.advance(1_000)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        val checkpoint = fixture.coordinator.checkpoint()
        val rebootedTime = FakeCoordinatorTime(
            monotonic = 100,
            epoch = fixture.time.epoch + 2_000,
        )
        val restored = GameCoordinator.restore(
            checkpoint, FakeChessEngine(), FakeCheckpointSink(), rebootedTime, FakeCoordinatorIds(),
        )
        restored.start()
        assertThat(restored.snapshot().clock.blackRemainingMillis == 8_000L)
    }
    suite.test("tampered checkpoint FEN is rejected") {
        val fixture = coordinatorFixture()
        val tampered = fixture.coordinator.checkpoint().copy(currentFen = ChessPosition.START_FEN.replace(" w ", " b "))
        assertThrows<IllegalArgumentException> {
            GameCoordinator.restore(
                tampered, FakeChessEngine(), FakeCheckpointSink(), fixture.time, FakeCoordinatorIds(),
            )
        }
    }
    suite.test("rated checkpoint containing assistance is rejected") {
        val fixture = coordinatorFixture(coordinatorConfig(mode = GameMode.RATED))
        val tampered = fixture.coordinator.checkpoint().copy(assistance = AssistanceCounts(hints = 1))
        assertThrows<IllegalArgumentException> {
            GameCoordinator.restore(
                tampered, FakeChessEngine(), FakeCheckpointSink(), fixture.time, FakeCoordinatorIds(),
            )
        }
    }
    suite.test("tampered resignation winner is rejected") {
        val fixture = coordinatorFixture()
        fixture.coordinator.resignHuman()
        val checkpoint = fixture.coordinator.checkpoint()
        val tampered = checkpoint.copy(outcome = checkpoint.outcome!!.copy(winner = Side.WHITE, loser = Side.BLACK))
        assertThrows<IllegalArgumentException> {
            GameCoordinator.restore(
                tampered, FakeChessEngine(), FakeCheckpointSink(), fixture.time, FakeCoordinatorIds(),
            )
        }
    }
    suite.test("white orientation maps top-left to a8") {
        assertThat(BoardOrientation.WHITE_AT_BOTTOM.squareAt(0, 0) == Square.parse("a8"))
        assertThat(BoardOrientation.WHITE_AT_BOTTOM.displayCoordinates(Square.parse("h1")) == 7 to 7)
    }
    suite.test("black orientation maps top-left to h1") {
        assertThat(BoardOrientation.BLACK_AT_BOTTOM.squareAt(0, 0) == Square.parse("h1"))
        assertThat(BoardOrientation.BLACK_AT_BOTTOM.displayCoordinates(Square.parse("a8")) == 7 to 7)
    }
    suite.test("tap-select then tap-target submits a legal move") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2"))).state
        val result = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e4")))
        assertThat((result.action as BoardAction.SubmitMove).move == UciMove("e2e4"))
        assertThat(result.state.selected == null)
    }
    suite.test("tapping another friendly piece changes selection") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2"))).state
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("g1"))).state
        assertThat(state.selected == Square.parse("g1"))
    }
    suite.test("tapping the selected square deselects it") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2"))).state
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2"))).state
        assertThat(state.selected == null)
    }
    suite.test("illegal tap target retains selection") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2"))).state
        val result = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e5")))
        assertThat(result.action == null && result.state.selected == Square.parse("e2"))
        assertThat(result.state.selfCheckWarning == null)
    }
    suite.test("moving a pinned piece identifies the king and line attacker") {
        val position = ChessPosition.fromFen("k3r3/8/8/8/8/8/4R3/4K3 w - - 0 1")
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("e2")),
        ).state
        val warning = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("f2")),
        ).state.selfCheckWarning
        assertThat(warning?.reason == SelfCheckWarningReason.MOVE_EXPOSES_KING)
        assertThat(warning?.kingSquare == Square.parse("e1"))
        assertThat(warning?.unsafeKingSquare == Square.parse("e1"))
        assertThat(warning?.attackerSquares == setOf(Square.parse("e8")))
    }
    suite.test("castling through check identifies the transit square and attacker") {
        val position = ChessPosition.fromFen("k4r2/8/8/8/8/8/8/4K2R w K - 0 1")
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("e1")),
        ).state
        val warning = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("g1")),
        ).state.selfCheckWarning
        assertThat(warning?.reason == SelfCheckWarningReason.CASTLE_THROUGH_CHECK)
        assertThat(warning?.kingSquare == Square.parse("e1"))
        assertThat(warning?.unsafeKingSquare == Square.parse("f1"))
        assertThat(warning?.attackerSquares == setOf(Square.parse("f8")))
    }
    suite.test("castling into check identifies the destination and attacker") {
        val position = ChessPosition.fromFen("k5r1/8/8/8/8/8/8/4K2R w K - 0 1")
        val warning = SelfCheckDiagnostics.forAttempt(
            position,
            Square.parse("e1"),
            Square.parse("g1"),
        )
        assertThat(warning?.reason == SelfCheckWarningReason.CASTLE_INTO_CHECK)
        assertThat(warning?.unsafeKingSquare == Square.parse("g1"))
        assertThat(warning?.attackerSquares == setOf(Square.parse("g8")))
    }
    suite.test("castling while checked identifies the current king square and attacker") {
        val position = ChessPosition.fromFen("k3r3/8/8/8/8/8/8/4K2R w K - 0 1")
        val warning = SelfCheckDiagnostics.forAttempt(
            position,
            Square.parse("e1"),
            Square.parse("g1"),
        )
        assertThat(warning?.reason == SelfCheckWarningReason.CASTLE_WHILE_IN_CHECK)
        assertThat(warning?.unsafeKingSquare == Square.parse("e1"))
        assertThat(warning?.attackerSquares == setOf(Square.parse("e8")))
    }
    suite.test("drag and drop submits a legal move") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.DragStarted(Square.parse("g1"))).state
        val result = BoardInteractionReducer.reduce(context, state, BoardEvent.Dropped(Square.parse("f3")))
        assertThat((result.action as BoardAction.SubmitMove).move == UciMove("g1f3"))
        assertThat(result.state.draggingFrom == null)
    }
    suite.test("invalid drop snaps back while retaining source selection") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.DragStarted(Square.parse("e2"))).state
        val result = BoardInteractionReducer.reduce(context, state, BoardEvent.Dropped(Square.parse("e5")))
        assertThat(result.action == null)
        assertThat(result.state.selected == Square.parse("e2") && result.state.draggingFrom == null)
    }
    suite.test("noninteractive board ignores moves but still allows flipping") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(position, false)
        val state = BoardInteractionState.initial(position, Side.WHITE)
        val ignored = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("e2")))
        assertThat(ignored.state.selected == null)
        val flipped = BoardInteractionReducer.reduce(context, state, BoardEvent.FlipBoard)
        assertThat(flipped.state.orientation == BoardOrientation.BLACK_AT_BOTTOM)
    }
    suite.test("bot-thinking preselection accepts only the human side and never submits") {
        val position = ChessRules.apply(ChessPosition.starting(), UciMove("e2e4"))
        val context = BoardInteractionContext(
            position = position,
            interactive = false,
            selectionSide = Side.WHITE,
            preselectionEnabled = true,
        )
        var state = BoardInteractionState.initial(position, Side.WHITE)
        val selected = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("g1")),
        )
        assertThat(selected.action == null)
        assertThat(selected.state.selected == Square.parse("g1") && selected.state.preselected)

        state = selected.state
        val opponentPiece = BoardInteractionReducer.reduce(
            context,
            state,
            BoardEvent.TapSquare(Square.parse("g8")),
        )
        assertThat(opponentPiece.action == null && opponentPiece.state.selected == null)
    }
    suite.test("selection-only events never turn a highlighted destination into a pre-move") {
        val position = ChessPosition.starting()
        val context = BoardInteractionContext(
            position = position,
            interactive = true,
            selectionSide = Side.WHITE,
        )
        val selected = BoardInteractionReducer.reduce(
            context,
            BoardInteractionState.initial(position, Side.WHITE),
            BoardEvent.PreselectSquare(Square.parse("g1")),
        )
        val destination = BoardInteractionReducer.reduce(
            context,
            selected.state,
            BoardEvent.PreselectSquare(Square.parse("f3")),
        )
        assertThat(selected.state.selected == Square.parse("g1"))
        assertThat(destination.action == null && destination.state.selected == null)
    }
    suite.test("preselection survives the bot move when the piece remains on its square") {
        val before = ChessRules.apply(ChessPosition.starting(), UciMove("e2e4"))
        val after = ChessRules.apply(before, UciMove("e7e5"))
        val waiting = BoardInteractionState.initial(before, Side.WHITE).copy(
            selected = Square.parse("g1"),
            preselected = true,
        )
        val ready = BoardInteractionReducer.reconcile(
            BoardInteractionContext(
                position = after,
                interactive = true,
                selectionSide = Side.WHITE,
            ),
            waiting,
        )
        assertThat(ready.selected == Square.parse("g1") && !ready.preselected)
    }
    suite.test("preselection is cleared when the bot captures the selected piece") {
        val before = ChessPosition.fromFen("4k3/8/8/1b6/8/8/4R3/4K3 b - - 0 1")
        val after = ChessRules.apply(before, UciMove("b5e2"))
        val waiting = BoardInteractionState.initial(before, Side.WHITE).copy(
            selected = Square.parse("e2"),
            preselected = true,
        )
        val ready = BoardInteractionReducer.reconcile(
            BoardInteractionContext(
                position = after,
                interactive = true,
                selectionSide = Side.WHITE,
            ),
            waiting,
        )
        assertThat(ready.selected == null && !ready.preselected)
    }
    suite.test("promotion requires an explicit piece choice") {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val context = BoardInteractionContext(position, true)
        var state = BoardInteractionState.initial(position, Side.WHITE)
        state = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("a7"))).state
        val pending = BoardInteractionReducer.reduce(context, state, BoardEvent.TapSquare(Square.parse("a8")))
        assertThat(pending.action == null)
        assertThat(pending.state.promotionPrompt?.choices == listOf(
            PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT,
        ))
        val chosen = BoardInteractionReducer.reduce(context, pending.state, BoardEvent.PromotionChosen(PieceType.KNIGHT))
        assertThat((chosen.action as BoardAction.SubmitMove).move == UciMove("a7a8n"))
    }
    suite.test("promotion dialog blocks unrelated board events") {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val context = BoardInteractionContext(position, true)
        val pending = BoardInteractionState.initial(position, Side.WHITE).copy(
            promotionPrompt = PromotionPrompt(
                Square.parse("a7"), Square.parse("a8"),
                listOf(PieceType.QUEEN, PieceType.KNIGHT),
            ),
        )
        val result = BoardInteractionReducer.reduce(context, pending, BoardEvent.TapSquare(Square.parse("e1")))
        assertThat(result.state == pending && result.action == null)
    }
    suite.test("new position reconciles away stale selection and promotion") {
        val before = ChessPosition.starting()
        val after = ChessRules.apply(before, UciMove("e2e4"))
        val stale = BoardInteractionState.initial(before, Side.WHITE).copy(
            selected = Square.parse("e2"),
            promotionPrompt = PromotionPrompt(Square.parse("a7"), Square.parse("a8"), listOf(PieceType.QUEEN)),
            selfCheckWarning = SelfCheckWarning(
                kingSquare = Square.parse("e1"),
                unsafeKingSquare = Square.parse("e1"),
                attackerSquares = setOf(Square.parse("e8")),
                reason = SelfCheckWarningReason.MOVE_EXPOSES_KING,
            ),
        )
        val reconciled = BoardInteractionReducer.reconcile(BoardInteractionContext(after, false), stale)
        assertThat(
            reconciled.selected == null && reconciled.promotionPrompt == null &&
                reconciled.selfCheckWarning == null,
        )
        assertThat(reconciled.orientation == stale.orientation)
    }
    suite.test("presenter produces 64 display-ordered cells") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val interaction = BoardInteractionState.initial(ChessPosition.starting(), Side.WHITE)
        val screen = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
        assertThat(screen.cells.size == 64)
        assertThat(screen.cells.first().square == Square.parse("a8"))
        assertThat(screen.interactive && screen.status == BoardStatus.HUMAN_TURN)
    }
    suite.test("presenter carries a hint move for the board overlay") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val interaction = BoardInteractionState.initial(ChessPosition.starting(), Side.WHITE)
        val hint = BoardMoveArrow(Square.parse("e2"), Square.parse("e4"))
        val screen = BoardPresenter.present(
            fixture.coordinator.snapshot(),
            config,
            interaction,
            hintMove = hint,
        )
        assertThat(screen.hintMove == hint)
    }
    suite.test("review presenter replays a prefix without enabling board input") {
        val moves = listOf(UciMove("e2e4"), UciMove("e7e5"), UciMove("g1f3"))
        val screen = BoardPresenter.presentReview(
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            humanSide = Side.WHITE,
            orientation = BoardOrientation.BLACK_AT_BOTTOM,
        )
        assertThat(screen.cells.size == 64)
        assertThat(screen.cells.first().square == Square.parse("h1"))
        assertThat(screen.cells.single { it.square == Square.parse("f3") }.piece?.type == PieceType.KNIGHT)
        assertThat(screen.cells.filter { it.lastMove }.map { it.square }.toSet() ==
            setOf(Square.parse("g1"), Square.parse("f3")))
        assertThat(!screen.interactive && !screen.preselectionEnabled)
        assertThat(screen.plyCount == 3 && screen.moveMotion == null)
    }
    suite.test("presenter marks quiet legal targets from selection") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val interaction = BoardInteractionState.initial(ChessPosition.starting(), Side.WHITE).copy(
            selected = Square.parse("e2"),
        )
        val screen = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
        assertThat(screen.cells.single { it.square == Square.parse("e3") }.target == TargetKind.QUIET)
        assertThat(screen.cells.single { it.square == Square.parse("e4") }.target == TargetKind.QUIET)
    }
    suite.test("presenter maps a self-check warning onto king attacker and unsafe cells") {
        val fen = "k4r2/8/8/8/8/8/8/4K2R w K - 0 1"
        val position = ChessPosition.fromFen(fen)
        val config = coordinatorConfig(initialFen = fen)
        val fixture = coordinatorFixture(config)
        val warning = requireNotNull(
            SelfCheckDiagnostics.forAttempt(position, Square.parse("e1"), Square.parse("g1")),
        )
        val interaction = BoardInteractionState.initial(position, Side.WHITE).copy(
            selected = Square.parse("e1"),
            selfCheckWarning = warning,
        )
        val screen = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
        val king = screen.cells.single { it.square == Square.parse("e1") }
        val transit = screen.cells.single { it.square == Square.parse("f1") }
        val attacker = screen.cells.single { it.square == Square.parse("f8") }
        assertThat(king.selfCheckKing && king.accessibility.selfCheckKing)
        assertThat(transit.selfCheckUnsafe && transit.accessibility.selfCheckUnsafe)
        assertThat(attacker.selfCheckAttacker && attacker.accessibility.selfCheckAttacker)
    }
    suite.test("presenter marks capture target and accessible label") {
        val fen = "4k3/8/3p4/4P3/8/8/8/4K3 w - - 0 1"
        val config = coordinatorConfig().copy(initialFen = fen)
        val fixture = coordinatorFixture(config)
        val position = ChessPosition.fromFen(fen)
        val interaction = BoardInteractionState.initial(position, Side.WHITE).copy(selected = Square.parse("e5"))
        val cell = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
            .cells.single { it.square == Square.parse("d6") }
        assertThat(cell.target == TargetKind.CAPTURE)
        assertThat(cell.accessibility.target == TargetKind.CAPTURE)
    }
    suite.test("threat indication highlights only attacked player pieces and labels them") {
        val fen = "4k3/8/8/3q4/8/3N4/8/4K3 w - - 0 1"
        val config = coordinatorConfig().copy(initialFen = fen)
        val fixture = coordinatorFixture(config)
        val position = ChessPosition.fromFen(fen)
        val threatened = ThreatIndicators.threatenedPieces(position, Side.WHITE)
        assertThat(threatened == setOf(Square.parse("d3")))

        val interaction = BoardInteractionState.initial(position, Side.WHITE)
        val withoutAid = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
        assertThat(withoutAid.cells.none { it.threatened })

        val withAid = BoardPresenter.present(
            fixture.coordinator.snapshot(),
            config,
            interaction,
            threatIndicationEnabled = true,
        )
        val knight = withAid.cells.single { it.square == Square.parse("d3") }
        assertThat(knight.threatened)
        assertThat(knight.accessibility.piece?.type == PieceType.KNIGHT)
        assertThat(knight.accessibility.piece?.side == Side.WHITE)
        assertThat(knight.accessibility.threatened)
        assertThat(withAid.cells.single { it.square == Square.parse("e1") }.threatened.not())
    }
    suite.test("presenter highlights both squares of the last move") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        val position = ChessPosition.fromFen(fixture.coordinator.snapshot().currentFen)
        val screen = BoardPresenter.present(
            fixture.coordinator.snapshot(), config, BoardInteractionState.initial(position, Side.WHITE),
        )
        assertThat(screen.cells.filter { it.lastMove }.map { it.square }.toSet() ==
            setOf(Square.parse("e7"), Square.parse("e5")))
        assertThat(screen.plyCount == 2)
        assertThat(screen.moveMotion == BoardMoveMotion(
            ply = 2,
            mover = Side.BLACK,
            pieces = listOf(
                PieceMotion(
                    from = Square.parse("e7"),
                    to = Square.parse("e5"),
                    piece = PieceView(Side.BLACK, PieceType.PAWN, "modern_flat_black_pawn"),
                ),
            ),
        ))
    }
    suite.test("presenter includes both king and rook in castling motion") {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        val config = coordinatorConfig().copy(initialFen = fen)
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("e1g1"))
        val position = ChessPosition.fromFen(fixture.coordinator.snapshot().currentFen)
        val motion = BoardPresenter.present(
            fixture.coordinator.snapshot(), config, BoardInteractionState.initial(position, Side.WHITE),
        ).moveMotion!!

        assertThat(motion.pieces.map { it.from to it.to } == listOf(
            Square.parse("e1") to Square.parse("g1"),
            Square.parse("h1") to Square.parse("f1"),
        ))
        assertThat(motion.pieces.map { it.piece.type } == listOf(PieceType.KING, PieceType.ROOK))
    }
    suite.test("presenter animates a pawn before revealing its promoted piece") {
        val fen = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"
        val config = coordinatorConfig().copy(initialFen = fen)
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("a7a8q"))
        val position = ChessPosition.fromFen(fixture.coordinator.snapshot().currentFen)
        val screen = BoardPresenter.present(
            fixture.coordinator.snapshot(), config, BoardInteractionState.initial(position, Side.WHITE),
        )

        assertThat(screen.moveMotion!!.pieces.single().piece.type == PieceType.PAWN)
        assertThat(screen.cells.single { it.square == Square.parse("a8") }.piece?.type == PieceType.QUEEN)
    }
    suite.test("piece set creates deterministic semantic asset keys") {
        val key = PieceSets.MODERN_FLAT.assetKey(Piece(Side.BLACK, PieceType.QUEEN))
        assertThat(key == "modern_flat_black_queen")
    }
    suite.test("built-in theme and piece identifiers are unique") {
        assertThat(BoardThemes.all.size == 5)
        assertThat(BoardThemes.all.map { it.id }.distinct().size == BoardThemes.all.size)
        assertThat(BoardThemes.all.all { it.lightSquare != it.darkSquare })
        BoardThemes.all.forEach { theme -> assertThat(BoardThemes.fromId(theme.id) == theme) }
        assertThat(BoardThemes.all.all { it.textureId != null })
        listOf(
            "obsidian_glass",
            "arctic_slate",
            "modern_walnut",
            "emerald_court",
            "royal_amethyst",
        ).forEach { retiredId ->
            assertThat(BoardThemes.fromId(retiredId) == BoardThemes.DEFAULT)
        }
        assertThat(BoardThemes.fromId("malachite_court") == BoardThemes.VERDIGRIS_COPPER)
        assertThat(BoardThemes.fromId("removed-or-corrupt") == BoardThemes.DEFAULT)
        assertThat(BoardThemes.fromId(null) == BoardThemes.DEFAULT)
        assertThat(PieceSets.all.map { it.id }.distinct().size == PieceSets.all.size)
    }
    suite.test("phone layout stacks controls below board") {
        val layout = ResponsiveBoardLayout.calculate(412, 915)
        assertThat(layout.widthClass == WindowWidthClass.COMPACT)
        assertThat(layout.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(layout.boardSizeDp == 380)

        val shortPhone = ResponsiveBoardLayout.calculate(360, 640)
        assertThat(shortPhone.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(shortPhone.boardSizeDp == 328)
        assertThat(shortPhone.panelMoveHistoryHeightDp == 160)
    }
    suite.test("tablet layout stays large at the medium boundary and fits landscape clocks") {
        val beforeBoundary = ResponsiveBoardLayout.calculate(599, 1_000)
        val atBoundary = ResponsiveBoardLayout.calculate(600, 1_000)
        assertThat(beforeBoundary.boardSizeDp == 567)
        assertThat(atBoundary.widthClass == WindowWidthClass.MEDIUM)
        assertThat(atBoundary.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(atBoundary.boardSizeDp == 568)

        val portrait = ResponsiveBoardLayout.calculate(706, 1_152)
        assertThat(portrait.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(portrait.boardSizeDp == 640)

        val landscape = ResponsiveBoardLayout.calculate(1_176, 706)
        assertThat(landscape.widthClass == WindowWidthClass.EXPANDED)
        assertThat(landscape.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(landscape.boardSizeDp == 658)
        assertThat(landscape.panelWidthDp == 320)
        assertThat(landscape.panelMoveHistoryHeightDp == 240)
        assertThat(landscape.boardSizeDp <= 706 - landscape.outerPaddingDp * 2)
    }
    suite.test("short landscape keeps a useful board and a narrow scrollable side panel") {
        val expanded = ResponsiveBoardLayout.calculate(891, 347)
        assertThat(expanded.widthClass == WindowWidthClass.EXPANDED)
        assertThat(expanded.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(expanded.outerPaddingDp == 12)
        assertThat(expanded.panelWidthDp == 280)
        assertThat(expanded.boardSizeDp == 323)
        assertThat(expanded.panelMoveHistoryHeightDp == 132)

        val medium = ResponsiveBoardLayout.calculate(640, 296)
        assertThat(medium.widthClass == WindowWidthClass.MEDIUM)
        assertThat(medium.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(medium.panelWidthDp == 240)
        assertThat(medium.boardSizeDp == 272)
        assertThat(medium.panelMoveHistoryHeightDp == 132)

        val compact = ResponsiveBoardLayout.calculate(568, 320)
        assertThat(compact.widthClass == WindowWidthClass.COMPACT)
        assertThat(compact.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(compact.panelWidthDp == 200)
        assertThat(compact.boardSizeDp == 296)
        assertThat(compact.boardSizeDp > 1)
    }
    suite.test("medium portrait keeps controls below a full-width board") {
        val layout = ResponsiveBoardLayout.calculate(800, 1_200)
        assertThat(layout.widthClass == WindowWidthClass.MEDIUM)
        assertThat(layout.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(layout.panelWidthDp == 768)
        assertThat(layout.boardSizeDp == 640)

        val beforeExpandedBoundary = ResponsiveBoardLayout.calculate(839, 1_200)
        val atExpandedBoundary = ResponsiveBoardLayout.calculate(840, 1_200)
        assertThat(beforeExpandedBoundary.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(beforeExpandedBoundary.boardSizeDp == 640)
        assertThat(atExpandedBoundary.widthClass == WindowWidthClass.EXPANDED)
        assertThat(atExpandedBoundary.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(atExpandedBoundary.boardSizeDp == 640)

        val largePortrait = ResponsiveBoardLayout.calculate(1_024, 1_366)
        assertThat(largePortrait.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(largePortrait.boardSizeDp == 640)

        val beforeSideTransition = ResponsiveBoardLayout.calculate(1_031, 1_400)
        val atSideTransition = ResponsiveBoardLayout.calculate(1_032, 1_400)
        assertThat(beforeSideTransition.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(beforeSideTransition.boardSizeDp == 640)
        assertThat(atSideTransition.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(atSideTransition.boardSizeDp == 640)
    }
    suite.test("SAN formats a quiet pawn move") {
        assertThat(SanNotation.format(ChessPosition.starting(), UciMove("e2e4")) == "e4")
    }
    suite.test("SAN formats a knight move") {
        assertThat(SanNotation.format(ChessPosition.starting(), UciMove("g1f3")) == "Nf3")
    }
    suite.test("SAN formats pawn capture") {
        val position = ChessPosition.fromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
        assertThat(SanNotation.format(position, UciMove("e4d5")) == "exd5")
    }
    suite.test("SAN formats castling") {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        assertThat(SanNotation.format(position, UciMove("e1g1")) == "O-O")
    }
    suite.test("SAN formats promotion with check") {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        assertThat(SanNotation.format(position, UciMove("a7a8q")) == "a8=Q+")
    }
    suite.test("SAN formats checkmate") {
        val position = ChessAdapter.replay(
            ChessPosition.START_FEN,
            listOf("f2f3", "e7e5", "g2g4").map(::UciMove),
        )
        assertThat(SanNotation.format(position, UciMove("d8h4")) == "Qh4#")
    }
    suite.test("SAN disambiguates pieces by file") {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/8/3N3N/4K3 w - - 0 1")
        assertThat(SanNotation.format(position, UciMove("d2f3")) == "Ndf3")
    }
    suite.test("SAN disambiguates pieces by rank") {
        val position = ChessPosition.fromFen("4k3/8/8/8/8/R7/8/R3K3 w - - 0 1")
        assertThat(SanNotation.format(position, UciMove("a1a2")) == "R1a2")
    }
    suite.test("screen controller submits board actions to coordinator") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val controller = GameScreenController(fixture.coordinator, config)
        controller.boardEvent(BoardEvent.TapSquare(Square.parse("e2")))
        val model = controller.boardEvent(BoardEvent.TapSquare(Square.parse("e4")))
        assertThat(fixture.engine.requests.size == 1)
        assertThat(model.board.phase == CoordinatorPhase.BOT_THINKING)
    }
    suite.test("screen controller carries a bot-thinking preselection into the human turn") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val controller = GameScreenController(fixture.coordinator, config)
        controller.boardEvent(BoardEvent.TapSquare(Square.parse("e2")))
        controller.boardEvent(BoardEvent.TapSquare(Square.parse("e4")))

        val waiting = controller.boardEvent(BoardEvent.TapSquare(Square.parse("g1")))
        assertThat(waiting.board.phase == CoordinatorPhase.BOT_THINKING)
        assertThat(waiting.board.interaction.selected == Square.parse("g1"))
        assertThat(waiting.board.cells.none { it.target != null })
        assertThat(fixture.engine.requests.size == 1)

        fixture.engine.respond(move = "e7e5")
        val ready = controller.model()
        assertThat(ready.board.phase == CoordinatorPhase.HUMAN_TURN)
        assertThat(ready.board.interaction.selected == Square.parse("g1"))
        assertThat(ready.board.cells.single { it.square == Square.parse("f3") }.target == TargetKind.QUIET)

        controller.boardEvent(BoardEvent.TapSquare(Square.parse("f3")))
        assertThat(fixture.engine.requests.size == 2)
    }
    suite.test("screen controller produces SAN move-history rows") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        val model = GameScreenController(fixture.coordinator, config).model()
        val row = model.history.single()
        val white = requireNotNull(row.white)
        val black = requireNotNull(row.black)
        assertThat(row.moveNumber == 1)
        assertThat(white.notation == "e4" && white.piece == PieceType.PAWN)
        assertThat(black.notation == "e5" && black.piece == PieceType.PAWN)
        assertThat(white.accessibility.movingPiece == PieceType.PAWN)
        assertThat(white.accessibility.from.algebraic == "e2")
        assertThat(white.accessibility.to.algebraic == "e4")
        assertThat(model.capturedMaterial.white.totalValue == 0)
        assertThat(model.capturedMaterial.black.totalValue == 0)
        assertThat(model.capturedMaterial.lead == CaptureScoreLead(null, 0))
    }
    suite.test("screen controller reuses timeline presentation across clock-only refreshes") {
        val config = coordinatorConfig(timeControl = TimeControl.Clock(10_000))
        val fixture = coordinatorFixture(config)
        val controller = GameScreenController(fixture.coordinator, config)
        val initial = controller.model()

        fixture.time.advance(250)
        val clockRefresh = controller.tick()
        assertThat(initial.history === clockRefresh.history)
        assertThat(initial.capturedMaterial === clockRefresh.capturedMaterial)

        fixture.coordinator.playHuman(UciMove("e2e4"))
        val afterMove = controller.model()
        assertThat(afterMove.history !== clockRefresh.history)
        assertThat(afterMove.capturedMaterial !== clockRefresh.capturedMaterial)

        fixture.time.advance(250)
        val botClockRefresh = controller.tick()
        assertThat(afterMove.history === botClockRefresh.history)
        assertThat(afterMove.capturedMaterial === botClockRefresh.capturedMaterial)
    }
    suite.test("move history identifies pieces for castling and promotion") {
        val castling = GameHistoryPresenter.present(
            "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1",
            listOf(UciMove("e1g1")),
        ).history.single().white!!
        assertThat(castling.piece == PieceType.KING)
        assertThat(castling.promotedTo == null)
        assertThat(castling.notation == "O-O")
        assertThat(castling.accessibility.castleSide == CastleSide.KING_SIDE)

        val promotion = GameHistoryPresenter.present(
            "4k3/P7/8/8/8/8/8/4K3 w - - 0 1",
            listOf(UciMove("a7a8q")),
        ).history.single().white!!
        assertThat(promotion.piece == PieceType.PAWN)
        assertThat(promotion.promotedTo == PieceType.QUEEN)
        assertThat(promotion.notation == "a8=Q+")
        assertThat(promotion.accessibility.promotedTo == PieceType.QUEEN)
    }
    suite.test("move history respects Black-to-move FEN numbering") {
        val timeline = GameHistoryPresenter.present(
            "4k3/8/8/8/8/8/8/4K3 b - - 0 23",
            listOf(UciMove("e8e7"), UciMove("e1e2")),
        )
        assertThat(timeline.history.map { it.moveNumber } == listOf(23, 24))
        assertThat(timeline.history[0].white == null)
        assertThat(timeline.history[0].black?.piece == PieceType.KING)
        assertThat(timeline.history[1].white?.piece == PieceType.KING)
        assertThat(timeline.history[1].black == null)
    }
    suite.test("captured material uses conventional values for both sides") {
        val moves = listOf("e2e4", "d7d5", "e4d5", "d8d5").map(::UciMove)
        val material = GameHistoryPresenter.present(ChessPosition.START_FEN, moves).capturedMaterial
        assertThat(material.white.pieces == listOf(PieceType.PAWN))
        assertThat(material.black.pieces == listOf(PieceType.PAWN))
        assertThat(material.white.totalValue == 1 && material.black.totalValue == 1)
        assertThat(material.lead == CaptureScoreLead(null, 0))
        assertThat(material.white.pieces.count { it == PieceType.PAWN } == 1)
        assertThat(material.lead == CaptureScoreLead(null, 0))
    }
    suite.test("captured material handles en passant and capture-promotion") {
        val enPassant = GameHistoryPresenter.present(
            "4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1",
            listOf(UciMove("e5d6")),
        )
        assertThat(enPassant.capturedMaterial.white.pieces == listOf(PieceType.PAWN))
        assertThat(enPassant.capturedMaterial.white.totalValue == 1)
        assertThat(
            enPassant.history.single().white!!.accessibility.let { facts ->
                facts.capturedSide == Side.BLACK &&
                    facts.capturedPiece == PieceType.PAWN &&
                    facts.capturedSquare?.algebraic == "d5" &&
                    facts.enPassant &&
                    facts.to.algebraic == "d6"
            },
        )

        val promotion = GameHistoryPresenter.present(
            "1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1",
            listOf(UciMove("a7b8q")),
        )
        assertThat(promotion.capturedMaterial.white.pieces == listOf(PieceType.ROOK))
        assertThat(promotion.capturedMaterial.white.totalValue == 5)
        assertThat(promotion.capturedMaterial.lead == CaptureScoreLead(Side.WHITE, 5))
        assertThat(promotion.history.single().white!!.promotedTo == PieceType.QUEEN)
        assertThat(promotion.history.single().white!!.accessibility.capturedPiece == PieceType.ROOK)
    }
    suite.test("captured material recomputes from truncated undo history") {
        val moves = listOf("e2e4", "d7d5", "e4d5", "d8d5").map(::UciMove)
        val beforeUndo = GameHistoryPresenter.present(ChessPosition.START_FEN, moves).capturedMaterial
        val afterUndo = GameHistoryPresenter.present(ChessPosition.START_FEN, moves.take(2)).capturedMaterial
        val afterResume = GameHistoryPresenter.present(ChessPosition.START_FEN, moves).capturedMaterial
        assertThat(beforeUndo == afterResume)
        assertThat(afterUndo.white.pieces.isEmpty() && afterUndo.black.pieces.isEmpty())
    }
    suite.test("screen controller maps completed outcomes to the local player") {
        val blackConfig = coordinatorConfig(humanSide = Side.BLACK)
        val checkmate = coordinatorFixture(blackConfig)
        checkmate.engine.respond(move = "f2f3")
        checkmate.coordinator.playHuman(UciMove("e7e5"))
        checkmate.engine.respond(move = "g2g4")
        checkmate.coordinator.playHuman(UciMove("d8h4"))

        val winningResult = GameScreenController(checkmate.coordinator, blackConfig).model().result
        assertThat(winningResult == GameResultView(
            playerWon = true,
            playerSide = Side.BLACK,
            winner = Side.BLACK,
            reason = EndReason.CHECKMATE,
            score = GameScore(100, 100, 0),
            rules = blackConfig.rules,
            adjudicationFacts = checkmate.coordinator.snapshot().session.adjudicationFacts,
        ))

        val whiteConfig = coordinatorConfig(humanSide = Side.WHITE)
        val resignation = coordinatorFixture(whiteConfig)
        val losingResult = GameScreenController(resignation.coordinator, whiteConfig).resign().result
        assertThat(losingResult == GameResultView(
            playerWon = false,
            playerSide = Side.WHITE,
            winner = Side.BLACK,
            reason = EndReason.RESIGNATION,
            score = GameScore(0, 100, 0),
            rules = whiteConfig.rules,
            adjudicationFacts = null,
        ))
    }
    suite.test("screen result reports the explicit threat assistance penalty") {
        val blackConfig = coordinatorConfig(humanSide = Side.BLACK)
        val checkmate = coordinatorFixture(
            config = blackConfig,
            initialAssistance = AssistanceCounts(threatIndication = true),
        )
        checkmate.engine.respond(move = "f2f3")
        checkmate.coordinator.playHuman(UciMove("e7e5"))
        checkmate.engine.respond(move = "g2g4")
        checkmate.coordinator.playHuman(UciMove("d8h4"))

        assertThat(
            GameScreenController(
                checkmate.coordinator,
                blackConfig,
                threatIndicationEnabled = true,
            ).model().result?.score == GameScore(95, 100, 5),
        )
    }
    suite.test("screen controller exposes casual controls") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val controls = GameScreenController(fixture.coordinator, config).model().controls
        assertThat(controls.canPause && controls.canHint && controls.canResign)
        assertThat(!controls.canUndo)
    }
    suite.test("screen controller hides rated assistance") {
        val config = coordinatorConfig(mode = GameMode.RATED)
        val fixture = coordinatorFixture(config)
        val controls = GameScreenController(fixture.coordinator, config).model().controls
        assertThat(!controls.canPause && !controls.canHint && !controls.canUndo)
    }
    suite.test("screen controller emits hint analysis effect") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        var effect: GameUiEffect? = null
        val controller = GameScreenController(fixture.coordinator, config, onEffect = { effect = it })
        val model = controller.hint()
        assertThat(effect is GameUiEffect.RequestHintAnalysis)
        assertThat(model.board.interaction.positionMarker == ChessPosition.START_FEN)
        assertThat(fixture.coordinator.snapshot().assistance.hints == 0)
    }
    suite.test("effect callback may publish a persistent UI message") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        lateinit var controller: GameScreenController
        controller = GameScreenController(fixture.coordinator, config, onEffect = {
            controller.showMessage("Candidate move: e4")
        })
        assertThat(controller.hint().transientNotice == GameNotice.External("Candidate move: e4"))
    }
    suite.test("screen controller presents a hint move arrow") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val controller = GameScreenController(fixture.coordinator, config)
        controller.showHint("Engine suggests e4", UciMove("e2e4"))
        assertThat(
            controller.model().board.hintMove ==
                BoardMoveArrow(Square.parse("e2"), Square.parse("e4")),
        )
        controller.showMessage("Hint unavailable")
        assertThat(controller.model().board.hintMove == null)
    }
    suite.test("clock view formats untimed and low-time states") {
        assertThat(GameScreenController.clockView(null, true).text == "∞")
        val low = GameScreenController.clockView(9_950, true)
        assertThat(low.text == "9.9" && low.lowTime && low.active)
        assertThat(GameScreenController.clockView(65_000, false).text == "1:05")
    }
    suite.test("screen controller switches visual contracts without changing game") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val controller = GameScreenController(fixture.coordinator, config)
        val before = controller.model()
        BoardThemes.all.forEach { theme ->
            val themed = controller.selectTheme(theme)
            assertThat(themed.board.theme == theme)
            assertThat(themed.board.positionMarker == before.board.positionMarker)
            assertThat(themed.history == before.history)
            assertThat(themed.board.interaction == before.board.interaction)
        }
        var model = controller.selectTheme(BoardThemes.VERDIGRIS_COPPER)
        model = controller.selectPieceSet(PieceSets.SCULPTED)
        assertThat(model.board.theme == BoardThemes.VERDIGRIS_COPPER)
        assertThat(model.board.pieceSet == PieceSets.SCULPTED)
        assertThat(model.history.isEmpty())
    }

    registerEngineLayerTests(suite)
    registerNativeBridgeTests(suite)
    registerJniFairyEnginePortTestsIfPresent(suite)

    suite.finish()
}
