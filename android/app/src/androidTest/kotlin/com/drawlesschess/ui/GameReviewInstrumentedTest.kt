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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
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
import com.drawlesschess.core.presentation.BoardMoveArrow
import com.drawlesschess.core.presentation.BoardPresenter
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.ControlPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

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
                    onSaveAndExit = {},
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
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                    showOpponentMoves = true,
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
        compose.onNodeWithTag("review_previous_issue").assertIsNotEnabled()
        compose.onNodeWithTag("review_next_issue").assertIsNotEnabled()
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
    fun mediaNavigatorVisitsEveryMoveAndSkipsBetweenPlayerIssues() {
        var selectedPly by mutableIntStateOf(2)
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly, playerSide = Side.BLACK),
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                    showOpponentMoves = true,
                )
            }
        }

        compose.onNodeWithTag("review_navigator").performScrollTo().assertIsDisplayed()
        listOf(
            "review_previous_issue",
            "review_previous",
            "review_next",
            "review_next_issue",
        ).forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
        }
        compose.onNodeWithTag("review_previous_issue")
            .assertContentDescriptionEquals("Previous mistake")
            .assertIsNotEnabled()
        compose.onNodeWithTag("review_previous")
            .assertContentDescriptionEquals("Previous move")
            .assertIsEnabled()
        compose.onNodeWithTag("review_next")
            .assertContentDescriptionEquals("Next move")
            .assertIsEnabled()
        compose.onNodeWithTag("review_next_issue")
            .assertContentDescriptionEquals("Next mistake")
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(4, selectedPly) }

        compose.onNodeWithTag("review_next_issue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("review_previous_issue")
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(2, selectedPly) }

        compose.onNodeWithTag("review_next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, selectedPly) }
        compose.onNodeWithTag("review_previous").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, selectedPly) }
    }

    @Test
    fun betterMoveArrowAppearsOnlyForAPlayerSuggestion() {
        var model by mutableStateOf(reviewModel(selectedPly = 0))
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = model,
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                    showOpponentMoves = true,
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
    fun gameplayHintArrowUsesTheSameVisualOverlayAsReview() {
        val board = BoardPresenter.presentReview(
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            humanSide = Side.WHITE,
        ).copy(
            hintMove = BoardMoveArrow(Square.parse("e2"), Square.parse("e4")),
        )

        compose.setContent {
            DrawlessTheme {
                ChessBoard(
                    model = board,
                    boardSizeDp = 320,
                    onEvent = {},
                    showCoordinates = true,
                    onMoveAnimationFinished = {},
                )
            }
        }

        compose.onNodeWithTag("hint_move_arrow", useUnmergedTree = true)
            .assertContentDescriptionEquals("Hint move arrow from e2 to e4")
            .assertIsDisplayed()
    }

    @Test
    fun completedReviewShowsOnlyPlayerSummaryWithoutInventingAccuracy() {
        var rematchClicks = 0
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly = 1),
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                    onRematch = { rematchClicks += 1 },
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
        compose.onNodeWithTag("review_rematch")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, rematchClicks) }
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onAllNodesWithTag("review_move_4").assertCountEquals(0)
    }

    @Test
    fun reviewEntryStartsAtThePlayersFirstMoveForEitherSide() {
        val whiteMoves = reviewModel(selectedPly = 4, playerSide = Side.WHITE).moves
        val blackMoves = reviewModel(selectedPly = 4, playerSide = Side.BLACK).moves
        assertEquals(1, initialPlayerReviewPly(whiteMoves))
        assertEquals(2, initialPlayerReviewPly(blackMoves))
        assertEquals(0, initialPlayerReviewPly(emptyList()))
        assertEquals(listOf(1, 3), visibleReviewMoves(whiteMoves, false).map { it.ply })
        assertEquals(listOf(2, 4), visibleReviewMoves(blackMoves, false).map { it.ply })
        assertEquals(listOf(1, 2, 3, 4), visibleReviewMoves(whiteMoves, true).map { it.ply })
        assertEquals(listOf(1, 2, 3, 4), visibleReviewMoves(blackMoves, true).map { it.ply })
        assertEquals(3, reviewSelectionAfterHidingOpponentMoves(whiteMoves, selectedPly = 2))
        assertEquals(3, reviewSelectionAfterHidingOpponentMoves(whiteMoves, selectedPly = 4))
        assertEquals(2, reviewSelectionAfterHidingOpponentMoves(blackMoves, selectedPly = 1))
        assertEquals(4, reviewSelectionAfterHidingOpponentMoves(blackMoves, selectedPly = 3))
        assertEquals(1, reviewSelectionAfterHidingOpponentMoves(whiteMoves, selectedPly = 1))
        assertEquals(0, reviewSelectionAfterHidingOpponentMoves(whiteMoves, selectedPly = 0))
        assertEquals(
            0,
            reviewSelectionAfterHidingOpponentMoves(blackMoves.take(1), selectedPly = 1),
        )
    }

    @Test
    fun opponentMoveToggleDefaultsOffAndKeepsTheTimelineChronological() {
        var selectedPly by mutableIntStateOf(1)
        var showOpponentMoves by mutableStateOf(false)
        val moves = reviewModel(selectedPly = 1).moves
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly),
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                    showOpponentMoves = showOpponentMoves,
                    onShowOpponentMovesChange = { show ->
                        if (!show) {
                            selectedPly = reviewSelectionAfterHidingOpponentMoves(
                                moves,
                                selectedPly,
                            )
                        }
                        showOpponentMoves = show
                    },
                )
            }
        }

        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .assertIsOff()
        compose.onNodeWithText("Your moves").assertIsDisplayed()
        compose.onNodeWithTag("review_move_1").assertIsSelected()
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onNodeWithTag("review_move_3").fetchSemanticsNode()
        compose.onAllNodesWithTag("review_move_4").assertCountEquals(0)
        compose.onNodeWithText("Your move 1 of 2").assertIsDisplayed()

        compose.onNodeWithTag("review_next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, selectedPly) }
        compose.onNodeWithText("Your move 2 of 2").assertIsDisplayed()

        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose.onNodeWithText("Game moves").assertIsDisplayed()
        compose.onNodeWithTag("review_move_2").fetchSemanticsNode()
        compose.onNodeWithTag("review_move_4").fetchSemanticsNode()
        compose.onNodeWithText("Move 3 of 4").assertIsDisplayed()

        compose.onNodeWithTag("review_previous").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, selectedPly) }
        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .performClick()
            .assertIsOff()
        compose.runOnIdle { assertEquals(3, selectedPly) }
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onNodeWithTag("review_move_3").assertIsSelected()
    }

    @Test
    fun playerOnlyTimelineHasAnEmptyStateButCanRevealOpponentContext() {
        var showOpponentMoves by mutableStateOf(false)
        val blackReview = reviewModel(selectedPly = 0, playerSide = Side.BLACK)
        val opponentOpeningOnly = blackReview.copy(
            moves = blackReview.moves.take(1),
            completedPlayerMoves = 0,
            status = ReviewAnalysisUiStatus.ANALYZING,
            playerSummary = null,
        )
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = opponentOpeningOnly,
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = {},
                    showOpponentMoves = showOpponentMoves,
                    onShowOpponentMovesChange = { showOpponentMoves = it },
                )
            }
        }

        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .assertIsOff()
        compose.onNodeWithTag("review_no_player_moves").fetchSemanticsNode()
        compose.onNodeWithText("No player moves to review.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithTag("review_move_1").assertCountEquals(0)
        compose.onAllNodesWithTag("review_rematch").assertCountEquals(0)
        compose.onNodeWithTag("review_next").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("review_show_opponent_moves").performScrollTo().performClick()
        compose.onNodeWithTag("review_show_opponent_moves").assertIsOn()
        compose.onAllNodesWithTag("review_no_player_moves").assertCountEquals(0)
        compose.onNodeWithTag("review_move_1")
            .assert(hasContentDescription("Opponent move", substring = true))
    }

    @Test
    fun completedReviewKeepsThePlayersFirstMoveSelectedWithoutAnActionButton() {
        var selectedPly by mutableIntStateOf(2)
        compose.setContent {
            DrawlessTheme {
                GameReviewScreen(
                    model = reviewModel(selectedPly, playerSide = Side.BLACK),
                    showBoardCoordinates = true,
                    onSaveAndExit = {},
                    onFlip = {},
                    onCancel = {},
                    onRetry = {},
                    onSelectPly = { selectedPly = it },
                )
            }
        }

        compose.onNodeWithText("You (Black)").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("You (White)").assertCountEquals(0)
        compose.onAllNodesWithTag("review_summary_opponent").assertCountEquals(0)
        compose.onNodeWithTag("review_show_opponent_moves").performScrollTo().assertIsOff()
        compose.onAllNodesWithTag("review_move_1").assertCountEquals(0)
        compose.onAllNodesWithTag("review_move_3").assertCountEquals(0)
        compose.onNodeWithTag("review_my_mistakes").assertDoesNotExist()
        compose.onNodeWithTag("review_move_2").assertIsSelected()
        compose.onNodeWithTag("review_move_4").fetchSemanticsNode()
        compose.onNodeWithTag("review_next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4, selectedPly) }
    }

    @Test
    fun completedPortraitRevealsSelectedFeedbackDirectlyBelowTheBoard() {
        val selectedPly = 2
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                DrawlessTheme {
                    Box(Modifier.width(412.dp).height(915.dp)) {
                        GameReviewScreen(
                            model = reviewModel(selectedPly, playerSide = Side.BLACK),
                            showBoardCoordinates = true,
                            onSaveAndExit = {},
                            onFlip = {},
                            onCancel = {},
                            onRetry = {},
                            onSelectPly = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("review_my_mistakes").assertDoesNotExist()
        compose.onNodeWithTag("review_move_2").assertIsSelected()

        val board = compose.onNodeWithTag("chess_board_imperial_marble")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val feedback = compose.onNodeWithTag("review_move_feedback")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(feedback.top >= board.bottom)
        assertTrue(feedback.top - board.bottom <= 11f)
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
                    onSaveAndExit = {},
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
        compose.onNodeWithTag("review_show_opponent_moves").performScrollTo().assertIsOff()
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onAllNodesWithTag("review_move_4").assertCountEquals(0)
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
                    onSaveAndExit = {},
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
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onAllNodesWithTag("review_move_4").assertCountEquals(0)
        compose.onNodeWithTag("review_move_3")
            .assert(hasContentDescription("Waiting for analysis", substring = true))

        compose.runOnIdle { model = streamingModel(includeSecondPlayerMove = true) }
        compose.onNodeWithText("Your moves reviewed: 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag("review_move_1").assertIsSelected()
        compose.onAllNodesWithTag("review_move_2").assertCountEquals(0)
        compose.onAllNodesWithTag("review_move_4").assertCountEquals(0)
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
                            model = reviewModel(selectedPly = 1).copy(playerSummary = largeSummary),
                            showBoardCoordinates = true,
                            onSaveAndExit = {},
                            onFlip = {},
                            onCancel = {},
                            onRetry = {},
                            onSelectPly = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("review_save_exit")
            .assertTextEquals("Save & exit")
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
        compose.onNodeWithTag("chess_board_imperial_marble").assertIsDisplayed()
        compose.onNodeWithTag("review_summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_rematch")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertIsDisplayed()
        compose.onNodeWithTag("review_summary_player_blunder")
            .assertWidthIsAtLeast(96.dp)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("review_summary_player_blunder_label", useUnmergedTree = true)
            .assert(hasText("?? 100"))
            .assertIsDisplayed()
        compose.onNodeWithTag("review_move_feedback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_navigator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
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
                    onSaveAndExit = {},
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
                    onSaveAndExit = {},
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
    fun shortLandscapeReviewLayoutUsesTheFullContentHeight() {
        val layout = calculateReviewContentLayout(widthDp = 568, heightDp = 320)
        val widthLimited = calculateReviewContentLayout(widthDp = 520, heightDp = 320)
        val portrait = calculateReviewContentLayout(widthDp = 412, heightDp = 915)

        assertEquals(ControlPlacement.BESIDE_BOARD, layout.controlPlacement)
        assertEquals(320, layout.boardSizeDp)
        assertEquals(0, layout.outerPaddingDp)
        assertEquals(8, layout.panelGapDp)
        assertEquals(200, layout.panelWidthDp)
        assertEquals(312, widthLimited.boardSizeDp)
        assertEquals(ControlPlacement.BELOW_BOARD, portrait.controlPlacement)
        assertEquals(380, portrait.boardSizeDp)
        assertEquals(16, portrait.outerPaddingDp)
    }

    @Test
    fun shortLandscapeRelocatesSaveExitAndFlipBesideAFullHeightBoard() {
        var saveExitClicks = 0
        var flipClicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                DrawlessTheme {
                    Box(Modifier.width(568.dp).height(320.dp)) {
                        GameReviewScreen(
                            model = reviewModel(selectedPly = 1),
                            showBoardCoordinates = true,
                            onSaveAndExit = { saveExitClicks += 1 },
                            onFlip = { flipClicks += 1 },
                            onCancel = {},
                            onRetry = {},
                            onSelectPly = {},
                        )
                    }
                }
            }
        }

        compose.onAllNodesWithTag("review_top_bar").assertCountEquals(0)
        compose.onNodeWithTag("review_side_header").assertIsDisplayed()
        compose.onNodeWithTag("review_save_exit")
            .assertTextEquals("Save & exit")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("review_flip").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, saveExitClicks)
            assertEquals(1, flipClicks)
        }

        val reviewBounds = compose.onNodeWithTag("game_review")
            .fetchSemanticsNode()
            .boundsInRoot
        val boardBounds = compose.onNodeWithTag("chess_board_imperial_marble")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(abs(boardBounds.height - reviewBounds.height) <= 1f)
        assertTrue(abs(boardBounds.top - reviewBounds.top) <= 1f)
    }

    @Test
    fun responsiveLayoutKeepsBoardAndReviewPanelReachable() {
        compose.setContent {
            DrawlessTheme {
                Box(Modifier.fillMaxSize()) {
                    GameReviewScreen(
                        model = reviewModel(selectedPly = 1),
                        showBoardCoordinates = true,
                        onSaveAndExit = {},
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
