package com.drawlesschess.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class AppleEngineLifecycleSoakTest {
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
