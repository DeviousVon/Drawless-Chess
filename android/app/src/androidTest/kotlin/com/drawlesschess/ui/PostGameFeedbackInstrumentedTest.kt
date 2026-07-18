package com.drawlesschess.ui

import android.content.Context
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.R
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameScore
import com.drawlesschess.core.MaterialScore
import com.drawlesschess.core.PositionFacts
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.presentation.GameResultView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.RoundingMode
import java.text.NumberFormat

class PostGameFeedbackInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun victoryFeedbackIsExplicitAndActionsRemainAvailable() {
        val context = targetContext()
        var homeClicks = 0
        var rematchClicks = 0

        compose.setContent {
            DrawlessTheme {
                PostGameBar(
                    result = GameResultView(
                        playerWon = true,
                        playerSide = Side.BLACK,
                        winner = Side.BLACK,
                        reason = EndReason.CHECKMATE,
                        score = GameScore(100, 100, 0),
                    ),
                    onHome = { homeClicks += 1 },
                    onRematch = { rematchClicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("post_game_feedback").fetchSemanticsNode()
        compose.onNodeWithText(context.getString(R.string.game_victory)).fetchSemanticsNode()
        compose.onNodeWithText(context.getString(R.string.game_you_won)).fetchSemanticsNode()
        compose.onNodeWithText(context.getString(R.string.result_checkmate)).fetchSemanticsNode()
        compose.onNodeWithTag("post_game_score")
            .assertTextEquals(context.getString(R.string.game_score, 100, 100))

        compose.onNodeWithTag("post_game_home").performClick()
        compose.onNodeWithTag("post_game_rematch").performClick()
        // performClick waits for Compose to become idle, so the callbacks are complete here.
        // Avoid another ActivityScenario hop after the final click: on some physical devices the
        // shared test host can already be tearing down when this test follows non-Compose tests.
        assertEquals(1, homeClicks)
        assertEquals(1, rematchClicks)
    }

    @Test
    fun terminalExplanationsDistinguishRuleBranchesAndRetainExactFacts() {
        val context = targetContext()
        val results = listOf(
            result(EndReason.STALEMATE, Side.WHITE, RulesContractV1.drawless()),
            result(EndReason.STALEMATE, Side.BLACK, RulesContractV1.escape()),
            result(
                EndReason.REPETITION,
                Side.BLACK,
                facts(mover = Side.BLACK, repetitionAvoiding = 0),
            ),
            result(
                EndReason.REPETITION,
                Side.WHITE,
                facts(mover = Side.BLACK, repetitionAvoiding = 2),
            ),
            result(
                EndReason.DEAD_POSITION_MATERIAL,
                Side.WHITE,
                facts(mover = Side.WHITE, whiteMaterial = 3, blackMaterial = 1),
            ),
            result(
                EndReason.DEAD_POSITION_MATERIAL,
                Side.BLACK,
                facts(mover = Side.BLACK, whiteMaterial = 3, blackMaterial = 3),
            ),
            result(
                EndReason.BARE_KING,
                Side.WHITE,
                facts(mover = Side.WHITE, whiteMaterial = 5, blackMaterial = 0),
            ),
            result(
                EndReason.FIFTY_MOVE_LIMIT,
                Side.WHITE,
                RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.COMPLETING_PLAYER_LOSES),
                facts(mover = Side.BLACK, fiftyAvoiding = 2),
            ),
            result(
                EndReason.FIFTY_MOVE_LIMIT,
                Side.WHITE,
                RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION),
                facts(mover = Side.WHITE, fiftyAvoiding = 0),
            ),
            result(
                EndReason.FIFTY_MOVE_LIMIT,
                Side.WHITE,
                RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY),
                facts(mover = Side.BLACK, whiteMaterial = 8, blackMaterial = 5),
            ),
            result(
                EndReason.FIFTY_MOVE_LIMIT,
                Side.BLACK,
                RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY),
                facts(mover = Side.WHITE, whiteMaterial = 5, blackMaterial = 5, lastCaptureBy = Side.BLACK),
            ),
            result(
                EndReason.FIFTY_MOVE_LIMIT,
                Side.BLACK,
                RulesContractV1.drawless(fiftyMove = FiftyMovePolicy.MATERIAL_VICTORY),
                facts(mover = Side.WHITE, whiteMaterial = 5, blackMaterial = 5),
            ),
        )

        compose.setContent {
            DrawlessTheme {
                Column { results.forEach { result -> Text(resultReasonText(result)) } }
            }
        }

        val white = context.getString(R.string.label_white)
        val black = context.getString(R.string.label_black)
        listOf(
            context.getString(R.string.result_stalemate),
            context.getString(R.string.result_escape_stalemate),
            context.getString(R.string.result_forced_repetition, black, white),
            context.resources.getQuantityString(
                R.plurals.result_avoidable_repetition,
                2,
                black,
                2,
            ),
            context.getString(R.string.result_dead_position_material_score, 3, 1, white),
            context.getString(R.string.result_dead_position_material_tie, 3, black),
            context.getString(R.string.result_bare_king, black, white),
            context.getString(R.string.result_fifty_move_completing_player),
            context.getString(R.string.result_forced_fifty_move, white, black),
            context.getString(R.string.result_fifty_move_material_score, 8, 5, white),
            context.getString(R.string.result_fifty_move_material_last_capture, 5, black),
            context.getString(R.string.result_fifty_move_material_no_capture, 5, black),
        ).forEach { explanation ->
            compose.onNodeWithText(explanation).fetchSemanticsNode()
        }
    }

    @Test
    fun assistedVictoryExplainsEveryAppliedPenaltyAndCareerAverage() {
        val context = targetContext()
        compose.setContent {
            DrawlessTheme {
                PostGameBar(
                    result = GameResultView(
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
                    ),
                    careerAverageGameScore = 64.25,
                    onHome = {},
                    onRematch = {},
                )
            }
        }

        compose.onNodeWithTag("post_game_score")
            .assertTextEquals(context.getString(R.string.game_score, 70, 100))
        compose.onNodeWithTag("career_average_score").assertTextEquals(
            context.getString(R.string.game_career_average, oneDecimal(context, 64.25)),
        )
        compose.onNodeWithTag("hint_score_penalty").assertTextEquals(
            context.getString(R.string.game_penalty, context.getString(R.string.game_hints), 10),
        )
        compose.onNodeWithTag("undo_score_penalty").assertTextEquals(
            context.getString(R.string.game_penalty, context.getString(R.string.game_undos), 10),
        )
        compose.onNodeWithTag("pause_score_penalty").assertTextEquals(
            context.getString(R.string.game_penalty, context.getString(R.string.game_timed_pauses), 5),
        )
        compose.onNodeWithTag("threat_score_penalty").assertTextEquals(
            context.getString(R.string.game_penalty, context.getString(R.string.game_threat_indication), 5),
        )
    }

    @Test
    fun resultScoreAndActionsRemainScrollReachableAtCompactSizeAndDoubleFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DrawlessTheme {
                    Box(Modifier.size(width = 320.dp, height = 640.dp).testTag("compact_result_host")) {
                        PostGameBar(
                            result = GameResultView(
                                playerWon = true,
                                playerSide = Side.WHITE,
                                winner = Side.WHITE,
                                reason = EndReason.REPETITION,
                                score = GameScore(95, 100, 5),
                                rules = RulesContractV1.drawless(),
                                adjudicationFacts = facts(
                                    mover = Side.WHITE,
                                    repetitionAvoiding = 0,
                                ),
                            ),
                            opponentName = "Lucian",
                            onHome = {},
                            onRematch = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("compact_result_host").assertIsDisplayed()
        compose.onNodeWithTag("post_game_score").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("threat_score_penalty")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("post_game_home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("post_game_rematch").assertIsDisplayed()
    }

    @Test
    fun completionTimelinesStaySynchronizedAndReferenceRendererRemainsBounded() {
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
        assertEquals(listOf(CompletionEffectCue.GLASS_IMPACT), defeatCursor.advanceTo(0.04f))
        assertTrue(defeatCursor.advanceTo(0.049f).isEmpty())
        assertEquals(listOf(CompletionEffectCue.GLASS_FRACTURE), defeatCursor.advanceTo(0.05f))
        assertTrue(defeatCursor.advanceTo(0.20f).isEmpty())
        assertEquals(listOf(CompletionEffectCue.GLASS_SHARDS), defeatCursor.advanceTo(0.36f))
        assertTrue(defeatCursor.advanceTo(1f).isEmpty())

        // A dropped frame emits every marker that the visual has actually crossed. Playback is
        // immediate for each returned cue, so there is no independent wall-clock schedule to
        // drift away from a scaled or temporarily stalled Compose animation.
        val droppedFrameCursor = CompletionCueCursor(CompletionEffectTimeline.Defeat.cues)
        assertEquals(
            listOf(CompletionEffectCue.GLASS_IMPACT, CompletionEffectCue.GLASS_FRACTURE),
            droppedFrameCursor.advanceTo(0.20f),
        )
        assertEquals(
            listOf(CompletionEffectCue.GLASS_SHARDS),
            droppedFrameCursor.advanceTo(0.36f),
        )

        val defeat = CompletionEffectTimeline.Defeat
        val fractureProgress = defeat.progressOf(CompletionEffectCue.GLASS_FRACTURE)
        val shardProgress = defeat.progressOf(CompletionEffectCue.GLASS_SHARDS)
        val rayStarts = List(10) { index -> defeat.defeatCrackStartProgress(index, 10) }
        assertEquals(fractureProgress, rayStarts.first())
        assertEquals(7, rayStarts.count { it == fractureProgress })
        assertEquals(3, rayStarts.count { it == shardProgress })
        assertTrue(rayStarts.none { it == defeat.progressOf(CompletionEffectCue.GLASS_IMPACT) })

        listOf(
            CompletionEffectTimeline.Victory to CompletionEffectCue.FIREWORK_LOW,
            CompletionEffectTimeline.Defeat to CompletionEffectCue.GLASS_IMPACT,
        ).forEach { (spec, startCue) ->
            // With animations disabled, play one concise outcome cue. Bursting every layer in
            // the same frame would be noisy and there is no visual timeline left to accompany it.
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
        // This is retained verification code, not the SoundPool production path. A wide bound
        // catches an accidental infinite/degenerate renderer without making CPU speed on an old
        // debug-instrumented tablet a product gate.
        assertTrue("Reference audio rendering took ${renderMillis}ms", renderMillis < 10_000)
    }

    @Test
    fun referenceBoardFoleyIsDistinctDeterministicAndKeepsHeadroom() {
        assertEquals(44_100, SOUND_SAMPLE_RATE)
        val move = renderMoveSound(capture = false)
        val repeatedMove = renderMoveSound(capture = false)
        val capture = renderMoveSound(capture = true)

        assertTrue(move.contentEquals(repeatedMove))
        assertEquals((SOUND_SAMPLE_RATE * 0.48).toInt(), move.size)
        assertEquals((SOUND_SAMPLE_RATE * 0.58).toInt(), capture.size)
        listOf(move, capture).forEach { pcm ->
            val peak = pcm.maxOf { sample -> kotlin.math.abs(sample.toInt()) }
            assertTrue(peak in 20_000..27_000)
            assertTrue(pcm.none { sample -> kotlin.math.abs(sample.toInt()) >= Short.MAX_VALUE })
            assertEquals(0.toShort(), pcm.first())
            assertEquals(0.toShort(), pcm.last())
            assertTrue(pcm.count { sample -> kotlin.math.abs(sample.toInt()) >= 128 } > pcm.size / 2)
        }

        // The capture is a materially stronger impact, not the ordinary move transposed or
        // played louder. Low correlation prevents a regression to one alert tone variant.
        assertTrue(rootMeanSquare(capture) > rootMeanSquare(move) * 1.45)
        assertTrue(kotlin.math.abs(normalizedCorrelation(move, capture)) < 0.20)
    }

    @Test
    fun authoredCaptureCrushAndCheckTicksHaveDistinctTemporalSignatures() {
        val move = renderMoveSound(capture = false)
        val crush = renderCaptureCrushSound()
        val repeatedCrush = renderCaptureCrushSound()
        val tick = renderCheckTickSound()
        val repeatedTick = renderCheckTickSound()

        assertTrue(crush.contentEquals(repeatedCrush))
        assertTrue(tick.contentEquals(repeatedTick))
        assertTrue(rootMeanSquare(crush) > rootMeanSquare(move) * 1.8)
        assertTrue(kotlin.math.abs(normalizedCorrelation(move, crush)) < 0.20)
        assertTrue(kotlin.math.abs(normalizedCorrelation(move, tick)) < 0.12)

        val firstTick = rootMeanSquare(tick, 0.070, 0.142)
        val gap = rootMeanSquare(tick, 0.165, 0.235)
        val secondTick = rootMeanSquare(tick, 0.255, 0.327)
        assertTrue(firstTick > gap * 8.0)
        assertTrue(secondTick > gap * 8.0)
        assertTrue(secondTick > firstTick)
    }

    @Test
    fun staticAudioTrackAcceptsPcmBeforeBecomingInitializedAndStartsPlayback() {
        val pcm = renderCompletionSequence(CompletionEffectTimeline.Victory)
        val track = createStaticAudioTrack(pcm)
        assertTrue("The platform rejected the prepared static audio track", track != null)
        track ?: return

        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            track.play()
            SystemClock.sleep(100)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        } finally {
            try {
                track.pause()
            } catch (_: RuntimeException) {
                // The assertion above should report playback failure; still release the track.
            }
            track.release()
        }
    }

    private fun rootMeanSquare(pcm: ShortArray): Double = kotlin.math.sqrt(
        pcm.sumOf { sample ->
            val value = sample.toDouble() / Short.MAX_VALUE
            value * value
        } / pcm.size,
    )

    private fun rootMeanSquare(pcm: ShortArray, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * SOUND_SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
        val until = (toSeconds * SOUND_SAMPLE_RATE).toInt().coerceIn(from + 1, pcm.size)
        return kotlin.math.sqrt(
            (from until until).sumOf { index ->
                val value = pcm[index].toDouble() / Short.MAX_VALUE
                value * value
            } / (until - from),
        )
    }

    private fun normalizedCorrelation(first: ShortArray, second: ShortArray): Double {
        val size = minOf(first.size, second.size)
        var dot = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        repeat(size) { index ->
            val left = first[index].toDouble()
            val right = second[index].toDouble()
            dot += left * right
            firstEnergy += left * left
            secondEnergy += right * right
        }
        return dot / kotlin.math.sqrt(firstEnergy * secondEnergy).coerceAtLeast(1.0)
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun result(
        reason: EndReason,
        winner: Side,
        rules: RulesContractV1 = RulesContractV1.drawless(),
        adjudicationFacts: PositionFacts? = null,
    ) = GameResultView(
        playerWon = winner == Side.WHITE,
        playerSide = Side.WHITE,
        winner = winner,
        reason = reason,
        score = GameScore(if (winner == Side.WHITE) 100 else 0, 100, 0),
        rules = rules,
        adjudicationFacts = adjudicationFacts,
    )

    private fun result(
        reason: EndReason,
        winner: Side,
        adjudicationFacts: PositionFacts,
    ) = result(reason, winner, RulesContractV1.drawless(), adjudicationFacts)

    private fun facts(
        mover: Side,
        repetitionAvoiding: Int = 1,
        fiftyAvoiding: Int = 1,
        whiteMaterial: Int = 0,
        blackMaterial: Int = 0,
        lastCaptureBy: Side? = null,
    ) = PositionFacts(
        mover = mover,
        legalMovesAfter = 1,
        sideToMoveInCheck = false,
        positionOccurrenceCount = 3,
        repetitionAvoidingAlternativesBeforeMove = repetitionAvoiding,
        halfmoveClockAfter = 100,
        fiftyMoveAvoidingAlternativesBeforeMove = fiftyAvoiding,
        deadPositionAfter = whiteMaterial + blackMaterial > 0,
        moveWasCapture = false,
        materialAfter = MaterialScore(whiteMaterial, blackMaterial),
        lastCaptureBy = lastCaptureBy,
    )

    private fun oneDecimal(context: Context, value: Double): String {
        val locale = context.resources.configuration.locales[0]
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }
}
