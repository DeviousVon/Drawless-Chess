package com.drawlesschess.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.Side
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.persistence.DrawlessDatabase
import com.drawlesschess.persistence.RoomCheckpointStore
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostGameLaunchActionsInstrumentedTest {
    @Test
    fun rematchPreservesRuntimeSideWhileQuickPlayDrawsAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val checkpointStore = RoomCheckpointStore(
            database = database,
            localProfileIdSource = { "post-game-launch-actions-profile" },
        )
        val viewModelStore = ViewModelStore()
        val randomSides = ArrayDeque(listOf(true, false))
        var randomCalls = 0
        val viewModel = createViewModel(context, checkpointStore, viewModelStore) {
            randomCalls += 1
            randomSides.removeFirst()
        }
        val originalQuickPlayOpponent = viewModel.quickPlayOpponentLevel
        val expectedOpponent = BotDifficultyCatalog.named("challenger")

        try {
            waitUntil { viewModel.resumeState is ResumeState.Empty }
            onMain {
                viewModel.startNewGame(
                    SetupSelection(
                        startingColor = StartingColor.RANDOM,
                        botLevel = expectedOpponent,
                    ),
                )
            }

            waitUntil { viewModel.runtime != null }
            val firstRuntime = requireNotNull(viewModel.runtime)
            assertEquals(Side.WHITE, firstRuntime.controller.model().board.humanSide)
            assertEquals(expectedOpponent.id, firstRuntime.opponentLevel.id)
            assertEquals(1, randomCalls)

            assertTrue(viewModel.isPostGameReviewPending(firstRuntime.gameId))
            onMain { viewModel.enterPostGameReview(firstRuntime.gameId) }
            assertEquals("A nonterminal game entered Review", AppRoute.GAME, viewModel.route)

            onMain {
                firstRuntime.controller.resign()
            }
            onMain { viewModel.enterPostGameReview("stale-game-id") }
            assertEquals(AppRoute.GAME, viewModel.route)
            onMain { viewModel.enterPostGameReview(firstRuntime.gameId) }
            assertEquals(AppRoute.REVIEW, viewModel.route)
            assertSame(firstRuntime, viewModel.runtime)
            assertTrue(!viewModel.isPostGameReviewPending(firstRuntime.gameId))

            onMain {
                viewModel.rematchGame()
            }
            waitUntil { viewModel.runtime != null && viewModel.runtime !== firstRuntime }
            val rematchRuntime = requireNotNull(viewModel.runtime)
            assertNotSame(firstRuntime, rematchRuntime)
            assertEquals(Side.WHITE, rematchRuntime.controller.model().board.humanSide)
            assertEquals(expectedOpponent.id, rematchRuntime.opponentLevel.id)
            assertEquals("Rematch unexpectedly drew a new side", 1, randomCalls)

            onMain { viewModel.enterPostGameReview(firstRuntime.gameId) }
            assertEquals("Stale callback navigated the replacement game", AppRoute.GAME, viewModel.route)
            assertTrue(viewModel.isPostGameReviewPending(rematchRuntime.gameId))

            onMain {
                rematchRuntime.controller.resign()
            }
            onMain { viewModel.enterPostGameReview(rematchRuntime.gameId) }
            assertEquals(AppRoute.REVIEW, viewModel.route)
            onMain {
                viewModel.postGameQuickPlay()
            }
            waitUntil { viewModel.runtime != null && viewModel.runtime !== rematchRuntime }
            val quickPlayRuntime = requireNotNull(viewModel.runtime)
            assertNotSame(rematchRuntime, quickPlayRuntime)
            assertEquals(Side.BLACK, quickPlayRuntime.controller.model().board.humanSide)
            assertEquals(expectedOpponent.id, quickPlayRuntime.opponentLevel.id)
            assertEquals("Quick Play did not draw a fresh side", 2, randomCalls)
            assertTrue(randomSides.isEmpty())
        } finally {
            onMain {
                QuickPlayPreferenceStore(context).save(originalQuickPlayOpponent)
                viewModelStore.clear()
            }
            checkpointStore.closeForTest()
        }
    }

    private fun createViewModel(
        context: Context,
        checkpointStore: RoomCheckpointStore,
        viewModelStore: ViewModelStore,
        randomBoolean: () -> Boolean,
    ): DrawlessAppViewModel = ViewModelProvider(
        viewModelStore,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DrawlessAppViewModel(context, checkpointStore, randomBoolean) as T
        },
    )[DrawlessAppViewModel::class.java]

    private fun waitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20L)
        }
        assertTrue("Condition was not satisfied within $timeoutMillis ms", condition())
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
}
