package com.drawlesschess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewMoveQuality
import com.drawlesschess.core.engine.ReviewSideSummary
import com.drawlesschess.core.engine.ReviewedMove
import com.drawlesschess.core.presentation.BoardOrientation
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GameReviewInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewProgressCountsOnlyCompletedPlayerDecisionsForEitherSide() {
        val whiteMoves = reviewModel(selectedPly = 1, playerSide = Side.WHITE).moves
        val blackMoves = reviewModel(selectedPly = 2, playerSide = Side.BLACK).moves
        val rootRunning = GameReviewProgress(
            completedWorkUnits = 1,
            totalWorkUnits = 3,
            completedMoves = 0,
            totalMoves = 2,
        )
        val firstDecisionComplete = rootRunning.copy(
            completedWorkUnits = 2,
            completedMoves = 1,
        )
        val reviewComplete = rootRunning.copy(
            completedWorkUnits = 3,
            completedMoves = 2,
        )
        assertEquals(0, reviewCompletedPlayerMoveCount(rootRunning, whiteMoves))
        assertEquals(1, reviewCompletedPlayerMoveCount(firstDecisionComplete, whiteMoves))
        assertEquals(2, reviewCompletedPlayerMoveCount(reviewComplete, whiteMoves))
        assertEquals(0, reviewCompletedPlayerMoveCount(rootRunning, blackMoves))
        assertEquals(1, reviewCompletedPlayerMoveCount(firstDecisionComplete, blackMoves))
        assertEquals(2, reviewCompletedPlayerMoveCount(reviewComplete, blackMoves))

        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 2, playerSide = Side.BLACK).copy(
                        completedPlayerMoves = 1,
                        status = ReviewAnalysisUiStatus.ANALYZING,
                        playerSummary = null,
                    ),
                    showBoardCoordinates = false,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }
        compose.onNodeWithText("Your moves reviewed: 1 of 2").assertIsDisplayed()
    }

    @Test
    fun sparsePlayerResultsMergeIntoTheCanonicalTimelineWithoutGradingTheOpponent() {
        val placeholders = reviewModel(selectedPly = 1).moves.map { move ->
            move.copy(
                grade = null,
                bestMoveSan = null,
                suggestedLine = emptyList(),
                evaluation = null,
                betterMoveArrow = null,
            )
        }
        val initial = ChessPosition.fromFen(ChessPosition.START_FEN)
        val afterE4 = ChessRules.apply(initial, UciMove("e2e4"))
        val afterE5 = ChessRules.apply(afterE4, UciMove("e7e5"))
        val afterNf3 = ChessRules.apply(afterE5, UciMove("g1f3"))
        val opponentResult = ReviewedMove(
            ply = 2,
            mover = Side.BLACK,
            playedMove = UciMove("e7e5"),
            bestMove = UciMove("b8c6"),
            quality = ReviewMoveQuality.MISTAKE,
            bestEvaluation = ReviewEvaluation.Centipawns(10),
            playedEvaluation = ReviewEvaluation.Centipawns(120),
            expectedPointLoss = 0.2,
            suggestedLine = listOf(UciMove("b8c6")),
            fenBefore = afterE4.fen(),
            fenAfter = afterE5.fen(),
        )
        val playerResult = ReviewedMove(
            ply = 3,
            mover = Side.WHITE,
            playedMove = UciMove("g1f3"),
            bestMove = UciMove("b1c3"),
            quality = ReviewMoveQuality.GOOD,
            bestEvaluation = ReviewEvaluation.Centipawns(80),
            playedEvaluation = ReviewEvaluation.Centipawns(55),
            expectedPointLoss = 0.04,
            suggestedLine = listOf(UciMove("b1c3")),
            fenBefore = afterE5.fen(),
            fenAfter = afterNf3.fen(),
        )

        val merged = reviewMovesWithPartials(
            placeholders = placeholders,
            partialMoves = mapOf(2 to opponentResult, 3 to playerResult),
            playerSide = Side.WHITE,
        )

        assertEquals(listOf(1, 2, 3, 4), merged.map { move -> move.ply })
        assertEquals(ReviewMoveRole.OPPONENT_CONTEXT, merged[1].role)
        assertEquals(null, merged[1].grade)
        assertEquals(ReviewGradeUi.GOOD, merged[2].grade)
        assertEquals(
            ReviewBetterMoveArrow(Square.parse("b1"), Square.parse("c3")),
            merged[2].betterMoveArrow,
        )
    }

    @Test
    fun completedReviewNavigatesAndPublishesGradeWithoutRelyingOnColor() {
        var selectedPly by mutableIntStateOf(1)
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly),
                    showBoardCoordinates = true,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                )
            }
        }

        compose.onNodeWithText("Best").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_move_1")
            .assertIsSelected()
            .assert(hasContentDescription("Best", substring = true))
        compose.onNodeWithTag("review_move_feedback")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "1. e4. Best. Selected.",
                ),
            )
        compose.onNodeWithTag("review_previous").assertIsEnabled()
        compose.onNodeWithTag("review_next")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(2, selectedPly) }

        compose.onNodeWithText("1… e5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Opponent move").assertIsDisplayed()
        compose.onNodeWithText("Shown for context. Only your moves are graded.").assertIsDisplayed()
        compose.onNodeWithTag("review_move_2")
            .assertIsSelected()
            .assert(hasContentDescription("Opponent move", substring = true))
        compose.onNodeWithTag("review_move_feedback")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "1… e5. Opponent move. Selected.",
                ),
            )
        compose.onAllNodesWithText("Better was Nc6.").assertCountEquals(0)
        compose.onAllNodesWithText("Suggested line: Nc6 Nf3").assertCountEquals(0)
        compose.onAllNodesWithText("Mistake").assertCountEquals(0)
        compose.onAllNodesWithText("Black evaluation", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("review_better_move_arrow").assertCountEquals(0)

        compose.onNodeWithTag("review_next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, selectedPly) }
        compose.onNodeWithText("Better was Nc3.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_better_move_arrow", useUnmergedTree = true)
            .assertContentDescriptionEquals("Better move arrow from b1 to c3")
            .assertIsDisplayed()
    }

    @Test
    fun betterMoveArrowAppearsOnlyForAPlayerSuggestion() {
        var model by mutableStateOf(reviewModel(selectedPly = 0))
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = model,
                    showBoardCoordinates = true,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }

        compose.onAllNodesWithTag("review_better_move_arrow", useUnmergedTree = true)
            .assertCountEquals(0)

        compose.runOnIdle { model = reviewModel(selectedPly = 1) }
        compose.onAllNodesWithTag("review_better_move_arrow", useUnmergedTree = true)
            .assertCountEquals(0)

        compose.runOnIdle { model = reviewModel(selectedPly = 2) }
        compose.onNodeWithTag("review_opponent_context").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("review_better_move_arrow", useUnmergedTree = true)
            .assertCountEquals(0)

        compose.runOnIdle {
            val withoutSuggestion = reviewModel(selectedPly = 3)
            model = withoutSuggestion.copy(
                moves = withoutSuggestion.moves.map { move ->
                    if (move.ply == 3) move.copy(bestMoveSan = null) else move
                },
            )
        }
        compose.onAllNodesWithTag("review_better_move_arrow", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun betterMoveArrowUsesSuggestedMoveSquaresThroughEitherOrientation() {
        val arrow = ReviewBetterMoveArrow(Square.parse("b1"), Square.parse("c3"))
        val moves = listOf(UciMove("e2e4"), UciMove("e7e5"), UciMove("g1f3"))
        val whiteBoard = BoardPresenter.presentReview(
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            humanSide = Side.WHITE,
            orientation = BoardOrientation.WHITE_AT_BOTTOM,
        )
        val blackBoard = BoardPresenter.presentReview(
            initialFen = ChessPosition.START_FEN,
            moves = moves,
            humanSide = Side.WHITE,
            orientation = BoardOrientation.BLACK_AT_BOTTOM,
        )

        val (whiteFrom, whiteTo) = reviewArrowDisplayCells(whiteBoard, arrow)
        val (blackFrom, blackTo) = reviewArrowDisplayCells(blackBoard, arrow)
        assertEquals(7 - whiteFrom.displayRow, blackFrom.displayRow)
        assertEquals(7 - whiteFrom.displayColumn, blackFrom.displayColumn)
        assertEquals(7 - whiteTo.displayRow, blackTo.displayRow)
        assertEquals(7 - whiteTo.displayColumn, blackTo.displayColumn)
        assertEquals(Square.parse("b1"), whiteFrom.square)
        assertEquals(Square.parse("c3"), whiteTo.square)
    }

    @Test
    fun completedReviewShowsOnlyPlayerSummaryWithoutInventingAccuracy() {
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 2),
                    showBoardCoordinates = true,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }

        compose.onNodeWithTag("review_summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your review").assertIsDisplayed()
        compose.onNodeWithText("You (White)").assertIsDisplayed()
        compose.onAllNodesWithText("Opponent (Black)").assertCountEquals(0)
        compose.onAllNodesWithText("Moves graded: 2").assertCountEquals(1)
        compose.onNodeWithTag("review_summary_player_best")
            .assert(hasContentDescription("Best: 1"))
        compose.onNodeWithTag("review_summary_player_good")
            .assert(hasContentDescription("Good: 1"))
        compose.onAllNodesWithTag("review_summary_opponent").assertCountEquals(0)
        compose.onAllNodesWithTag("review_summary_opponent_inaccuracy").assertCountEquals(0)
        compose.onAllNodesWithTag("review_summary_opponent_mistake").assertCountEquals(0)
        compose.onNodeWithTag("review_summary_player_blunder")
            .assert(hasContentDescription("Blunder: 0"))
        compose.onAllNodesWithText("Accuracy", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Your moves: 2. Moves graded: 2.").assertIsDisplayed()
    }

    @Test
    fun reviewMyMistakesSelectsFirstIssueForThePlayerSide() {
        var selectedPly by mutableIntStateOf(4)
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly, playerSide = Side.BLACK),
                    showBoardCoordinates = true,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                )
            }
        }

        compose.onNodeWithText("You (Black)").assertIsDisplayed()
        compose.onAllNodesWithText("You (White)").assertCountEquals(0)
        compose.onAllNodesWithTag("review_summary_opponent").assertCountEquals(0)
        compose.onNodeWithTag("review_move_1")
            .assert(hasContentDescription("Opponent move", substring = true))
        compose.onNodeWithTag("review_my_mistakes")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(2, selectedPly) }
        compose.onNodeWithTag("review_move_2").assertIsSelected()
    }

    @Test
    fun summaryIsHiddenUntilReviewCompletes() {
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 1).copy(
                        completedPlayerMoves = 1,
                        status = ReviewAnalysisUiStatus.ANALYZING,
                    ),
                    showBoardCoordinates = false,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }

        compose.onAllNodesWithTag("review_summary").assertCountEquals(0)
        compose.onNodeWithTag("review_progress").assertIsDisplayed()
        compose.onNodeWithText("Your moves reviewed: 1 of 2").assertIsDisplayed()
    }

    @Test
    fun completedPlayerFeedbackCanStreamWithoutMovingTheSelection() {
        fun streamingModel(includeSecondPlayerMove: Boolean): GameReviewUiModel {
            val completed = reviewModel(selectedPly = 1).copy(
                completedPlayerMoves = if (includeSecondPlayerMove) 2 else 1,
                status = ReviewAnalysisUiStatus.ANALYZING,
                playerSummary = null,
            )
            return completed.copy(
                moves = completed.moves.map { move ->
                    when {
                        move.role == ReviewMoveRole.OPPONENT_CONTEXT -> move
                        move.ply == 1 -> move
                        includeSecondPlayerMove -> move
                        else -> move.copy(
                            grade = null,
                            bestMoveSan = null,
                            suggestedLine = emptyList(),
                            evaluation = null,
                            betterMoveArrow = null,
                        )
                    }
                },
            )
        }

        var model by mutableStateOf(streamingModel(includeSecondPlayerMove = false))
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = model,
                    showBoardCoordinates = true,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }

        compose.onNodeWithText("Best").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your moves reviewed: 1 of 2").assertIsDisplayed()
        compose.onNodeWithTag("review_move_1").assertIsSelected()
        compose.onNodeWithTag("review_move_3")
            .assert(hasContentDescription("Waiting for analysis", substring = true))

        compose.runOnIdle { model = streamingModel(includeSecondPlayerMove = true) }
        compose.onNodeWithText("Your moves reviewed: 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag("review_move_1").assertIsSelected()
        compose.onNodeWithTag("review_move_3")
            .assert(hasContentDescription("Good", substring = true))
    }

    @Test
    fun compactDoubleFontKeepsReviewContentReachable() {
        val largeCounts = ReviewMoveQuality.entries.associateWith { 100 }
        val largeSummary = ReviewSideSummary(
            side = Side.WHITE,
            gradedMoves = 500,
            movesWithExpectedPointLoss = 0,
            meanExpectedPointLoss = null,
            qualityCounts = largeCounts,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        GameReviewScreen(
                            model = reviewModel(selectedPly = 2).copy(playerSummary = largeSummary),
                            showBoardCoordinates = true,
                            onBack = {},
                            onFlip = {},
                            onCancel = {},
                            onRetry = {},
                            onSelectPly = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("review_back").assertIsDisplayed()
        compose.onNodeWithTag("chess_board_imperial_marble").assertIsDisplayed()
        compose.onNodeWithTag("review_summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_summary_player_blunder")
            .assertWidthIsAtLeast(96.dp)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("review_summary_player_blunder_label", useUnmergedTree = true)
            .assert(hasText("?? 100"))
            .assertIsDisplayed()
        compose.onNodeWithTag("review_move_feedback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_navigator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_move_list").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stoppedAnalysisCanRestartWhileFatalFailureStaysExplanatory() {
        var status by mutableStateOf(ReviewAnalysisUiStatus.ANALYZING)
        var cancelClicks = 0
        var retryClicks = 0
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 1).copy(
                        completedPlayerMoves = 1,
                        status = status,
                        errorMessage = if (status == ReviewAnalysisUiStatus.FAILED) {
                            "The engine stopped unexpectedly."
                        } else null,
                    ),
                    showBoardCoordinates = false,
                    onBack = {},
                    onFlip = {},
                    onCancel = {
                        cancelClicks += 1
                        status = ReviewAnalysisUiStatus.CANCELLED
                    },
                    onRetry = { retryClicks += 1 },
                    onSelectPly = {},
                )
            }
        }

        compose.onNodeWithTag("review_progress").assertIsDisplayed()
        compose.onNodeWithTag("review_cancel").performClick()
        compose.onNodeWithTag("review_retry").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelClicks)
            assertEquals(1, retryClicks)
            status = ReviewAnalysisUiStatus.FAILED
        }
        compose.onNodeWithText("The engine stopped unexpectedly.").assertIsDisplayed()
        compose.onAllNodesWithTag("review_retry").assertCountEquals(0)
    }

    @Test
    fun stoppedOrFailedReviewMarksUnscoredMoveNotAnalyzed() {
        var status by mutableStateOf(ReviewAnalysisUiStatus.CANCELLED)
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 1).copy(
                        moves = reviewModel(selectedPly = 1).moves.map { it.copy(grade = null) },
                        status = status,
                        playerSummary = null,
                    ),
                    showBoardCoordinates = false,
                    onBack = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                )
            }
        }

        compose.onNodeWithTag("review_move_feedback")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "1. e4. Not analyzed. Selected.",
                ),
            )
        compose.onAllNodesWithText("Waiting for analysis…").assertCountEquals(0)
        compose.runOnIdle { status = ReviewAnalysisUiStatus.FAILED }
        compose.onNodeWithTag("review_move_feedback")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "1. e4. Not analyzed. Selected.",
                ),
            )
        compose.onAllNodesWithText("Waiting for analysis…").assertCountEquals(0)
    }

    @Test
    fun responsiveLayoutKeepsBoardAndReviewPanelReachable() {
        compose.setContent {
            DrawlessTheme {
                Box(Modifier.fillMaxSize()) {
                    GameReviewScreen(
                        model = reviewModel(selectedPly = 2),
                        showBoardCoordinates = true,
                        onBack = {},
                        onFlip = {},
                        onCancel = {},
                        onRetry = {},
                        onSelectPly = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("chess_board_imperial_marble").assertIsDisplayed()
        compose.onNodeWithTag("review_panel").fetchSemanticsNode()
        compose.onNodeWithTag("review_move_feedback").performScrollTo().assertIsDisplayed()
    }

    private fun reviewModel(
        selectedPly: Int,
        playerSide: Side = Side.WHITE,
    ): GameReviewUiModel {
        val moves = listOf(
            UciMove("e2e4"),
            UciMove("e7e5"),
            UciMove("g1f3"),
            UciMove("b8c6"),
        )
        val reviewed = listOf(
            ReviewMoveUi(
                ply = 1,
                moveNumber = 1,
                mover = Side.WHITE,
                role = roleFor(Side.WHITE, playerSide),
                san = "e4",
                grade = ReviewGradeUi.BEST,
                bestMoveSan = "e4",
                suggestedLine = listOf("e4", "e5"),
                evaluation = ReviewEvaluation.Centipawns(20),
                betterMoveArrow = ReviewBetterMoveArrow(
                    Square.parse("e2"),
                    Square.parse("e4"),
                ),
            ),
            ReviewMoveUi(
                ply = 2,
                moveNumber = 1,
                mover = Side.BLACK,
                role = roleFor(Side.BLACK, playerSide),
                san = "e5",
                grade = ReviewGradeUi.MISTAKE,
                bestMoveSan = "Nc6",
                suggestedLine = listOf("Nc6", "Nf3"),
                evaluation = ReviewEvaluation.Centipawns(-110),
                evaluationSide = Side.BLACK,
                betterMoveArrow = ReviewBetterMoveArrow(
                    Square.parse("b8"),
                    Square.parse("c6"),
                ),
            ),
            ReviewMoveUi(
                ply = 3,
                moveNumber = 2,
                mover = Side.WHITE,
                role = roleFor(Side.WHITE, playerSide),
                san = "Nf3",
                grade = ReviewGradeUi.GOOD,
                bestMoveSan = "Nc3",
                suggestedLine = listOf("Nc3", "Nc6"),
                evaluation = ReviewEvaluation.Centipawns(15),
                betterMoveArrow = ReviewBetterMoveArrow(
                    Square.parse("b1"),
                    Square.parse("c3"),
                ),
            ),
            ReviewMoveUi(
                ply = 4,
                moveNumber = 2,
                mover = Side.BLACK,
                role = roleFor(Side.BLACK, playerSide),
                san = "Nc6",
                grade = ReviewGradeUi.INACCURACY,
                bestMoveSan = "Nf6",
                betterMoveArrow = ReviewBetterMoveArrow(
                    Square.parse("g8"),
                    Square.parse("f6"),
                ),
            ),
        )
        return GameReviewUiModel(
            board = BoardPresenter.presentReview(
                initialFen = ChessPosition.START_FEN,
                moves = moves.take(selectedPly),
                humanSide = playerSide,
                theme = BoardThemes.DEFAULT,
            ),
            moves = reviewed,
            selectedPly = selectedPly,
            completedPlayerMoves = reviewed.count {
                it.role == ReviewMoveRole.PLAYER_DECISION
            },
            status = ReviewAnalysisUiStatus.COMPLETE,
            playerSide = playerSide,
            playerSummary = if (playerSide == Side.WHITE) {
                sideSummary(
                    Side.WHITE,
                    ReviewMoveQuality.BEST,
                    ReviewMoveQuality.GOOD,
                )
            } else {
                sideSummary(
                    Side.BLACK,
                    ReviewMoveQuality.MISTAKE,
                    ReviewMoveQuality.INACCURACY,
                )
            },
        )
    }

    private fun roleFor(mover: Side, playerSide: Side): ReviewMoveRole =
        if (mover == playerSide) ReviewMoveRole.PLAYER_DECISION
        else ReviewMoveRole.OPPONENT_CONTEXT

    private fun sideSummary(
        side: Side,
        vararg qualities: ReviewMoveQuality,
    ): ReviewSideSummary = ReviewSideSummary(
        side = side,
        gradedMoves = qualities.size,
        movesWithExpectedPointLoss = 0,
        meanExpectedPointLoss = null,
        qualityCounts = ReviewMoveQuality.entries.associateWith { quality ->
            qualities.count { it == quality }
        },
    )
}
