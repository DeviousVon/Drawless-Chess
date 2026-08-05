package com.drawlesschess.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.drawlesschess.DrawlessApplication
import com.drawlesschess.MainActivity
import com.drawlesschess.R
import com.drawlesschess.core.engine.REVIEW_ANALYSIS_VERSION
import com.drawlesschess.core.engine.REVIEW_EVIDENCE_SCHEMA_VERSION
import com.drawlesschess.core.engine.ReviewGradingPolicy
import com.drawlesschess.core.engine.ReviewScoreSource
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        compose.onNodeWithTag("theme_option_verdigris_copper").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                compose.onNodeWithTag("chess_board_verdigris_copper").fetchSemanticsNode()
            }.isSuccess
        }

        compose.onNodeWithTag("board_square_e2").performClick()
        compose.onNodeWithTag("board_square_e4").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                compose.onNodeWithTag("board_square_e2").assertContentDescriptionEquals(
                    compose.activity.getString(R.string.board_empty_square, "e2"),
                )
            }.isSuccess
        }
        waitForStatus(R.string.status_your_turn, timeoutMillis = 20_000L)

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

        compose.onNodeWithTag("board_square_e2").performClick()
        compose.onNodeWithTag("board_square_e4").performClick()
        waitForStatus(R.string.status_your_turn, timeoutMillis = 20_000L)
        assertTrue(
            compose.onAllNodesWithText("Engine session has failed", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun customGameUsesDescriptiveLevelsAndKeepsEscapeAdvanced() {
        dismissRulesGuideIfShown()
        compose.onNodeWithTag("home_brand_hero").assertIsDisplayed()
        waitForText("Theme ·", substring = true)
        compose.onNodeWithTag("home_theme").performClick()
        BoardThemes.all.forEach { theme ->
            compose.onNodeWithTag("theme_option_${theme.id}").performScrollTo().fetchSemanticsNode()
        }
        compose.onNodeWithTag("theme_option_amethyst_geode")
            .performScrollTo()
            .performClick()
        assertEquals(BoardThemes.AMETHYST_GEODE, ThemePreferenceStore(compose.activity).load())
        waitForText("Theme · Amethyst Geode")
        compose.onNodeWithTag("home_brand_hero").assertIsDisplayed()

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

        val grandmasterIndex = OpponentProfiles.all.indexOfFirst { it.level.id == "grandmaster" }
        compose.onNodeWithTag("opponent_picker").performScrollToIndex(grandmasterIndex)
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

        compose.onNodeWithTag("resign_button").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        openPostGameReview()
        compose.waitUntil(timeoutMillis = 30_000L) {
            compose.onAllNodesWithTag("review_rematch").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review_rematch").performScrollTo().performClick()
        waitForStatus(R.string.status_your_turn)
        assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun completedReviewSaveExitOffersWorkingQuickPlayFromHome() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()

        compose.onNodeWithTag("resign_button").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        openPostGameReview()
        compose.onNodeWithTag("review_save_exit").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("home_quick_play").performClick()
        waitForText("Save & exit")
        waitForText("Theo")
        assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())
        assertTrue(
            compose.onAllNodesWithText("Forfeit current game?").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun completedGameRunsANativeReviewAndSavesAndExitsToHome() {
        assertFalse(
            "Native review smoke must not use the development engine",
            com.drawlesschess.BuildConfig.USE_DEVELOPMENT_ENGINE,
        )
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()

        compose.onNodeWithTag("board_square_e2").performClick()
        compose.onNodeWithTag("board_square_e4").performClick()
        waitForStatus(R.string.status_your_turn, timeoutMillis = 20_000L)
        compose.onNodeWithTag("board_square_g1").performClick()
        compose.onNodeWithTag("board_square_f3").performClick()
        waitForStatus(R.string.status_your_turn, timeoutMillis = 20_000L)

        compose.onNodeWithTag("resign_button").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForPostGameReviewTapGate()
        val runtimeBeforeRecreation = requireNotNull(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].runtime,
        )
        compose.waitUntil(timeoutMillis = 30_000L) {
            when (val state = runtimeBeforeRecreation.gameReviewState().value) {
                is RuntimeGameReviewState.Analyzing ->
                    (state.progress?.completedPositions ?: 0) >= 1
                is RuntimeGameReviewState.Complete -> true
                else -> false
            }
        }
        assertTrue(
            "Review opened before the player acknowledged the completed presentation",
            compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("post_game_review_tap_gate").performClick()
        waitForText("Game review")
        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .assertIsOff()
        compose.onNodeWithTag("review_move_1").fetchSemanticsNode()
        assertTrue(
            "Opponent move was visible before the player enabled it",
            compose.onAllNodesWithTag("review_move_2").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("review_show_opponent_moves").performClick().assertIsOn()
        compose.onNodeWithTag("review_next").performScrollTo().performClick()
        compose.runOnIdle {}
        compose.onNodeWithTag("review_move_2").assertIsSelected()
        val stateBeforeRecreation = runtimeBeforeRecreation.gameReviewState().value
        val completedBeforeRecreation =
            (stateBeforeRecreation as? RuntimeGameReviewState.Analyzing)
                ?.progress?.completedPositions ?: 0

        compose.activityRule.scenario.recreate()
        waitForText("Game review")
        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .assertIsOn()
        compose.onNodeWithTag("review_move_2").assertIsSelected()
        val runtimeAfterRecreation = requireNotNull(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].runtime,
        )
        assertTrue(
            "Activity recreation replaced the runtime that owns the active review",
            runtimeBeforeRecreation === runtimeAfterRecreation,
        )
        compose.waitUntil(timeoutMillis = 30_000L) {
            when (val state = runtimeAfterRecreation.gameReviewState().value) {
                is RuntimeGameReviewState.Analyzing ->
                    stateBeforeRecreation is RuntimeGameReviewState.Analyzing &&
                        (state.progress?.completedPositions ?: 0) >= completedBeforeRecreation
                is RuntimeGameReviewState.Complete -> true
                else -> false
            }
        }
        compose.waitUntil(timeoutMillis = 30_000L) {
            compose.onAllNodesWithText("Review complete").fetchSemanticsNodes().isNotEmpty()
        }
        val completedReview = runtimeAfterRecreation.gameReviewState().value
            as? RuntimeGameReviewState.Complete
            ?: error("Native review did not publish its completed evidence")
        assertEquals(REVIEW_EVIDENCE_SCHEMA_VERSION, completedReview.result.evidenceSchemaVersion)
        assertEquals(REVIEW_ANALYSIS_VERSION, completedReview.result.analysisVersion)
        assertEquals(ReviewGradingPolicy.CURRENT.version, completedReview.result.gradingPolicyVersion)
        assertTrue(
            "Native review did not retain all three MultiPV candidates",
            completedReview.result.moves.all { move -> move.evidence?.lines?.size == 3 },
        )
        assertTrue(
            "Native review did not retain depth-tagged WDL evidence",
            completedReview.result.moves.flatMap { move -> move.evidence?.lines.orEmpty() }
                .any { line -> line.source == ReviewScoreSource.WDL && line.depth != null },
        )
        compose.onNodeWithTag("review_show_opponent_moves")
            .performScrollTo()
            .performClick()
            .assertIsOff()
        compose.onNodeWithTag("review_move_3").assertIsSelected()
        compose.onNodeWithTag("board_square_f3").assertContentDescriptionEquals(
            compose.activity.getString(
                R.string.board_piece_square,
                compose.activity.getString(
                    R.string.side_piece,
                    compose.activity.getString(R.string.label_white),
                    compose.activity.getString(R.string.piece_knight),
                ),
                "f3",
            ),
        )
        assertTrue(
            "Opponent move remained visible after the player hid it",
            compose.onAllNodesWithTag("review_move_2").fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag("review_save_exit").performClick()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isNotEmpty()
        }
        val viewModelAfterExit =
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
        assertEquals(AppRoute.HOME, viewModelAfterExit.route)
        assertEquals(null, viewModelAfterExit.runtime)
        assertTrue(compose.onAllNodesWithText("Resume game").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun idleAnalysisMakesAnOffMultiPvReviewReadyBeforePostGameTap() {
        assertFalse(
            "Native review latency test must not use the development engine",
            com.drawlesschess.BuildConfig.USE_DEVELOPMENT_ENGINE,
        )
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()
        val runtime = requireNotNull(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].runtime,
        )

        compose.waitUntil(timeoutMillis = 20_000L) {
            runtime.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1]?.isNotEmpty() == true
        }
        val candidates = requireNotNull(
            runtime.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1],
        )
        val played = listOf("a2a3", "h2h3", "b2b3", "g2g3", "a2a4", "h2h4")
            .map { value -> com.drawlesschess.core.UciMove(value) }
            .first { it !in candidates }
        compose.onNodeWithTag("board_square_${played.value.substring(0, 2)}").performClick()
        compose.onNodeWithTag("board_square_${played.value.substring(2, 4)}").performClick()
        waitForStatus(R.string.status_your_turn, timeoutMillis = 20_000L)

        compose.waitUntil(timeoutMillis = 20_000L) {
            1 in runtime.reviewPrefetchDiagnostics().adjacentFallbackPlies
        }
        val finalReviewSubmissionsBeforeGameEnd = runtime.reviewEngineSubmissionCount()
        compose.onNodeWithTag("resign_button").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForPostGameReviewTapGate()

        // GameRoute must finish the fully seeded evidence behind the result presentation, before
        // the player acknowledges it, and without another engine submission.
        compose.waitUntil(timeoutMillis = 5_000L) {
            runtime.currentGameReviewState() is RuntimeGameReviewState.Complete
        }
        val complete = runtime.currentGameReviewState() as RuntimeGameReviewState.Complete
        assertEquals(
            "Final review submitted new engine work instead of consuming the in-game cache",
            finalReviewSubmissionsBeforeGameEnd,
            runtime.reviewEngineSubmissionCount(),
        )
        assertTrue(complete.result.moves.single().evidence?.usedAdjacentFallback == true)

        assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("post_game_review_tap_gate").performClick()
        waitForText("Game review")
        waitForText("Review complete")
    }

    @Test
    fun saveExitAndResumeRestoresReviewPrefetchWithoutAnotherEngineSearch() {
        assertFalse(
            "Review resume test must use the embedded native engine",
            com.drawlesschess.BuildConfig.USE_DEVELOPMENT_ENGINE,
        )
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        startWhiteCustomGame()
        val runtimeBeforeExit = requireNotNull(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].runtime,
        )
        compose.waitUntil(timeoutMillis = 20_000L) {
            runtimeBeforeExit.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1]
                ?.isNotEmpty() == true
        }
        val candidatesBeforeExit = requireNotNull(
            runtimeBeforeExit.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1],
        )
        assertEquals(1, runtimeBeforeExit.reviewPrefetchEngineSubmissionCount())

        compose.onNodeWithTag("game_save_exit").performClick()
        waitForText("Resume game")
        compose.onNodeWithText("Resume game").performClick()
        waitForText("Save & exit")

        val resumedRuntime = requireNotNull(
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].runtime,
        )
        assertTrue(runtimeBeforeExit !== resumedRuntime)
        assertEquals(
            candidatesBeforeExit,
            resumedRuntime.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1],
        )
        assertEquals(
            "Resume submitted the already-completed current review root again",
            0,
            resumedRuntime.reviewPrefetchEngineSubmissionCount(),
        )
    }

    @Test
    fun postGameTapGateAndReviewExitSurviveActivityRecreation() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        val originalPreferences = currentGamePreferences()
        try {
            setGamePreferences(originalPreferences.copy(celebrationEffectsEnabled = true))
            startWhiteCustomGame()

            compose.onNodeWithTag("resign_button").performClick()
            waitForText("Resign this game?")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Resign game").performClick()
            compose.mainClock.advanceTimeBy(100L)
            compose.waitForIdle()
            compose.onNodeWithTag("completion_effect_overlay", useUnmergedTree = true)
                .fetchSemanticsNode()
            compose.onNodeWithTag("post_game_presentation_input_blocker")
                .assertHasNoClickAction()
                .performTouchInput { click() }
            assertTrue(compose.onAllNodesWithTag("post_game_review_tap_gate").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
            assertTrue(
                "Underlying result actions remained accessible during the presentation",
                compose.onAllNodesWithText("Home").fetchSemanticsNodes().isEmpty(),
            )

            val defeat = CompletionEffectTimeline.Defeat
            compose.mainClock.advanceTimeBy(defeat.durationMillis.toLong())
            compose.waitForIdle()
            assertTrue(
                compose.onAllNodesWithTag(
                    "completion_effect_overlay",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )
            // A single large virtual-clock step can deliver the final shard cue at the visual
            // endpoint. Advance through that longest possible newly-started sample as well.
            val latestPossibleCueTail =
                CompletionEffectCue.GLASS_SHARDS.audioEndUptimeMillis(0L)
            compose.mainClock.advanceTimeBy(
                maxOf(defeat.postVisualAudioTailMillis, latestPossibleCueTail) + 100L,
            )
            compose.waitForIdle()
            compose.onNodeWithTag("post_game_review_tap_gate").fetchSemanticsNode()
            assertTrue(
                "Review opened without the player's post-game tap",
                compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty(),
            )

            // Recreation must keep the result acknowledgement pending without replaying the
            // one-shot celebration or navigating on its own.
            compose.activityRule.scenario.recreate()
            compose.mainClock.advanceTimeBy(defeat.postVisualAudioTailMillis + 50L)
            compose.waitForIdle()
            compose.onNodeWithTag("post_game_review_tap_gate").fetchSemanticsNode()
            assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())

            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("post_game_review_tap_gate").performClick()
            waitForText("Game review")
            assertTrue(
                "Activity recreation replayed the one-shot completion effect",
                compose.onAllNodesWithTag(
                    "completion_effect_overlay",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )

            compose.activityRule.scenario.recreate()
            waitForText("Game review")

            compose.activityRule.scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(timeoutMillis = 10_000L) {
                compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isNotEmpty()
            }
            val viewModelAfterBack =
                ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
            assertEquals(AppRoute.HOME, viewModelAfterBack.route)
            assertEquals(null, viewModelAfterBack.runtime)
            assertTrue(compose.onAllNodesWithText("Resume game").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Defeat").fetchSemanticsNodes().isEmpty())

            compose.activityRule.scenario.recreate()
            compose.waitUntil(timeoutMillis = 10_000L) {
                compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "Review exit restored the already-consumed tap gate",
                compose.onAllNodesWithTag("post_game_review_tap_gate").fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
        } finally {
            compose.mainClock.autoAdvance = true
            setGamePreferences(originalPreferences)
        }
    }

    @Test
    fun disabledCelebrationsStillWaitForPostGameTap() {
        dismissRulesGuideIfShown()
        waitForText("Quick Play")
        val originalPreferences = currentGamePreferences()
        try {
            setGamePreferences(originalPreferences.copy(celebrationEffectsEnabled = false))
            startWhiteCustomGame()

            compose.onNodeWithTag("resign_button").performClick()
            waitForText("Resign this game?")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Resign game").performClick()
            compose.mainClock.advanceTimeBy(100L)
            compose.waitForIdle()
            assertTrue(
                "Disabled celebration effects still created a completion overlay",
                compose.onAllNodesWithTag(
                    "completion_effect_overlay",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("post_game_review_tap_gate").fetchSemanticsNode()
            assertTrue(
                "Disabled effects bypassed the required post-game acknowledgement",
                compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty(),
            )
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("post_game_review_tap_gate").performClick()
            waitForText("Game review")
        } finally {
            compose.mainClock.autoAdvance = true
            setGamePreferences(originalPreferences)
        }
    }

    @Test
    fun privacyInformationIsAvailableFromHome() {
        dismissRulesGuideIfShown()
        waitForText("Privacy")

        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        waitForText("Drawless Chess works entirely offline", substring = true)
        waitForText("support@drawlesschess.com", substring = true)
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
        compose.onNodeWithTag("resign_button").performClick()
        waitForText("Resign this game?")
        compose.onNodeWithText("Resign game").performClick()
        waitForPostGameReviewTapGate()
        assertTrue(compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty())
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

    private fun waitForPostGameReviewTapGate() {
        compose.waitUntil(timeoutMillis = 15_000L) {
            compose.onAllNodesWithTag("post_game_review_tap_gate").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Review opened before the post-game acknowledgement",
            compose.onAllNodesWithText("Game review").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun openPostGameReview() {
        waitForPostGameReviewTapGate()
        compose.onNodeWithTag("post_game_review_tap_gate").performClick()
        waitForText("Game review")
    }

    private fun currentGamePreferences(): GamePreferences =
        ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java].gamePreferences

    private fun setGamePreferences(preferences: GamePreferences) {
        compose.activity.runOnUiThread {
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
                .updateGamePreferences(preferences)
        }
        compose.waitUntil(timeoutMillis = 5_000L) {
            currentGamePreferences() == preferences &&
                GamePreferenceStore(compose.activity).load() == preferences
        }
    }

    private fun startWhiteCustomGame() {
        compose.onNodeWithText("Custom game").performClick()
        waitForText("Start game")
        compose.onNodeWithTag("play_as_white").performClick().assertIsSelected()
        // These tests exercise native session reuse and saved-game lifecycle behavior. Keep
        // their opponent deterministic; Vesper's async rating lifecycle has dedicated coverage.
        compose.onNodeWithTag("opponent_option_casual").performClick().assertIsSelected()
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

    private fun waitForStatus(resourceId: Int, timeoutMillis: Long = 10_000L) {
        val expected = compose.activity.getString(resourceId)
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                compose.onNodeWithTag("game_status").assertTextEquals(expected)
            }.isSuccess
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
