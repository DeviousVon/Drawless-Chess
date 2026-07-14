package com.drawlesschess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drawlesschess.persistence.OpponentStatistics
import com.drawlesschess.persistence.PlayerStatistics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerStatsScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedCareerShowsRecordAverageStreaksAndOpponentBreakdown() {
        var backClicks = 0
        compose.setContent {
            DrawlessTheme {
                PlayerStatsScreen(
                    state = PlayerStatsState.Ready(
                        sampleStatistics(),
                    ),
                    onBack = { backClicks += 1 },
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag("player_stats_content").assertIsDisplayed()
        compose.onNodeWithText("5 completed games").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record: 3 wins, 2 losses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Average game score: 54.0").assertIsDisplayed()
        compose.onNodeWithText("Theo · Casual").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5 games · 3–2 · latest strength: 650 estimated Elo").assertIsDisplayed()
        compose.onNodeWithText("Stats are derived from completed games.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("private app storage", substring = true).assertDoesNotExist()
        compose.onNodeWithText("discarded games", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backClicks)
    }

    @Test
    fun emptyCareerUsesDashesInsteadOfPretendingTheAverageIsZero() {
        compose.setContent {
            DrawlessTheme {
                PlayerStatsScreen(
                    state = PlayerStatsState.Ready(
                        PlayerStatistics(
                            localProfileId = "local-profile",
                            displayName = "Player",
                            avatarId = null,
                            completedGames = 0,
                            wins = 0,
                            losses = 0,
                            winPercentage = null,
                            averageScore = null,
                            currentWinStreak = 0,
                            bestWinStreak = 0,
                            unassistedWins = 0,
                            opponents = emptyList(),
                        ),
                    ),
                    onBack = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Your first result starts here").assertIsDisplayed()
        compose.onNodeWithText("No completed games yet").assertDoesNotExist()
        compose.onNodeWithTag("stats_average_score")
            .assertContentDescriptionEquals("Average game score: Not available")
    }

    @Test
    fun compactDoubleFontLayoutKeepsMetricsAndOpponentBreakdownReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                        PlayerStatsScreen(
                            state = PlayerStatsState.Ready(sampleStatistics()),
                            onBack = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Unassisted wins: 1 unassisted wins")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Theo · Casual").performScrollTo().assertIsDisplayed()
    }

    private fun sampleStatistics(): PlayerStatistics = PlayerStatistics(
        localProfileId = "local-profile",
        displayName = "Player",
        avatarId = null,
        completedGames = 5,
        wins = 3,
        losses = 2,
        winPercentage = 60.0,
        averageScore = 54.0,
        currentWinStreak = 2,
        bestWinStreak = 2,
        unassistedWins = 1,
        opponents = listOf(
            OpponentStatistics(
                opponentStableId = "bot:casual",
                opponentExactElo = 650,
                completedGames = 5,
                wins = 3,
                losses = 2,
                winPercentage = 60.0,
                averageScore = 54.0,
            ),
        ),
    )
}
