package com.drawlesschess.shared

import com.drawlesschess.core.DrawlessAdjudicator
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.MaterialScore
import com.drawlesschess.core.MoveAlternative
import com.drawlesschess.core.MoveTransition
import com.drawlesschess.core.PositionFacts
import com.drawlesschess.core.PositionKey
import com.drawlesschess.core.PrincipalVariation
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.engine.GameReviewPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SharedCoreParityTest {
    @Test
    fun startingPositionMatchesEstablishedMoveAndPerftCounts() {
        val position = ChessPosition.starting()

        assertEquals(ChessPosition.START_FEN, position.fen())
        assertEquals(20, ChessRules.legalMoves(position).size)
        assertEquals(400L, ChessAdapter.perft(position, 2))
        assertEquals(8_902L, ChessAdapter.perft(position, 3))
    }

    @Test
    fun kiwipeteExercisesCastlingAndKingSafety() {
        val position = ChessPosition.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )

        assertEquals(48, ChessRules.legalMoves(position).size)
        assertEquals(2_039L, ChessAdapter.perft(position, 2))
    }

    @Test
    fun stalematePresetMeaningIsIdenticalAcrossTargets() {
        val facts = PositionFacts(
            mover = Side.WHITE,
            legalMovesAfter = 0,
            sideToMoveInCheck = false,
            positionOccurrenceCount = 1,
            repetitionAvoidingAlternativesBeforeMove = 1,
            halfmoveClockAfter = 0,
            fiftyMoveAvoidingAlternativesBeforeMove = 1,
            deadPositionAfter = false,
            moveWasCapture = false,
            materialAfter = MaterialScore(1, 1),
            lastCaptureBy = null,
        )
        val adjudicator = DrawlessAdjudicator()

        assertEquals(Side.WHITE, adjudicator.adjudicate(RulesContractV1.drawless(), facts)?.winner)
        assertEquals(Side.BLACK, adjudicator.adjudicate(RulesContractV1.escape(), facts)?.winner)
    }

    @Test
    fun sessionDistinguishesAvoidableAndForcedThirdRepetition() {
        val avoidable = repeatedSession(
            gameId = "avoidable",
            finalAlternatives = listOf(
                alternative("f6g8", "A"),
                alternative("f6h5", "C"),
            ),
        )
        val forced = repeatedSession(
            gameId = "forced",
            finalAlternatives = listOf(alternative("f6g8", "A")),
        )

        assertEquals(EndReason.REPETITION, avoidable.outcome?.reason)
        assertEquals(Side.WHITE, avoidable.outcome?.winner)
        assertEquals(1, avoidable.adjudicationFacts?.repetitionAvoidingAlternativesBeforeMove)
        assertEquals(Side.BLACK, forced.outcome?.winner)
        assertEquals(0, forced.adjudicationFacts?.repetitionAvoidingAlternativesBeforeMove)
    }

    @Test
    fun replayAndSessionUseTheSameExistingSourceFiles() {
        val checkmate = ChessAdapter.replay(
            ChessPosition.START_FEN,
            listOf("f2f3", "e7e5", "g2g4", "d8h4").map(::UciMove),
        )
        val ongoing = GameSession.newGame(
            gameId = "shared-smoke",
            rules = RulesContractV1.drawless(),
            initialPositionKey = PositionKey("start"),
        ).apply(transition("e2e4", Side.WHITE, "after-e4"))

        assertEquals(true, ChessRules.isCheckmate(checkmate))
        assertNull(ongoing.outcome)
        assertEquals(UciMove("e2e4"), ongoing.moves.single().move)
    }

    @Test
    fun appleHostSmokeFacadeExecutesProductionRules() {
        val smoke = SharedCoreSmoke()

        assertEquals(true, smoke.isHealthy())
        assertEquals("20 legal moves • perft(2) 400 • rules v1", smoke.verificationSummary())
    }

    @Test
    fun appleRuntimePlaysThroughProductionCoordinatorAndBoardReducer() {
        val game = SharedGameRuntime()
        try {
            val initial = game.view()
            assertEquals(64, initial.cells.size)
            assertEquals("e2", initial.cells[52].square)
            assertEquals(false, initial.cells[52].selected)
            assertEquals("bN", initial.cells.single { it.square == "b8" }.pieceCode)
            assertEquals("bK", initial.cells.single { it.square == "e8" }.pieceCode)
            assertEquals("wN", initial.cells.single { it.square == "b1" }.pieceCode)
            assertEquals("wK", initial.cells.single { it.square == "e1" }.pieceCode)

            val selected = game.tap(52)
            assertEquals(true, selected.cells[52].selected)
            assertEquals(true, selected.cells[36].legalTarget)

            game.tap(36)
            val afterTurn = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(afterTurn.engineError)
            assertEquals(2, afterTurn.plyCount)
            assertEquals("HUMAN_TURN", afterTurn.phase)
            assertEquals(true, afterTurn.canUndo)
            assertEquals(true, afterTurn.moveHistory.startsWith("1. e4"))

            val undone = game.undo()
            assertEquals(0, undone.plyCount)
            assertEquals("wP", undone.cells[52].pieceCode)
            assertEquals("", undone.moveHistory)
        } finally {
            game.close()
        }
    }

    @Test
    fun appleRuntimeExposesHintPauseResumeAndResignationControls() {
        val game = SharedGameRuntime(initialMillis = 60_000)
        try {
            game.requestHint()
            val hinted = awaitView(game) { it.hintMove != null || it.engineError != null }
            assertNull(hinted.engineError)
            val hintMove = requireNotNull(hinted.hintMove)
            assertTrue(hintMove.matches(Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")))
            assertEquals(hintMove.substring(0, 2), hinted.hintFromSquare)
            assertEquals(hintMove.substring(2, 4), hinted.hintToSquare)
            assertEquals(1, game.checkpointRevision())
            assertEquals("PAUSED", game.pause().phase)
            assertEquals("HUMAN_TURN", game.resume().phase)

            val resigned = game.resign()
            assertEquals("COMPLETED", resigned.phase)
            assertEquals("BLACK", resigned.winner)
            assertEquals("RESIGNATION", resigned.endReason)
        } finally {
            game.close()
        }
    }

    @Test
    fun appleRuntimeCheckpointPayloadRoundTripsAndRestoresTheExactGame() {
        val original = SharedGameRuntime(
            presetId = "escape",
            humanSideId = "white",
            botLevelId = "challenger",
            initialMillis = 180_000,
            incrementMillis = 2_000,
            threatIndicationEnabled = true,
        )
        var restored: SharedGameRuntime? = null
        try {
            original.tap(52)
            original.tap(36)
            val played = awaitView(original) { it.plyCount == 2 || it.engineError != null }
            assertNull(played.engineError)
            val payload = original.checkpointJson()
            val decoded = SharedCheckpointCodec.decode(payload)

            assertEquals(played.gameId, decoded.config.gameId)
            assertEquals("ESCAPE", decoded.config.rules.preset.name)
            assertEquals("challenger", decoded.config.opponentLevelId)
            assertEquals(2, decoded.moves.size)
            assertEquals(decoded, SharedCheckpointCodec.decode(SharedCheckpointCodec.encode(decoded)))

            val playedPieces = played.cells.map { it.pieceCode }
            original.close()
            restored = SharedGameRuntime(checkpointJson = payload)
            val restoredView = restored.view()
            assertEquals(played.gameId, restoredView.gameId)
            assertEquals(played.plyCount, restoredView.plyCount)
            assertEquals(played.moveHistory, restoredView.moveHistory)
            assertEquals(playedPieces, restoredView.cells.map { it.pieceCode })
            assertEquals(true, restoredView.canUndo)
        } finally {
            restored?.close()
            original.close()
        }
    }

    @Test
    fun checkpointCodecPreservesExactForegroundReviewEvidence() {
        val game = SharedGameRuntime()
        var resumed: SharedGameRuntime? = null
        try {
            val checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())
            val root = GameReviewPlanner.playerRoot(
                requestId = "apple-review-prefetch",
                gameId = checkpoint.config.gameId,
                initialFen = checkpoint.config.initialFen,
                moves = checkpoint.moves,
                rules = checkpoint.config.rules,
            )
            val bestMove = UciMove("e2e4")
            val seeded = root.seed(
                EngineResponse(
                    requestId = root.request.requestId,
                    gameId = root.request.gameId,
                    positionId = root.request.positionId,
                    bestMove = bestMove,
                    ponderMove = null,
                    depth = 12,
                    nodes = 4_096,
                    variations = listOf(PrincipalVariation(18, null, listOf(bestMove))),
                    engine = EngineIdentity("apple-test-engine", "stale-build", 2),
                ),
            )
            val withEvidence = checkpoint.copy(reviewPrefetchRoots = listOf(seeded))

            val restored = SharedCheckpointCodec.decode(SharedCheckpointCodec.encode(withEvidence))

            assertEquals(withEvidence, restored)
            assertEquals(listOf(seeded), restored.reviewPrefetchRoots)

            game.close()
            resumed = SharedGameRuntime(checkpointJson = SharedCheckpointCodec.encode(withEvidence))
            val filtered = SharedCheckpointCodec.decode(resumed.checkpointJson())
            assertTrue(filtered.reviewPrefetchRoots.isEmpty())
        } finally {
            resumed?.close()
            game.close()
        }
    }

    @Test
    fun checkpointCodecRejectsNonBooleanAssistanceInsteadOfCoercingIt() {
        val game = SharedGameRuntime()
        try {
            val malformed = game.checkpointJson().replace(
                "\"threatIndication\":false",
                "\"threatIndication\":\"false\"",
            )

            assertFails { SharedCheckpointCodec.decode(malformed) }
        } finally {
            game.close()
        }
    }

    @Test
    fun foregroundReviewEvidenceIsPersistedAndReusedByTheSerializedAppleSession() {
        val game = SharedGameRuntime(botLevelId = "learner")
        try {
            game.setGameForeground(true)
            val openingPrefetch = awaitCheckpoint(game) { checkpoint ->
                checkpoint.reviewPrefetchRoots.any { it.key.ply == 1 }
            }
            assertTrue(openingPrefetch.reviewPrefetchRoots.any { it.key.ply == 1 })

            game.tap(52)
            game.tap(36)
            val played = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(played.engineError)
            // The JVM fixture completes bot analysis synchronously while the shared launch gate
            // is still held; reasserting visibility performs the same post-callback eligibility
            // check that the asynchronous Apple transport reaches after releasing that gate.
            game.setGameForeground(true)
            val duringGame = awaitCheckpoint(game) { checkpoint ->
                checkpoint.reviewPrefetchRoots.any { it.key.ply == 1 } &&
                    checkpoint.reviewPrefetchAdjacentRoots.any { it.key.rootKey.ply == 1 }
            }
            assertTrue(duringGame.reviewPrefetchAdjacentRoots.any { it.key.rootKey.ply == 1 })

            val completed = game.resign()
            assertEquals(true, completed.reviewAvailable)

            game.startReview()
            val reviewed = awaitView(game, timeoutMillis = 30_000) {
                it.reviewSummary != null || it.engineError != null
            }
            assertNull(reviewed.engineError)
            assertEquals(1, reviewed.reviewProgress)
            assertEquals(1, reviewed.reviewTotal)
            assertTrue(requireNotNull(reviewed.reviewSummary).contains("1 player moves"))
            assertEquals(1, reviewed.reviewDetails.lines().size)
        } finally {
            game.close()
        }
    }

    private fun repeatedSession(
        gameId: String,
        finalAlternatives: List<MoveAlternative>,
    ): GameSession {
        var session = GameSession.newGame(gameId, RulesContractV1.drawless(), PositionKey("A"))
        session = session.apply(transition("g1f3", Side.WHITE, "B"))
        session = session.apply(transition("g8f6", Side.BLACK, "A"))
        session = session.apply(transition("f3g1", Side.WHITE, "B"))
        return session.apply(transition("f6g8", Side.BLACK, "A", finalAlternatives))
    }

    private fun alternative(move: String, key: String) = MoveAlternative(
        move = UciMove(move),
        resultingPositionKey = PositionKey(key),
        resultingHalfmoveClock = 0,
    )

    private fun transition(
        move: String,
        mover: Side,
        key: String,
        alternatives: List<MoveAlternative> = listOf(alternative(move, key)),
    ) = MoveTransition(
        move = UciMove(move),
        mover = mover,
        resultingPositionKey = PositionKey(key),
        legalMovesAfter = 1,
        sideToMoveInCheck = false,
        legalAlternativesBeforeMove = alternatives,
        halfmoveClockAfter = 0,
        deadPositionAfter = false,
        moveWasCapture = false,
        materialAfter = MaterialScore(1, 1),
    )

    private fun awaitView(
        game: SharedGameRuntime,
        timeoutMillis: Long = 15_000,
        predicate: (SharedGameView) -> Boolean,
    ): SharedGameView {
        val started = TimeSource.Monotonic.markNow()
        var view = game.view()
        while (!predicate(view) && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            view = game.view()
        }
        assertTrue(predicate(view), "Timed out waiting for runtime state: $view")
        return view
    }

    private fun awaitCheckpoint(
        game: SharedGameRuntime,
        timeoutMillis: Long = 15_000,
        predicate: (CoordinatorCheckpoint) -> Boolean,
    ): CoordinatorCheckpoint {
        val started = TimeSource.Monotonic.markNow()
        var checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())
        while (!predicate(checkpoint) && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())
        }
        assertTrue(predicate(checkpoint), "Timed out waiting for foreground review evidence")
        return checkpoint
    }
}
