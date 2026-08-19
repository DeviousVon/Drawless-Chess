package com.drawlesschess.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class AppleEngineLifecycleSoakTest {
    @Test
    fun foregroundEvidenceSurvivesInterruptionsAndFinishesPostGameReview() {
        val game = SharedGameRuntime(botLevelId = "learner")
        try {
            game.setGameForeground(true)
            val prefetched = awaitCheckpoint(game) { it.reviewPrefetchRoots.isNotEmpty() }
            assertTrue(prefetched.reviewPrefetchRoots.any { it.key.ply == 1 })

            game.tap(52) // e2
            game.tap(36) // e4; cancels/drains any speculative continuation before bot work
            val completedTurn = awaitView(game) { it.plyCount == 2 || it.engineError != null }
            assertNull(completedTurn.engineError)
            assertEquals("HUMAN_TURN", completedTurn.phase)

            game.requestHint()
            assertEquals("PAUSED", game.pause().phase)
            assertEquals("HUMAN_TURN", game.resume().phase)

            val terminal = game.resign()
            assertEquals("COMPLETED", terminal.phase)
            val reviewStarted = game.startReview()
            assertTrue(reviewStarted.reviewInProgress || reviewStarted.reviewSummary != null)
            val reviewed = awaitView(game, timeoutMillis = 60_000) {
                !it.reviewInProgress && (it.reviewSummary != null || it.reviewError != null)
            }
            assertNull(reviewed.reviewError)
            assertTrue(reviewed.reviewTotal > 0)
            assertEquals(reviewed.reviewTotal, reviewed.reviewProgress)
            assertEquals(reviewed.plyCount, reviewed.reviewMoves.size)
            assertEquals(reviewed.reviewTotal, reviewed.reviewMoves.count { it.playerDecision })
            assertTrue(reviewed.reviewMoves.all { it.cells.size == 64 && it.playedSan.isNotBlank() })
        } finally {
            game.close()
        }
    }

    @Test
    fun repeatedStartupCancellationCloseAndRestartLeavesOneHealthySession() {
        repeat(12) { cycle ->
            val game = SharedGameRuntime(botLevelId = if (cycle.isEven()) "learner" else "casual")
            try {
                if (cycle.isEven()) {
                    game.requestHint()
                } else {
                    game.tap(52)
                    game.tap(36)
                }
            } finally {
                game.close()
            }
        }

        val finalGame = SharedGameRuntime()
        try {
            finalGame.tap(52)
            finalGame.tap(36)
            val completedTurn = awaitView(finalGame) { it.plyCount == 2 || it.engineError != null }
            assertNull(completedTurn.engineError)
            assertEquals(2, completedTurn.plyCount)
            assertEquals("HUMAN_TURN", completedTurn.phase)
        } finally {
            finalGame.close()
        }
    }

    private fun Int.isEven(): Boolean = this % 2 == 0

    private fun awaitCheckpoint(
        game: SharedGameRuntime,
        timeoutMillis: Long = 20_000,
        predicate: (com.drawlesschess.core.coordinator.CoordinatorCheckpoint) -> Boolean,
    ): com.drawlesschess.core.coordinator.CoordinatorCheckpoint {
        val started = TimeSource.Monotonic.markNow()
        var checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())
        while (!predicate(checkpoint) && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            checkpoint = SharedCheckpointCodec.decode(game.checkpointJson())
        }
        assertTrue(predicate(checkpoint), "Timed out waiting for foreground review evidence")
        return checkpoint
    }

    private fun awaitView(
        game: SharedGameRuntime,
        timeoutMillis: Long = 20_000,
        predicate: (SharedGameView) -> Boolean,
    ): SharedGameView {
        val started = TimeSource.Monotonic.markNow()
        var view = game.view()
        while (!predicate(view) && started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            view = game.view()
        }
        assertTrue(predicate(view), "Timed out waiting for final engine session: $view")
        return view
    }
}
