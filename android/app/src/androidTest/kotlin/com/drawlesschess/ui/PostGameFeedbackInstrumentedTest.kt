package com.drawlesschess.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.Side
import com.drawlesschess.core.presentation.GameResultView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PostGameFeedbackInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun victoryFeedbackIsExplicitAndActionsRemainAvailable() {
        var homeClicks = 0
        var rematchClicks = 0

        compose.setContent {
            DrawlessTheme {
                PostGameBar(
                    result = GameResultView(
                        playerWon = true,
                        playerSide = Side.BLACK,
                        reason = EndReason.CHECKMATE,
                        explanation = "BLACK wins by checkmate",
                    ),
                    onHome = { homeClicks += 1 },
                    onRematch = { rematchClicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("post_game_feedback").fetchSemanticsNode()
        compose.onNodeWithText("Victory").fetchSemanticsNode()
        compose.onNodeWithText("You won this game.").fetchSemanticsNode()
        compose.onNodeWithText("BLACK wins by checkmate").fetchSemanticsNode()

        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Rematch").performClick()
        // performClick waits for Compose to become idle, so the callbacks are complete here.
        // Avoid another ActivityScenario hop after the final click: on some physical devices the
        // shared test host can already be tearing down when this test follows non-Compose tests.
        assertEquals(1, homeClicks)
        assertEquals(1, rematchClicks)
    }
}
