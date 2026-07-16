package com.drawlesschess.ui

import android.content.Context
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
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.R
import com.drawlesschess.persistence.OpponentStatistics
import com.drawlesschess.persistence.PlayerStatistics
import java.math.RoundingMode
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerStatsScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedCareerShowsRecordAverageStreaksAndOpponentBreakdown() {
        val context = targetContext()
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
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.stats_completed_games, 5, 5),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            metricDescription(
                context,
                R.string.stats_record,
                context.getString(R.string.stats_record_accessibility, 3, 2),
            ),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            metricDescription(context, R.string.stats_average_game_score, oneDecimal(context, 54.0)),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(
                R.string.game_title_summary,
                context.getString(R.string.opponent_theo_name),
                context.getString(R.string.difficulty_casual),
            ),
        ).performScrollTo().assertIsDisplayed()
        val record = context.resources.getQuantityString(R.plurals.stats_opponent_games_record, 5, 5, 3, 2)
        val strength = context.getString(R.string.stats_latest_strength, 650)
        compose.onNodeWithText("$record · $strength").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stats_source_notice))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("stats_back").performClick()
        assertEquals(1, backClicks)
    }

    @Test
    fun emptyCareerUsesDashesInsteadOfPretendingTheAverageIsZero() {
        val context = targetContext()
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

        compose.onNodeWithText(context.getString(R.string.stats_first_result)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_stats_empty)).assertDoesNotExist()
        compose.onNodeWithTag("stats_average_score")
            .assertContentDescriptionEquals(
                metricDescription(
                    context,
                    R.string.stats_average_game_score,
                    context.getString(R.string.label_not_available),
                ),
            )
    }

    @Test
    fun compactDoubleFontLayoutKeepsMetricsAndOpponentBreakdownReachable() {
        val context = targetContext()
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

        compose.onNodeWithContentDescription(
            metricDescription(
                context,
                R.string.stats_unassisted_wins,
                context.resources.getQuantityString(R.plurals.stats_unassisted_wins_accessibility, 1, 1),
            ),
        )
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(
                R.string.game_title_summary,
                context.getString(R.string.opponent_theo_name),
                context.getString(R.string.difficulty_casual),
            ),
        ).performScrollTo().assertIsDisplayed()
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

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun metricDescription(context: Context, labelRes: Int, value: String): String =
        context.getString(R.string.stats_metric_accessibility, context.getString(labelRes), value)

    private fun oneDecimal(context: Context, value: Double): String {
        val locale = context.resources.configuration.locales[0]
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }
}
