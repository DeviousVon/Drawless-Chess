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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.GameReviewProgress
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GameReviewInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun resignationProgressWaitsForTheFinalPositionSearch() {
        assertEquals(0, reviewCompletedMoveCount(GameReviewProgress(0, 5), totalMoves = 4))
        assertEquals(3, reviewCompletedMoveCount(GameReviewProgress(4, 5), totalMoves = 4))
        assertEquals(4, reviewCompletedMoveCount(GameReviewProgress(5, 5), totalMoves = 4))
        assertEquals(3, reviewCompletedMoveCount(GameReviewProgress(4, 4), totalMoves = 4))
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

        compose.onNodeWithText("Best").assertIsDisplayed()
        compose.onNodeWithTag("review_move_1")
            .assertIsSelected()
            .assert(hasContentDescription("Best", substring = true))
        compose.onNodeWithTag("review_previous").assertIsEnabled()
        compose.onNodeWithTag("review_next").assertIsEnabled().performClick()

        compose.onNodeWithText("1… e5").assertIsDisplayed()
        compose.onNodeWithText("Better was Nc6.").assertIsDisplayed()
        compose.onNodeWithText("Suggested line: Nc6 Nf3").assertIsDisplayed()
        compose.onNodeWithTag("review_move_2")
            .assertIsSelected()
            .assert(hasContentDescription("Mistake", substring = true))
        compose.runOnIdle { assertEquals(2, selectedPly) }
    }

    @Test
    fun compactDoubleFontKeepsReviewContentReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
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
        }

        compose.onNodeWithTag("review_back").assertIsDisplayed()
        compose.onNodeWithTag("chess_board_imperial_marble").assertIsDisplayed()
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
                        completedMoves = 1,
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

    private fun reviewModel(selectedPly: Int): GameReviewUiModel {
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
                san = "e4",
                grade = ReviewGradeUi.BEST,
                bestMoveSan = "e4",
                suggestedLine = listOf("e4", "e5"),
                evaluation = ReviewEvaluation.Centipawns(20),
            ),
            ReviewMoveUi(
                ply = 2,
                moveNumber = 1,
                mover = Side.BLACK,
                san = "e5",
                grade = ReviewGradeUi.MISTAKE,
                bestMoveSan = "Nc6",
                suggestedLine = listOf("Nc6", "Nf3"),
                evaluation = ReviewEvaluation.Centipawns(-110),
                evaluationSide = Side.BLACK,
            ),
            ReviewMoveUi(
                ply = 3,
                moveNumber = 2,
                mover = Side.WHITE,
                san = "Nf3",
                grade = ReviewGradeUi.GOOD,
                bestMoveSan = "Nf3",
            ),
            ReviewMoveUi(
                ply = 4,
                moveNumber = 2,
                mover = Side.BLACK,
                san = "Nc6",
                grade = ReviewGradeUi.INACCURACY,
                bestMoveSan = "Nf6",
            ),
        )
        return GameReviewUiModel(
            board = BoardPresenter.presentReview(
                initialFen = ChessPosition.START_FEN,
                moves = moves.take(selectedPly),
                humanSide = Side.WHITE,
                theme = BoardThemes.DEFAULT,
            ),
            moves = reviewed,
            selectedPly = selectedPly,
            completedMoves = reviewed.size,
            status = ReviewAnalysisUiStatus.COMPLETE,
        )
    }
}
