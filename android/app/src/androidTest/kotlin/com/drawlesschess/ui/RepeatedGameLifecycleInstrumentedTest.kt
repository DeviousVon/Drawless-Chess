package com.drawlesschess.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.drawlesschess.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RepeatedGameLifecycleInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun fastExitThenSecondGameCompletesANativeBotMove() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        compose.onNodeWithText("Quick Play").performClick()
        waitForText("Exit")

        // This deliberately exits as soon as the first runtime reaches the game screen, while
        // asynchronous JNI startup may still be opening the process-global Fairy session.
        compose.onNodeWithText("Exit").performClick()
        waitForText("Quick Play")
        compose.onNodeWithText("Quick Play").performClick()
        waitForText("Exit")

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
            compose.onAllNodesWithText("e4", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun nativeHintCompletesThenTheSameSessionMakesABotMove() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        compose.onNodeWithText("Quick Play").performClick()
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
        waitForText("Custom game")
        compose.onNodeWithText("Custom game").performClick()
        waitForText("Start game")

        assertTrue(compose.onAllNodesWithText("Rated").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("900", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Escape").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Show options").performScrollTo().performClick()
        waitForText("Escape")
    }

    @Test
    fun completedGameOffersAWorkingRematch() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        compose.onNodeWithText("Quick Play").performClick()
        waitForText("Exit")

        compose.onNodeWithText("Resign").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForText("Defeat")
        waitForText("Your opponent won this game.")
        waitForText("Rematch")

        compose.onNodeWithText("Rematch").performClick()
        waitForText("Your move")
        assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())
    }

    private fun waitForText(value: String) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
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
