package com.drawlesschess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.persistence.CompletedGameHistory
import com.drawlesschess.persistence.GameHistoryEntry
import com.drawlesschess.persistence.HistoricalReviewAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GameHistoryInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun historyShowsResultSideAndReviewStateAndOpensTheSelectedGame() {
        var openedGameId: String? = null
        val entries = listOf(
            entry(
                gameId = "newest",
                playerSide = Side.WHITE,
                winner = Side.WHITE,
                availability = HistoricalReviewAvailability.READY,
                completedAt = 2_000L,
            ),
            entry(
                gameId = "older",
                playerSide = Side.BLACK,
                winner = Side.WHITE,
                availability = HistoricalReviewAvailability.NOT_ANALYZED,
                completedAt = 1_000L,
            ),
        )

        compose.setContent {
            DrawlessTheme {
                GameHistoryScreen(
                    state = GameHistoryState.Ready(entries),
                    onBack = {},
                    onRetry = {},
                    onOpenGame = { openedGameId = it },
                )
            }
        }

        compose.onNodeWithText("Game history").assertIsDisplayed()
        compose.onAllNodesWithText("Win").assertCountEquals(1)
        compose.onAllNodesWithText("Loss").assertCountEquals(1)
        compose.onNodeWithText("Review ready").assertIsDisplayed()
        compose.onNodeWithText("Not analyzed").assertIsDisplayed()
        compose.onNodeWithTag("history_game_newest")
            .assertHeightIsAtLeast(88.dp)
            .assert(hasContentDescription("Win", substring = true))
            .assert(hasContentDescription("You played White", substring = true))
            .assert(hasContentDescription("Review ready", substring = true))
            .performClick()
        compose.runOnIdle { assertEquals("newest", openedGameId) }

        val newestTop = compose.onNodeWithTag("history_game_newest")
            .fetchSemanticsNode().boundsInRoot.top
        val olderTop = compose.onNodeWithTag("history_game_older")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(newestTop < olderTop)
    }

    @Test
    fun historyRemainsScrollableAndActionableAtTwoHundredPercentFont() {
        val entries = (1..8).map { index ->
            entry(
                gameId = "game-$index",
                playerSide = if (index % 2 == 0) Side.WHITE else Side.BLACK,
                winner = Side.WHITE,
                availability = if (index == 8) {
                    HistoricalReviewAvailability.STALE
                } else {
                    HistoricalReviewAvailability.READY
                },
                completedAt = index.toLong(),
            )
        }.reversed()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        GameHistoryScreen(
                            state = GameHistoryState.Ready(entries),
                            onBack = {},
                            onRetry = {},
                            onOpenGame = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("history_back").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("history_game_game-8").assertIsDisplayed()
        compose.onNodeWithText("Review update needed").assertIsDisplayed()
    }

    private fun entry(
        gameId: String,
        playerSide: Side,
        winner: Side,
        availability: HistoricalReviewAvailability,
        completedAt: Long,
    ): GameHistoryEntry = GameHistoryEntry(
        game = CompletedGameHistory(
            gameId = gameId,
            completedAtEpochMillis = completedAt,
            completionSequence = completedAt,
            recordFormatVersion = 1,
            opponentStableId = "unknown",
            opponentExactElo = null,
            initialFen = ChessPosition.START_FEN,
            moves = emptyList(),
            rules = RulesContractV1.drawless(),
            playerSide = playerSide,
            outcome = GameOutcome(winner, reason = EndReason.RESIGNATION),
            playerWon = winner == playerSide,
        ),
        reviewAvailability = availability,
    )
}
