package com.drawlesschess.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.R
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameScore
import com.drawlesschess.core.Side
import com.drawlesschess.core.presentation.GameResultView
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PostGameOverlayInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitReviewPromptLeavesResultTextReachableAtDoubleFontScale() {
        assertAwaitingReviewLayout(width = 320, height = 640)
    }

    @Test
    fun landscapeReviewPromptLeavesResultTextReachableAtDoubleFontScale() {
        assertAwaitingReviewLayout(width = 640, height = 320)
    }

    @Test
    fun darkReviewPromptUsesReadableThemeTextDespiteBlackAmbientContentColor() {
        assertPromptColors(dark = true)
    }

    @Test
    fun lightReviewPromptUsesReadableThemeTextDespiteBlackAmbientContentColor() {
        assertPromptColors(dark = false)
    }

    private fun assertAwaitingReviewLayout(width: Int, height: Int) {
        val ready = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.size(width = width.dp, height = height.dp)) {
                        PostGameBar(
                            result = assistedVictory(),
                            opponentName = "Theo",
                            careerAverageGameScore = 64.25,
                            onHome = {},
                            onQuickPlay = {},
                            onRematch = {},
                            awaitingReview = true,
                            reviewTapReady = ready.value,
                        )
                    }
                }
            }
        }

        assertResultActionsAbsent()
        val prompt = compose.onNodeWithTag("post_game_review_prompt", useUnmergedTree = true)
        val reservedPromptBounds = prompt.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { ready.value = true }
        assertResultActionsAbsent()
        prompt.assertIsDisplayed()
        assertEquals(
            "Finishing the completion cue must not move the prompt over result text",
            reservedPromptBounds,
            prompt.fetchSemanticsNode().boundsInRoot,
        )

        listOf(
            "post_game_score",
            "career_average_score",
            "hint_score_penalty",
            "undo_score_penalty",
            "pause_score_penalty",
            "threat_score_penalty",
        ).forEach { tag ->
            val resultText = compose.onNodeWithTag(tag)
            resultText.performScrollTo().assertIsDisplayed()
            val textBounds = resultText.fetchSemanticsNode().boundsInRoot
            val promptBounds = prompt.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag overlaps the review prompt", textBounds.bottom <= promptBounds.top)
            prompt.assertIsDisplayed()
        }
        val contentBounds = compose.onNodeWithTag("post_game_feedback")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The prompt must have its own footer below the result's scroll viewport",
            contentBounds.bottom <= prompt.fetchSemanticsNode().boundsInRoot.top,
        )
    }

    private fun assertResultActionsAbsent() {
        listOf("post_game_quick_play", "post_game_rematch", "post_game_home").forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun assertPromptColors(dark: Boolean) {
        var expectedTextColor = Color.Unspecified
        var elevatedSurfaceColor = Color.Unspecified
        compose.setContent {
            val forcedConfiguration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides forcedConfiguration) {
                DrawlessTheme {
                    val colors = MaterialTheme.colorScheme
                    SideEffect {
                        expectedTextColor = colors.onSurface
                        elevatedSurfaceColor = colors.surfaceColorAtElevation(8.dp)
                    }
                    CompositionLocalProvider(LocalContentColor provides Color.Black) {
                        PostGameReviewPrompt()
                    }
                }
            }
        }

        val promptText = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.game_tap_anywhere_to_review)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(promptText, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("Prompt must expose its rendered text layout", action(layouts))
            }
        assertEquals(1, layouts.size)
        val actualTextColor = layouts.single().layoutInput.style.color
        assertEquals("Prompt text must use this theme's onSurface color", expectedTextColor, actualTextColor)
        val textLuminance = actualTextColor.luminance()
        val backgroundLuminance = elevatedSurfaceColor.luminance()
        val contrast = (max(textLuminance, backgroundLuminance) + 0.05f) /
            (min(textLuminance, backgroundLuminance) + 0.05f)
        assertTrue("Prompt text contrast is $contrast; expected at least 4.5:1", contrast >= 4.5f)
    }

    private fun assistedVictory() = GameResultView(
        playerWon = true,
        playerSide = Side.WHITE,
        winner = Side.WHITE,
        reason = EndReason.CHECKMATE,
        score = GameScore(
            points = 70,
            maximumPoints = 100,
            threatIndicationPenalty = 5,
            hintPenalty = 10,
            undoPenalty = 10,
            timedPausePenalty = 5,
        ),
    )
}
