package com.drawlesschess.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.Side
import com.drawlesschess.core.presentation.GameResultView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun completionTimelinesAndProceduralCuesStaySynchronizedAndDeterministic() {
        assertTrue(CompletionEffectTimeline.Victory.durationMillis >= 2_000)
        assertTrue(CompletionEffectTimeline.Defeat.durationMillis >= 2_000)

        val victoryCursor = CompletionCueCursor(CompletionEffectTimeline.Victory.cues)
        assertTrue(victoryCursor.advanceTo(0.07f).isEmpty())
        assertEquals(listOf(CompletionEffectCue.FIREWORK_LOW), victoryCursor.advanceTo(0.08f))
        assertTrue(victoryCursor.advanceTo(0.08f).isEmpty())
        assertEquals(listOf(CompletionEffectCue.FIREWORK_MID), victoryCursor.advanceTo(0.40f))
        assertEquals(listOf(CompletionEffectCue.FIREWORK_HIGH), victoryCursor.advanceTo(1f))
        assertTrue(victoryCursor.advanceTo(1f).isEmpty())

        val defeatCursor = CompletionCueCursor(CompletionEffectTimeline.Defeat.cues)
        assertTrue(defeatCursor.advanceTo(0.03f).isEmpty())
        assertEquals(
            listOf(CompletionEffectCue.GLASS_IMPACT, CompletionEffectCue.GLASS_FRACTURE),
            defeatCursor.advanceTo(0.20f),
        )
        assertTrue(defeatCursor.advanceTo(0.20f).isEmpty())
        assertEquals(listOf(CompletionEffectCue.GLASS_SHARDS), defeatCursor.advanceTo(0.36f))
        assertTrue(defeatCursor.advanceTo(1f).isEmpty())

        listOf(
            CompletionEffectTimeline.Victory to CompletionEffectCue.FIREWORK_LOW,
            CompletionEffectTimeline.Defeat to CompletionEffectCue.GLASS_IMPACT,
        ).forEach { (spec, startCue) ->
            val reducedMotionCursor = CompletionCueCursor(spec.cues)
            assertEquals(listOf(startCue), reducedMotionCursor.advanceTo(1f))
            assertTrue(reducedMotionCursor.advanceTo(1f).isEmpty())
            assertTrue(reducedMotionCursor.advanceTo(0.5f).isEmpty())
            assertTrue(reducedMotionCursor.advanceTo(1f).isEmpty())
        }

        val specs = listOf(CompletionEffectTimeline.Victory, CompletionEffectTimeline.Defeat)
        assertEquals(
            CompletionEffectCue.entries.toSet(),
            specs.flatMap { spec -> spec.cues.map(TimedCompletionCue::cue) }.toSet(),
        )
        val renderStarted = SystemClock.elapsedRealtimeNanos()
        specs.forEach { spec ->
            val first = renderCompletionSequence(spec)
            val second = renderCompletionSequence(spec)
            assertTrue(first.contentEquals(second))
            assertTrue(first.size > SOUND_SAMPLE_RATE)
            val peak = first.maxOf { sample -> kotlin.math.abs(sample.toInt()) }
            assertTrue(peak in 1_000 until Short.MAX_VALUE.toInt())
            assertTrue(first.count { it != 0.toShort() } > first.size / 4)

            val firstProgress = spec.cues.first().progress
            spec.cues.forEach { timedCue ->
                val offset = (
                    (timedCue.progress - firstProgress) *
                        spec.durationMillis * SOUND_SAMPLE_RATE / 1_000f
                    ).toInt()
                val end = minOf(first.size, offset + SOUND_SAMPLE_RATE / 20)
                assertTrue((offset until end).any { index -> first[index] != 0.toShort() })
            }
        }
        val renderMillis = (SystemClock.elapsedRealtimeNanos() - renderStarted) / 1_000_000
        Log.i("DrawlessAudioTiming", "two_pass_sequence_render_ms=$renderMillis")
        assertTrue("Procedural audio rendering took ${renderMillis}ms", renderMillis < 2_000)
    }
}
