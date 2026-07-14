package com.drawlesschess.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.drawlesschess.DrawlessApplication
import com.drawlesschess.MainActivity
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RepeatedGameLifecycleInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun fastExitThenSecondGameCompletesANativeBotMove() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()

        // This deliberately exits as soon as the first runtime reaches the game screen, while
        // asynchronous JNI startup may still be opening the process-global Fairy session.
        compose.onNodeWithText("Save & exit").performClick()
        waitForText("Quick Play")
        startWhiteCustomGame()

        compose.onNodeWithTag("game_theme_selector").performClick()
        compose.onNodeWithTag("theme_option_emerald_court").performClick().assertIsSelected()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                compose.onNodeWithTag("chess_board_emerald_court").fetchSemanticsNode()
            }.isSuccess
        }

        compose.onNodeWithContentDescription("White pawn on e2").performClick()
        compose.onNodeWithContentDescription("Empty e4, legal move").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithContentDescription("Empty e2", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 20_000L) {
            compose.onAllNodesWithText("Your move").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            compose.onAllNodesWithText("Engine session has failed", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            // Move-history rows now expose one complete spoken description instead of
            // duplicating the visible SAN text in the accessibility tree. The board and
            // history both still identify e4 through content descriptions.
            compose.onAllNodesWithContentDescription("e4", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun nativeHintCompletesThenTheSameSessionMakesABotMove() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()
        waitForText("Hint")

        compose.onNodeWithText("Hint").performClick()
        compose.waitUntil(timeoutMillis = 20_000L) {
            compose.onAllNodesWithText("Engine suggests", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithText("Candidate move", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithContentDescription("White pawn on e2").performClick()
        compose.onNodeWithContentDescription("Empty e4, legal move").performClick()
        compose.waitUntil(timeoutMillis = 20_000L) {
            compose.onAllNodesWithText("Your move").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithText("Engine session has failed", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun customGameUsesDescriptiveLevelsAndKeepsEscapeAdvanced() {
        dismissRulesGuideIfShown()
        waitForText("Theme ·", substring = true)
        compose.onNodeWithText("Theme ·", substring = true).performClick()
        BoardThemes.all.forEach { theme ->
            compose.onNodeWithTag("theme_option_${theme.id}").performScrollTo().fetchSemanticsNode()
        }
        compose.onNodeWithTag("theme_option_royal_amethyst")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertEquals(BoardThemes.ROYAL_AMETHYST, ThemePreferenceStore(compose.activity).load())
        compose.onNodeWithText("Done").performClick()
        waitForText("Theme · Royal Amethyst")

        waitForText("Custom game")
        compose.onNodeWithText("Custom game").performClick()
        waitForText("Start game")

        compose.onNodeWithTag("play_as_random").assertIsSelected()
        waitForText("White or Black will be chosen when the game starts.")
        assertTrue(compose.onAllNodesWithText("Rated").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Elo", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Escape").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("opponent_option_casual").assertIsSelected()
        compose.onNodeWithTag("selected_opponent_casual").fetchSemanticsNode()
        compose.onNodeWithTag("selected_opponent_name").assertTextEquals("Theo")

        compose.onNodeWithTag("opponent_picker").performScrollToIndex(6)
        compose.onNodeWithTag("opponent_option_grandmaster").performClick().assertIsSelected()
        compose.onNodeWithTag("selected_opponent_grandmaster").fetchSemanticsNode()
        compose.onNodeWithTag("selected_opponent_name").assertTextEquals("Lucian")
        compose.onNodeWithText("Courteous grandmaster").fetchSemanticsNode()

        compose.onNodeWithText("Show options").performScrollTo().performClick()
        waitForText("Escape")

        compose.onNodeWithText("Start game").performClick()
        confirmForfeitIfShown()
        waitForText("Lucian")
        compose.onNodeWithTag("game_opponent_avatar_grandmaster").fetchSemanticsNode()
    }

    @Test
    fun completedGameOffersAWorkingRematch() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        compose.onNodeWithText("Quick Play").performClick()
        confirmForfeitIfShown()
        waitForText("Save & exit")

        compose.onNodeWithText("Resign").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForText("Defeat")
        waitForText("Theo won this game.")
        waitForText("Rematch")

        compose.onNodeWithText("Rematch").performClick()
        waitForText("Your move")
        assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun completedResultSurvivesActivityRecreationWithoutReplayingCelebration() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        compose.activity.runOnUiThread {
            val viewModel = ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
            viewModel.updateGamePreferences(
                viewModel.gamePreferences.copy(celebrationEffectsEnabled = true),
            )
        }
        compose.waitForIdle()
        assertTrue(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
                .gamePreferences.celebrationEffectsEnabled,
        )
        startWhiteCustomGame()

        compose.onNodeWithText("Resign").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForText("Defeat")
        waitForText("Score: 0 / 100")

        // Freeze animation time across recreation. If GameRoute incorrectly restores the
        // one-shot effect, its overlay cannot auto-advance away before this assertion sees it.
        compose.mainClock.autoAdvance = false
        try {
            compose.activityRule.scenario.recreate()

            waitForText("Defeat")
            waitForText("Score: 0 / 100")
            compose.onNodeWithTag("post_game_feedback").fetchSemanticsNode()
            compose.onNodeWithText("Home").fetchSemanticsNode()
            compose.onNodeWithText("Rematch").fetchSemanticsNode()
            assertTrue(
                "Activity recreation replayed the one-shot completion effect",
                compose.onAllNodesWithTag(
                    "completion_effect_overlay",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun privacyInformationIsAvailableFromHome() {
        dismissRulesGuideIfShown()
        waitForText("Privacy")

        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        waitForText("Drawless Chess works entirely offline", substring = true)
        waitForText("realitymaster@protonmail.ch", substring = true)
        waitForText("View policy")

        compose.onNodeWithText("Close").performClick()
        waitForText("Quick Play")
    }

    @Test
    fun unfinishedGameReplacementWarnsCancelsAndThenRecordsAForfeit() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()
        val lossesBefore = loadPlayerLosses()
        compose.onNodeWithText("Save & exit").performClick()
        waitForText("Resume game")

        compose.onNodeWithText("Quick Play").performClick()
        waitForText("Forfeit current game?")
        waitForText("Are you sure you want to forfeit your current game?", substring = true)
        waitForText("It will count as a loss in your stats.", substring = true)
        compose.onNodeWithText("Keep current game").performClick()
        waitForText("Resume game")
        assertEquals(lossesBefore, loadPlayerLosses())
        assertTrue(
            compose.onAllNodesWithText("Forfeit current game?").fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithText("Quick Play").performClick()
        waitForText("Forfeit current game?")
        compose.onNodeWithText("Forfeit & start new game").performClick()
        waitForText("Save & exit")
        assertEquals(lossesBefore + 1, loadPlayerLosses())

        // Finish the replacement so this test leaves no resumable checkpoint behind.
        compose.onNodeWithText("Resign").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForText("Defeat")
    }

    private fun loadPlayerLosses(): Int {
        val application = ApplicationProvider.getApplicationContext<DrawlessApplication>()
        val resultRef = AtomicReference<Result<com.drawlesschess.persistence.PlayerStatistics>?>()
        val completed = CountDownLatch(1)
        application.checkpointStore.loadPlayerStats { result ->
            resultRef.set(result)
            completed.countDown()
        }
        assertTrue("Player statistics load timed out", completed.await(5, TimeUnit.SECONDS))
        return requireNotNull(resultRef.get()).getOrThrow().losses
    }

    private fun startWhiteCustomGame() {
        compose.onNodeWithText("Custom game").performClick()
        waitForText("Start game")
        compose.onNodeWithTag("play_as_white").performClick().assertIsSelected()
        compose.onNodeWithText("Start game").performClick()
        confirmForfeitIfShown()
        waitForText("Save & exit")
    }

    private fun confirmForfeitIfShown() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText("Forfeit current game?").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Save & exit").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Forfeit current game?").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Forfeit & start new game").performClick()
        }
    }

    private fun waitForText(value: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissRulesGuideIfShown() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Quick Play").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Got it").performClick()
        }
    }
}
