package com.drawlesschess.shared

import com.drawlesschess.core.DrawlessAdjudicator
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineRequest
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
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.MoveClockSnapshot
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.OfflineElo
import com.drawlesschess.core.engine.OfflineRating
import com.drawlesschess.core.engine.RatedResult
import com.drawlesschess.core.presentation.BoardEvent
import com.drawlesschess.core.presentation.BoardInteractionContext
import com.drawlesschess.core.presentation.BoardInteractionReducer
import com.drawlesschess.core.presentation.BoardInteractionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun botDifficultyLadderAndAdaptiveConstantsCompileAsSharedCore() {
        assertEquals(
            listOf(
                "learner" to 550,
                "casual" to 800,
                "challenger" to 1_000,
                "club" to 1_300,
                "expert" to 1_675,
                "master" to 2_100,
                "grandmaster" to 2_550,
            ),
            BotDifficultyCatalog.namedLevels.map { it.id to it.approximateElo },
        )
        assertEquals(500, BotDifficultyCatalog.MINIMUM_ELO)
        assertEquals(2_850, BotDifficultyCatalog.MAXIMUM_ELO)
        assertEquals("adaptive", BotDifficultyCatalog.ADAPTIVE_LEVEL_ID)
        assertEquals(800, BotDifficultyCatalog.ADAPTIVE_STARTING_ELO)
        assertEquals(
            BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
            BotDifficultyCatalog.adaptiveLevel().approximateElo,
        )
    }

    @Test
    fun offlineEloWinAndLossOutcomesCompileAsSharedCore() {
        val upsetWin = OfflineElo.update(
            current = OfflineRating(rating = 1_200, gamesPlayed = 0),
            opponentElo = 1_800,
            result = RatedResult.WIN,
        )
        val evenLoss = OfflineElo.update(
            current = OfflineRating(rating = 1_500, gamesPlayed = 40),
            opponentElo = 1_500,
            result = RatedResult.LOSS,
        )

        assertEquals(OfflineRating(rating = 1_247, gamesPlayed = 1), upsetWin)
        assertTrue(upsetWin.provisional)
        assertEquals(OfflineRating(rating = 1_490, gamesPlayed = 41), evenLoss)
        assertFalse(evenLoss.provisional)
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
    fun appleRuntimePreservesEveryCustomDeadPositionPolicy() {
        listOf(
            "material" to DeadPositionPolicy.MATERIAL_VICTORY,
            "final_capture" to DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
        ).forEach { (id, expected) ->
            val game = SharedGameRuntime(
                presetId = "escape",
                deadPositionId = id,
            )
            try {
                val view = game.view()
                val checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())

                assertEquals(id, view.deadPositionId)
                assertEquals(expected, checkpoint.config.rules.deadPosition)
                assertEquals(RulesContractV1.Preset.ESCAPE, checkpoint.config.rules.preset)
            } finally {
                game.close()
            }
        }
    }

    @Test
    fun applePromotionPickerCanBeCancelledWithoutSubmittingAMove() {
        val position = ChessPosition.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val context = BoardInteractionContext(position = position, interactive = true)
        val initial = BoardInteractionState.initial(position, Side.WHITE)
        val selected = BoardInteractionReducer.reduce(
            context,
            initial,
            BoardEvent.TapSquare(Square.parse("a7")),
        )
        val pending = BoardInteractionReducer.reduce(
            context,
            selected.state,
            BoardEvent.TapSquare(Square.parse("a8")),
        )

        assertEquals(
            listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT),
            pending.state.promotionPrompt?.choices,
        )

        val cancelled = BoardInteractionReducer.reduce(
            context,
            pending.state,
            BoardEvent.PromotionCancelled,
        )

        assertNull(cancelled.action)
        assertNull(cancelled.state.promotionPrompt)
        assertEquals(Square.parse("a7"), cancelled.state.selected)
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
                    variations = listOf(
                        PrincipalVariation(18, null, listOf(bestMove)),
                        PrincipalVariation(
                            scoreCentipawns = null,
                            mateIn = 3,
                            moves = listOf(UciMove("d2d4")),
                            rank = 2,
                        ),
                    ),
                    engine = EngineIdentity("apple-test-engine", "stale-build", 2),
                ),
            )
            val withEvidence = checkpoint.copy(reviewPrefetchRoots = listOf(seeded))

            val restored = SharedCheckpointCodec.decode(SharedCheckpointCodec.encode(withEvidence))

            assertEquals(withEvidence, restored)
            assertEquals(listOf(seeded), restored.reviewPrefetchRoots)

            // Android 1.0.2's JSONObject writer omitted nullable response/PV keys instead of
            // emitting JSON null. Those released checkpoints must retain their review evidence.
            val releasedAndroidPayload = SharedCheckpointCodec.encode(withEvidence)
                .replace("\"ponderMove\":null,", "")
                .replace("\"cp\":null,", "")
                .replace(",\"mate\":null", "")
                .replace(",\"depth\":null", "")
            assertEquals(withEvidence, SharedCheckpointCodec.decode(releasedAndroidPayload))

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
    fun unchangedUiPollingKeepsOneStablePresentationRevision() {
        val game = SharedGameRuntime()
        try {
            val initial = game.presentationRevision()
            repeat(1_000) {
                assertEquals(initial, game.presentationRevision())
            }
            assertEquals(initial, game.presentationRevision())
        } finally {
            game.close()
        }
    }

    @Test
    fun immutableCheckpointSnapshotCarriesSeventySixPlyReviewEvidence() {
        val game = SharedGameRuntime(humanSideId = "black")
        val base = try {
            SharedCheckpointCodec.decode(game.checkpointJson())
        } finally {
            game.close()
        }
        val moves = buildList {
            repeat(19) {
                add(UciMove("g1f3"))
                add(UciMove("g8f6"))
                add(UciMove("f3g1"))
                add(UciMove("f6g8"))
            }
        }
        val position = ChessAdapter.replay(base.config.initialFen, moves)
        val plan = GameReviewPlanner.playerPlan(
            gameId = base.config.gameId,
            initialFen = base.config.initialFen,
            moves = moves,
            rules = base.config.rules,
            playerSide = Side.BLACK,
        )
        val engineIdentity = EngineIdentity("snapshot-stress", "2", 2)
        val exactRoots = plan.roots.map { root ->
            root.seed(stressReviewResponse(root.request, engineIdentity))
        }
        val adjacentRoots = plan.roots.map { root ->
            val adjacent = GameReviewPlanner.adjacentRoot(
                requestId = "snapshot-adjacent-${root.ply}",
                root = root,
                playedMove = moves[root.ply - 1],
            )
            adjacent.seed(stressReviewResponse(adjacent.request, engineIdentity))
        }
        val checkpoint = base.copy(
            revision = 152,
            moves = moves,
            currentFen = position.fen(),
            moveClocks = moves.indices.map { index ->
                MoveClockSnapshot(index + 1, null, null)
            },
            reviewPrefetchRoots = exactRoots,
            reviewPrefetchAdjacentRoots = adjacentRoots,
        )

        val snapshot = SharedCheckpointSnapshot(checkpoint)
        val payload = snapshot.encodeJson()
        val decoded = SharedCheckpointCodec.decode(payload)

        assertEquals(76, moves.size)
        assertEquals(38, exactRoots.size)
        assertEquals(1_444, exactRoots.sumOf { it.key.movesBefore.size })
        assertEquals(checkpoint.config.gameId, snapshot.gameId)
        assertEquals(152, snapshot.revision)
        assertTrue(payload.length > 50_000, "Stress checkpoint unexpectedly small: ${payload.length}")
        assertEquals(checkpoint, decoded)
    }

    @Test
    fun checkpointSnapshotRemainsExactAfterRuntimeAdvances() {
        val game = SharedGameRuntime()
        try {
            val captured = game.checkpointSnapshot()
            assertEquals(0, captured.revision)

            val completed = game.resign()
            assertEquals("COMPLETED", completed.phase)
            assertTrue(game.checkpointRevision() > captured.revision)

            val encodedCapture = SharedCheckpointCodec.decode(captured.encodeJson())
            assertEquals(0, encodedCapture.revision)
            assertNull(encodedCapture.outcome)
        } finally {
            game.close()
        }
    }

    @Test
    fun immediateResignationCompletesAnEmptyReviewWithoutEngineWork() {
        val game = SharedGameRuntime()
        try {
            val completed = game.resign()
            assertEquals("COMPLETED", completed.phase)
            assertEquals(0, completed.plyCount)
            assertTrue(completed.reviewAvailable)

            game.ensureReviewPreparationStarted()
            val accepted = game.reviewReuseDiagnosticsForTesting()
            assertTrue(accepted.contains("reviewGeneration=1"), accepted)
            game.ensureReviewPreparationStarted()
            assertTrue(
                game.reviewReuseDiagnosticsForTesting().contains("reviewGeneration=1"),
                "The non-projecting automatic start created a second generation",
            )
            val reviewed = awaitView(game) {
                it.reviewSummary != null || it.reviewError != null
            }
            assertNull(reviewed.reviewError)
            assertEquals(0, reviewed.reviewProgress)
            assertEquals(0, reviewed.reviewTotal)
            assertTrue(reviewed.reviewMoves.isEmpty())
            assertTrue(requireNotNull(reviewed.reviewSummary).contains("0 player moves"))
        } finally {
            game.close()
        }
    }

    @Test
    fun foregroundReviewEvidenceDoesNotInvalidateTheVisiblePresentation() {
        val game = SharedGameRuntime()
        try {
            val initialPresentation = game.presentationRevision()
            val initialCheckpoint = game.checkpointRevision()

            game.setGameForeground(true)

            val checkpoint = awaitCheckpoint(game) { value ->
                value.reviewPrefetchRoots.any { it.key.ply == 1 }
            }
            assertTrue(checkpoint.reviewPrefetchRoots.any { it.key.ply == 1 })
            assertTrue(game.checkpointRevision() > initialCheckpoint)
            assertEquals(initialPresentation, game.presentationRevision())
        } finally {
            game.close()
        }
    }

    @Test
    fun visibleCoordinatorAsyncAndInteractionChangesAdvancePresentationRevision() {
        val game = SharedGameRuntime(initialMillis = 60_000)
        try {
            val initial = game.presentationRevision()

            val selected = game.tap(52)
            assertTrue(selected.cells[52].selected)
            val afterSelection = game.presentationRevision()
            assertNotEquals(initial, afterSelection)

            game.requestHint()
            val afterHint = game.presentationRevision()
            assertNotEquals(afterSelection, afterHint)

            game.pause()
            val afterPause = game.presentationRevision()
            assertNotEquals(afterHint, afterPause)

            game.resume()
            val afterResume = game.presentationRevision()
            assertNotEquals(afterPause, afterResume)

            game.tap(36)
            val played = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(played.engineError)
            val afterTurn = game.presentationRevision()
            assertNotEquals(afterResume, afterTurn)

            game.resign()
            val beforeReview = game.presentationRevision()
            game.startReview()
            val afterReview = game.presentationRevision()
            assertNotEquals(beforeReview, afterReview)
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
                    checkpoint.hasMaterializableReviewEvidence(1, UciMove("e2e4"))
            }
            assertTrue(duringGame.hasMaterializableReviewEvidence(1, UciMove("e2e4")))
            val acceptedRootKeys = duringGame.reviewPrefetchRoots.map { it.key }.toSet()
            val acceptedAdjacentKeys = duringGame.reviewPrefetchAdjacentRoots.map { it.key }.toSet()
            val preparedDuringPlay = awaitReviewDiagnostics(game) { diagnostics ->
                diagnostics.contains("foregroundMaterialized=1")
            }
            assertTrue(preparedDuringPlay.contains("reviewGeneration=0"), preparedDuringPlay)

            val completed = game.resign()
            assertEquals(true, completed.reviewAvailable)
            // Swift marks the completed route non-foreground before starting the runtime-owned
            // review. That lifecycle transition may cancel or trim the unplayed current root, but
            // never an accepted component belonging to a decision in the completed game.
            game.setGameForeground(false)
            val terminalCheckpoint = SharedCheckpointCodec.decode(game.checkpointJson())
            val playedRootKeys = acceptedRootKeys.filter { it.ply <= completed.plyCount }
            val playedAdjacentKeys = acceptedAdjacentKeys.filter {
                it.rootKey.ply <= completed.plyCount
            }
            assertTrue(terminalCheckpoint.reviewPrefetchRoots.map { it.key }.containsAll(playedRootKeys))
            assertTrue(
                terminalCheckpoint.reviewPrefetchAdjacentRoots.map { it.key }
                    .containsAll(playedAdjacentKeys),
            )

            game.ensureReviewStarted()
            val firstAttempt = game.reviewReuseDiagnosticsForTesting()
            assertTrue(firstAttempt.contains("reviewGeneration=1"), firstAttempt)
            game.ensureReviewStarted()
            val repeatedAttempt = game.reviewReuseDiagnosticsForTesting()
            assertTrue(repeatedAttempt.contains("reviewGeneration=1"), repeatedAttempt)
            assertTrue(repeatedAttempt.contains("reviewPostGameSearches=0"), repeatedAttempt)
            val reviewed = awaitView(game, timeoutMillis = 30_000) {
                it.reviewSummary != null || it.engineError != null
            }
            assertNull(reviewed.engineError)
            assertEquals(1, reviewed.reviewProgress)
            assertEquals(1, reviewed.reviewTotal)
            assertTrue(requireNotNull(reviewed.reviewSummary).contains("1 player moves"))
            assertEquals(1, reviewed.reviewDetails.lines().size)
            assertEquals(reviewed.plyCount, reviewed.reviewMoves.size)
            val reuseProof = game.reviewReuseDiagnosticsForTesting()
            assertTrue(reuseProof.contains("reviewGeneration=1"), reuseProof)
            assertTrue(reuseProof.contains("reviewPlanRoots=1"), reuseProof)
            assertTrue(reuseProof.contains("reviewSeededRoots=1"), reuseProof)
            assertTrue(reuseProof.contains("reviewPreparedMoves=1"), reuseProof)
            assertTrue(reuseProof.contains("reviewPostGameSearches=0"), reuseProof)
            assertTrue(reuseProof.contains("reviewReady=true"), reuseProof)
            assertEquals("READY", game.reviewPreparationState())
            val audit = game.drainReviewAuditEvents()
            assertTrue(audit.contains("\"event\":\"accepted\""), audit)
            assertTrue(audit.contains("\"event\":\"checkpointed\""), audit)
            assertTrue(audit.contains("\"event\":\"foreground_move_materialized\""), audit)
            assertTrue(audit.contains("\"event\":\"terminal_handoff_frozen\""), audit)
            assertTrue(audit.contains("\"event\":\"seed_coverage_materialized\""), audit)
            assertTrue(audit.contains("\"event\":\"review_ready\""), audit)
            assertFalse(audit.contains("\"event\":\"postgame_search_submitted\""), audit)
            assertTrue(audit.lineSequence().filter { it.isNotBlank() }.all { line ->
                line.startsWith("{") && line.endsWith("}")
            })
            assertEquals("", game.drainReviewAuditEvents())

            // Opening Review from a stale platform snapshot must subscribe to this completed
            // runtime-owned attempt, not create a second generation or submit the same root again.
            val reopened = game.ensureReviewStarted()
            assertTrue(reopened.reviewSummary != null)
            assertEquals(reuseProof, game.reviewReuseDiagnosticsForTesting())
            val playerMove = reviewed.reviewMoves.single { it.playerDecision }
            val opponentMove = reviewed.reviewMoves.single { !it.playerDecision }
            assertEquals(1, playerMove.ply)
            assertEquals(1, playerMove.moveNumber)
            assertEquals("WHITE", playerMove.mover)
            assertEquals("e4", playerMove.playedSan)
            assertEquals(64, playerMove.cells.size)
            assertEquals("a8", playerMove.cells.first().square)
            assertEquals("h1", playerMove.cells.last().square)
            assertEquals("wP", playerMove.cells.single { it.square == "e4" }.pieceCode)
            assertEquals("", playerMove.cells.single { it.square == "e2" }.pieceCode)
            assertTrue(playerMove.cells.single { it.square == "e2" }.lastMove)
            assertTrue(playerMove.cells.single { it.square == "e4" }.lastMove)
            assertTrue(playerMove.bestMoveSan?.isNotBlank() == true)
            assertTrue(playerMove.suggestedLineSan.isNotEmpty())
            assertEquals(2, opponentMove.ply)
            assertEquals("BLACK", opponentMove.mover)
            assertTrue(opponentMove.playedSan.isNotBlank())
            assertNull(opponentMove.quality)
            assertNull(opponentMove.bestMoveSan)
        } finally {
            game.close()
        }
    }

    @Test
    fun foregroundPreparedReviewSelfHealsAfterUndoAndAlternateReplay() {
        val game = SharedGameRuntime(botLevelId = "learner")
        try {
            game.setGameForeground(true)
            awaitCheckpoint(game) { checkpoint ->
                checkpoint.reviewPrefetchRoots.any { it.key.ply == 1 }
            }

            game.tap(52) // e2
            game.tap(36) // e4
            val firstTurn = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(firstTurn.engineError)
            game.setGameForeground(true)
            awaitCheckpoint(game) { checkpoint ->
                checkpoint.hasMaterializableReviewEvidence(1, UciMove("e2e4"))
            }
            val firstPrepared = awaitReviewDiagnostics(game) { diagnostics ->
                diagnostics.contains("foregroundMaterialized=1")
            }
            assertTrue(firstPrepared.contains("foregroundMaterializationFailed=false"), firstPrepared)

            val undone = game.undo()
            assertEquals(0, undone.plyCount)
            assertEquals("wP", undone.cells.single { it.square == "e2" }.pieceCode)
            // Deliberately do not poll presentationRevision or review diagnostics here. The stale
            // prepared e4 result remains in the runtime map while the same root is replayed with
            // a different legal move, reproducing a fast Undo -> alternate move interaction.
            game.tap(51) // d2
            game.tap(35) // d4
            val alternateTurn = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(alternateTurn.engineError)
            game.setGameForeground(true)
            val alternateEvidence = awaitCheckpoint(game) { checkpoint ->
                checkpoint.hasMaterializableReviewEvidence(1, UciMove("d2d4")) &&
                    checkpoint.reviewPrefetchRoots.any { it.key.ply == 3 }
            }
            assertFalse(
                alternateEvidence.reviewPrefetchAdjacentRoots.any { seed ->
                    seed.key.playedMove == UciMove("e2e4")
                },
            )
            val alternatePrepared = awaitReviewDiagnostics(game) { diagnostics ->
                diagnostics.contains("foregroundMaterialized=1") &&
                    diagnostics.contains(
                        "foregroundMaterializationRevision=${alternateEvidence.revision}",
                    )
            }
            assertTrue(
                alternatePrepared.contains("foregroundMaterializationFailed=false"),
                alternatePrepared,
            )

            val materializationAudit = game.drainReviewAuditEvents()
            assertEquals(
                2,
                materializationAudit.lineSequence().count { line ->
                    line.contains("\"event\":\"foreground_move_materialized\"")
                },
                materializationAudit,
            )
            assertFalse(
                materializationAudit.contains("\"event\":\"foreground_materialization_failed\""),
                materializationAudit,
            )

            // Once the alternate result is materialized, unchanged Swift polling at that same
            // revision must not continually enqueue another pass or produce an audit hot loop.
            val stablePresentation = game.presentationRevision()
            val stableCheckpointRevision = game.checkpointRevision()
            val stableDiagnostics = game.reviewReuseDiagnosticsForTesting()
            repeat(1_000) {
                assertEquals(stablePresentation, game.presentationRevision())
            }
            assertEquals(stableCheckpointRevision, game.checkpointRevision())
            assertEquals(stableDiagnostics, game.reviewReuseDiagnosticsForTesting())
            assertEquals("", game.drainReviewAuditEvents())

            val completed = game.resign()
            assertTrue(completed.reviewAvailable)
            game.setGameForeground(false)
            game.ensureReviewStarted()
            val reviewed = awaitView(game, timeoutMillis = 30_000) {
                it.reviewSummary != null || it.reviewError != null || it.engineError != null
            }
            assertNull(reviewed.engineError)
            assertNull(reviewed.reviewError)
            assertEquals(1, reviewed.reviewProgress)
            assertEquals(1, reviewed.reviewTotal)
            val playerMove = reviewed.reviewMoves.single { it.playerDecision }
            assertEquals(1, playerMove.ply)
            assertEquals("d4", playerMove.playedSan)
            assertEquals("wP", playerMove.cells.single { it.square == "d4" }.pieceCode)
            assertEquals("", playerMove.cells.single { it.square == "d2" }.pieceCode)

            val reuseProof = game.reviewReuseDiagnosticsForTesting()
            assertTrue(reuseProof.contains("reviewPreparedMoves=1"), reuseProof)
            assertTrue(reuseProof.contains("reviewPostGameSearches=0"), reuseProof)
            assertTrue(reuseProof.contains("reviewReady=true"), reuseProof)
            assertTrue(reuseProof.contains("foregroundMaterializationFailed=false"), reuseProof)
            val audit = game.drainReviewAuditEvents()
            assertFalse(audit.contains("\"event\":\"foreground_materialization_failed\""), audit)
            assertFalse(audit.contains("\"event\":\"postgame_search_submitted\""), audit)
        } finally {
            game.close()
        }
    }

    @Test
    fun completedReviewTimelineStartsInTheBlackPlayersOrientation() {
        val game = SharedGameRuntime(humanSideId = "black", botLevelId = "learner")
        try {
            val ready = awaitView(game) { it.phase == "HUMAN_TURN" || it.engineError != null }
            assertNull(ready.engineError)
            assertEquals(1, ready.plyCount)

            val completed = game.resign()
            assertTrue(
                completed.reviewMoves.isEmpty(),
                "Terminal publication must not eagerly rebuild the complete review timeline",
            )
            game.startReview()
            val prepared = awaitView(game) {
                it.reviewMoves.isNotEmpty() || it.reviewError != null
            }
            assertNull(prepared.reviewError)
            val opponentContext = prepared.reviewMoves.single()
            assertEquals(false, opponentContext.playerDecision)
            assertEquals("BLACK", completed.humanSide)
            assertEquals("h1", opponentContext.cells.first().square)
            assertEquals("a8", opponentContext.cells.last().square)
            assertTrue(opponentContext.playedSan.isNotBlank())
        } finally {
            game.close()
        }
    }

    @Test
    fun liveViewProjectsExactOpponentMoveMotionForNativeAnimation() {
        val game = SharedGameRuntime(humanSideId = "black", botLevelId = "learner")
        try {
            val ready = awaitView(game) { it.plyCount == 1 || it.engineError != null }
            assertNull(ready.engineError)
            assertEquals(1, ready.plyCount)
            assertNotEquals(ChessPosition.START_FEN, ready.positionMarker)

            val motion = requireNotNull(ready.moveMotion)
            assertEquals(ready.plyCount, motion.ply)
            assertEquals("WHITE", motion.mover)
            val piece = motion.pieces.single()
            assertTrue(piece.fromSquare.matches(Regex("[a-h][1-8]")))
            assertTrue(piece.toSquare.matches(Regex("[a-h][1-8]")))
            assertTrue(piece.pieceCode.startsWith("w"))
            assertEquals("", ready.cells.single { it.square == piece.fromSquare }.pieceCode)
            assertEquals(piece.pieceCode, ready.cells.single { it.square == piece.toSquare }.pieceCode)

            // The runtime may project the same committed board repeatedly; native hosts use the
            // marker and exact one-ply transition to avoid replaying this historical motion.
            val repeated = game.view()
            assertEquals(ready.positionMarker, repeated.positionMarker)
            assertEquals(motion, repeated.moveMotion)
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

    private fun CoordinatorCheckpoint.hasMaterializableReviewEvidence(
        ply: Int,
        playedMove: UciMove,
    ): Boolean =
        reviewPrefetchRoots.any { seed ->
            seed.key.ply == ply && seed.response.variations.any { variation ->
                variation.moves.firstOrNull() == playedMove
            }
        } || reviewPrefetchAdjacentRoots.any { seed ->
            seed.key.rootKey.ply == ply && seed.key.playedMove == playedMove
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

    private fun awaitReviewDiagnostics(
        game: SharedGameRuntime,
        timeoutMillis: Long = 15_000,
        predicate: (String) -> Boolean,
    ): String {
        val started = TimeSource.Monotonic.markNow()
        var diagnostics = game.reviewReuseDiagnosticsForTesting()
        while (!predicate(diagnostics) && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            game.presentationRevision()
            diagnostics = game.reviewReuseDiagnosticsForTesting()
        }
        assertTrue(predicate(diagnostics), "Timed out waiting for review diagnostics: $diagnostics")
        return diagnostics
    }

    private fun stressReviewResponse(
        request: EngineRequest,
        engineIdentity: EngineIdentity,
    ): EngineResponse {
        val legalMoves = ChessRules.legalMoves(
            ChessAdapter.replay(request.initialFen, request.moves),
        ).map { it.toUci() }
        val requestedRoots = request.searchMoves.toSet()
        val candidates = if (requestedRoots.isEmpty()) {
            legalMoves.take(request.limits.multiPv)
        } else {
            legalMoves.filter { it in requestedRoots }
                .also { constrained ->
                    require(constrained.size == requestedRoots.size)
                }
                .take(request.limits.multiPv)
        }
        require(candidates.isNotEmpty())
        return EngineResponse(
            requestId = request.requestId,
            gameId = request.gameId,
            positionId = request.positionId,
            bestMove = candidates.first(),
            ponderMove = null,
            depth = 16,
            nodes = 65_536,
            variations = candidates.mapIndexed { index, move ->
                PrincipalVariation(
                    scoreCentipawns = 30 - (index * 12),
                    mateIn = null,
                    moves = listOf(move),
                    rank = index + 1,
                )
            },
            engine = engineIdentity,
        )
    }
}
