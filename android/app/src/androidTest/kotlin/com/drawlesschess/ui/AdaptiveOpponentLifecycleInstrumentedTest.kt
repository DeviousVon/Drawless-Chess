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
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.OfflineElo
import com.drawlesschess.core.engine.OfflineRating
import com.drawlesschess.core.engine.RatedResult
import com.drawlesschess.persistence.DrawlessDatabase
import com.drawlesschess.persistence.RoomCheckpointStore
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveOpponentLifecycleInstrumentedTest {
    @Test
    fun completedAdaptiveGameRematchesAtTheNewDurableRating() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val checkpointStore = RoomCheckpointStore(
            database = database,
            localProfileIdSource = { "adaptive-lifecycle-profile" },
        )
        val viewModelStore = ViewModelStore()
        val viewModel = createViewModel(context, checkpointStore, viewModelStore)
        val originalPreferences = viewModel.gamePreferences
        val originalQuickOpponent = viewModel.quickPlayOpponentLevel

        try {
            waitUntil {
                viewModel.resumeState is ResumeState.Empty &&
                    viewModel.playerStatsState is PlayerStatsState.Ready
            }
            onMain {
                viewModel.updateGamePreferences(
                    originalPreferences.copy(threatIndicationEnabled = false),
                )
                viewModel.startNewGame(
                    SetupSelection(
                        startingColor = StartingColor.WHITE,
                        botLevel = BotDifficultyCatalog.adaptiveLevel(),
                    ),
                )
            }

            waitUntil { viewModel.runtime != null }
            val firstRuntime = requireNotNull(viewModel.runtime)
            assertEquals(BotDifficultyCatalog.ADAPTIVE_LEVEL_ID, firstRuntime.opponentLevel.id)
            assertEquals(BotDifficultyCatalog.ADAPTIVE_STARTING_ELO, firstRuntime.opponentLevel.approximateElo)

            onMain {
                firstRuntime.controller.resign()
                viewModel.completedGameRecorded()
            }
            waitUntil {
                (viewModel.playerStatsState as? PlayerStatsState.Ready)
                    ?.statistics
                    ?.adaptiveGamesPlayed == 1
            }
            val statistics = (viewModel.playerStatsState as PlayerStatsState.Ready).statistics
            val expected = OfflineElo.update(
                current = OfflineRating(BotDifficultyCatalog.ADAPTIVE_STARTING_ELO),
                opponentElo = BotDifficultyCatalog.ADAPTIVE_STARTING_ELO,
                result = RatedResult.LOSS,
            )
            assertEquals(expected.rating, statistics.adaptiveRating)

            onMain(viewModel::rematchGame)
            waitUntil { viewModel.runtime != null && viewModel.runtime !== firstRuntime }
            val rematchRuntime = requireNotNull(viewModel.runtime)
            assertNotSame(firstRuntime, rematchRuntime)
            assertEquals(BotDifficultyCatalog.ADAPTIVE_LEVEL_ID, rematchRuntime.opponentLevel.id)
            assertEquals(expected.rating, rematchRuntime.opponentLevel.approximateElo)
        } finally {
            onMain {
                viewModel.updateGamePreferences(originalPreferences)
                QuickPlayPreferenceStore(context).save(originalQuickOpponent)
                viewModelStore.clear()
            }
            checkpointStore.closeForTest()
        }
    }

    @Test
    fun backingOutOrClearingTheViewModelCancelsAnUnresolvedAdaptiveLaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val callbacks = QueuedExecutor()
        val checkpointStore = RoomCheckpointStore(
            database = database,
            callbackExecutor = callbacks,
            localProfileIdSource = { "adaptive-cancellation-profile" },
        )
        val viewModelStore = ViewModelStore()
        val viewModel = createViewModel(context, checkpointStore, viewModelStore)
        val originalQuickOpponent = viewModel.quickPlayOpponentLevel

        try {
            waitUntil { callbacks.pendingCount() >= 2 }
            callbacks.drainOnMain()
            assertTrue(viewModel.resumeState is ResumeState.Empty)
            assertTrue(viewModel.playerStatsState is PlayerStatsState.Ready)

            onMain {
                viewModel.showNewGameSetup()
                viewModel.startNewGame(
                    SetupSelection(botLevel = BotDifficultyCatalog.adaptiveLevel()),
                )
            }
            waitUntil { callbacks.pendingCount() >= 1 }
            onMain(viewModel::leaveSetup)
            callbacks.drainOnMain()
            assertEquals(AppRoute.HOME, viewModel.route)
            assertNull(viewModel.runtime)

            onMain {
                viewModel.showNewGameSetup()
                viewModel.startNewGame(
                    SetupSelection(botLevel = BotDifficultyCatalog.adaptiveLevel()),
                )
            }
            waitUntil { callbacks.pendingCount() >= 1 }
            onMain(viewModelStore::clear)
            callbacks.drainOnMain()
            assertNull(viewModel.runtime)
        } finally {
            onMain {
                QuickPlayPreferenceStore(context).save(originalQuickOpponent)
                viewModelStore.clear()
            }
            checkpointStore.closeForTest()
        }
    }

    private fun createViewModel(
        context: Context,
        checkpointStore: RoomCheckpointStore,
        viewModelStore: ViewModelStore,
    ): DrawlessAppViewModel = ViewModelProvider(
        viewModelStore,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DrawlessAppViewModel(context, checkpointStore, randomBoolean = { true }) as T
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

    private class QueuedExecutor : Executor {
        private val lock = Any()
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(lock) { tasks.addLast(command) }
        }

        fun pendingCount(): Int = synchronized(lock) { tasks.size }

        fun drainOnMain() {
            while (true) {
                val task = synchronized(lock) {
                    if (tasks.isEmpty()) null else tasks.removeFirst()
                } ?: return
                InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
            }
        }
    }
}
