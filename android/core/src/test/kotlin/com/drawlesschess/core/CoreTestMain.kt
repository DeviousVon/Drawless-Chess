package com.drawlesschess.core

import java.time.Instant
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
) = PositionFacts(
    mover, legalMoves, inCheck, occurrences, repetitionAvoiding, halfmove,
    fiftyAvoiding, dead, capture, MaterialScore(whiteMaterial, blackMaterial),
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
    engine = EngineIdentity("fairy-stockfish", "test", 1),
)

private fun coordinatorConfig(
    mode: GameMode = GameMode.CASUAL,
    timeControl: TimeControl = TimeControl.Untimed,
    humanSide: Side = Side.WHITE,
) = GameConfig(
    gameId = "coordinator-game",
    initialFen = ChessPosition.START_FEN,
    rules = drawless,
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
): CoordinatorFixture {
    val engine = FakeChessEngine()
    val sink = FakeCheckpointSink()
    val coordinator = GameCoordinator.newGame(
        config, engine, sink, time, FakeCoordinatorIds(),
    )
    coordinator.start()
    return CoordinatorFixture(coordinator, engine, sink, time)
}

fun main() {
    val suite = TestSuite()

    suite.test("ordinary position continues") {
        assertThat(adjudicator.adjudicate(drawless, facts()) == null)
    }
    suite.test("rules presets disable the 50-move limit by default") {
        assertThat(RulesContractV1.drawless().fiftyMove == FiftyMovePolicy.DISABLED)
        assertThat(RulesContractV1.escape().fiftyMove == FiftyMovePolicy.DISABLED)
        assertThat(adjudicator.adjudicate(RulesContractV1.drawless(), facts(halfmove = 100)) == null)
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
        assertThat(adjudicator.adjudicate(drawless, facts(dead = true, capture = true, whiteMaterial = 3))?.winner == Side.WHITE)
    }
    suite.test("equal dead material rewards mover") {
        assertThat(adjudicator.adjudicate(drawless, facts(mover = Side.BLACK, dead = true, capture = true))?.winner == Side.BLACK)
    }
    suite.test("final capture rewards capturer") {
        val rules = RulesContractV1.drawless(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY)
        assertThat(adjudicator.adjudicate(rules, facts(dead = true, capture = true, blackMaterial = 9))?.winner == Side.WHITE)
    }
    suite.test("final capture rejects non-capture transition") {
        val rules = RulesContractV1.drawless(deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY)
        assertThrows<IllegalStateException> { adjudicator.adjudicate(rules, facts(dead = true)) }
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
    }
    suite.test("saved rated game rejects assistance") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g6", Instant.EPOCH, GameMode.RATED, "start-fen", drawless,
                TimeControl.Untimed, emptyList(), EngineIdentity("fairy", "build", 0),
                assistance = AssistanceCounts(hints = 1),
            )
        }
    }
    suite.test("saved casual game accepts assistance") {
        val saved = SavedGameV1(
            "g7", Instant.EPOCH, GameMode.CASUAL, "start-fen", drawless,
            TimeControl.Untimed, emptyList(), EngineIdentity("fairy", "build", 0),
            assistance = AssistanceCounts(undos = 1),
        )
        assertThat(saved.schemaVersion == 1)
    }
    suite.test("untimed save rejects clock snapshots") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g8", Instant.EPOCH, GameMode.CASUAL, "start-fen", drawless,
                TimeControl.Untimed,
                listOf(SavedMoveV1(UciMove("g1f3"), whiteRemainingMillis = 5)),
                EngineIdentity("fairy", "build", 0),
            )
        }
    }
    suite.test("saved result cannot exceed replay history") {
        assertThrows<IllegalArgumentException> {
            SavedGameV1(
                "g9", Instant.EPOCH, GameMode.CASUAL, "start-fen", drawless,
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
        assertThat(fixture.coordinator.snapshot().assistance.hints == 1)
        fixture.engine.respond(pending, "e2e4")
        assertThat(result?.getOrNull()?.bestMove == UciMove("e2e4"))
        assertThat(fixture.coordinator.snapshot().phase == CoordinatorPhase.HUMAN_TURN)
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
        )
        val reconciled = BoardInteractionReducer.reconcile(BoardInteractionContext(after, false), stale)
        assertThat(reconciled.selected == null && reconciled.promotionPrompt == null)
        assertThat(reconciled.orientation == stale.orientation)
    }
    suite.test("presenter produces 64 display-ordered cells") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        val interaction = BoardInteractionState.initial(ChessPosition.starting(), Side.WHITE)
        val screen = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
        assertThat(screen.cells.size == 64)
        assertThat(screen.cells.first().square == Square.parse("a8"))
        assertThat(screen.interactive && screen.statusText == "Your move")
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
    suite.test("presenter marks capture target and accessible label") {
        val fen = "4k3/8/3p4/4P3/8/8/8/4K3 w - - 0 1"
        val config = coordinatorConfig().copy(initialFen = fen)
        val fixture = coordinatorFixture(config)
        val position = ChessPosition.fromFen(fen)
        val interaction = BoardInteractionState.initial(position, Side.WHITE).copy(selected = Square.parse("e5"))
        val cell = BoardPresenter.present(fixture.coordinator.snapshot(), config, interaction)
            .cells.single { it.square == Square.parse("d6") }
        assertThat(cell.target == TargetKind.CAPTURE)
        assertThat(cell.accessibilityLabel.contains("legal capture"))
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
    }
    suite.test("piece set creates deterministic semantic asset keys") {
        val key = PieceSets.MODERN_FLAT.assetKey(Piece(Side.BLACK, PieceType.QUEEN))
        assertThat(key == "modern_flat_black_queen")
    }
    suite.test("built-in theme and piece identifiers are unique") {
        assertThat(BoardThemes.all.map { it.id }.distinct().size == BoardThemes.all.size)
        assertThat(PieceSets.all.map { it.id }.distinct().size == PieceSets.all.size)
    }
    suite.test("phone layout stacks controls below board") {
        val layout = ResponsiveBoardLayout.calculate(412, 915)
        assertThat(layout.widthClass == WindowWidthClass.COMPACT)
        assertThat(layout.controlPlacement == ControlPlacement.BELOW_BOARD)
        assertThat(layout.boardSizeDp == 380)
    }
    suite.test("tablet layout places controls beside board") {
        val layout = ResponsiveBoardLayout.calculate(1_200, 800)
        assertThat(layout.widthClass == WindowWidthClass.EXPANDED)
        assertThat(layout.controlPlacement == ControlPlacement.BESIDE_BOARD)
        assertThat(layout.boardSizeDp == 752)
    }
    suite.test("medium window reserves a stable side panel") {
        val layout = ResponsiveBoardLayout.calculate(800, 1_200)
        assertThat(layout.widthClass == WindowWidthClass.MEDIUM)
        assertThat(layout.panelWidthDp == 260)
        assertThat(layout.boardSizeDp == 468)
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
    suite.test("screen controller produces SAN move-history rows") {
        val config = coordinatorConfig()
        val fixture = coordinatorFixture(config)
        fixture.coordinator.playHuman(UciMove("e2e4"))
        fixture.engine.respond(move = "e7e5")
        val model = GameScreenController(fixture.coordinator, config).model()
        assertThat(model.history == listOf(MoveHistoryRow(1, "e4", "e5")))
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
            reason = EndReason.CHECKMATE,
            explanation = "BLACK wins by checkmate",
        ))

        val whiteConfig = coordinatorConfig(humanSide = Side.WHITE)
        val resignation = coordinatorFixture(whiteConfig)
        val losingResult = GameScreenController(resignation.coordinator, whiteConfig).resign().result
        assertThat(losingResult == GameResultView(
            playerWon = false,
            playerSide = Side.WHITE,
            reason = EndReason.RESIGNATION,
            explanation = "WHITE resigns",
        ))
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
        assertThat(controller.hint().transientMessage == "Candidate move: e4")
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
        var model = controller.selectTheme(BoardThemes.MODERN_WALNUT)
        model = controller.selectPieceSet(PieceSets.SCULPTED)
        assertThat(model.board.theme == BoardThemes.MODERN_WALNUT)
        assertThat(model.board.pieceSet == PieceSets.SCULPTED)
        assertThat(model.history.isEmpty())
    }

    registerEngineLayerTests(suite)
    registerNativeBridgeTests(suite)
    registerJniFairyEnginePortTests(suite)

    suite.finish()
}
