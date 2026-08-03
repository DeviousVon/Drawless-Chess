package com.drawlesschess.ui

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.persistence.DrawlessDatabase
import com.drawlesschess.persistence.RoomCheckpointStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewPrefetchResumeInstrumentedTest {
    @Test
    fun nativePrefetchSurvivesRoomResumeWithoutAnotherSearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { action -> action.run() },
        )
        var firstRuntime: GameRuntime? = null
        var resumedRuntime: GameRuntime? = null
        try {
            val runtime = GameRuntime(
                selection = SetupSelection(startingColor = StartingColor.WHITE),
                applicationContext = context,
                checkpointSink = store.activateNewGame(),
            )
            firstRuntime = runtime
            runtime.setGameForeground(true)
            waitUntil(timeoutMillis = 30_000L) {
                runtime.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1]
                    ?.isNotEmpty() == true
            }
            val candidates = requireNotNull(
                runtime.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1],
            )
            assertEquals(1, runtime.reviewPrefetchEngineSubmissionCount())
            runtime.setGameForeground(false)
            runtime.close()
            firstRuntime = null

            val checkpoint = requireNotNull(load(store))
            assertEquals(1, checkpoint.reviewPrefetchRoots.size)
            val resumed = GameRuntime(
                checkpoint = checkpoint,
                applicationContext = context,
                checkpointSink = store.activateResume(),
            )
            resumedRuntime = resumed
            assertEquals(
                candidates,
                resumed.reviewPrefetchDiagnostics().rootCandidateMovesByPly[1],
            )

            resumed.setGameForeground(true)
            assertEquals(
                "Restoring the exact durable root submitted a replacement native search",
                0,
                resumed.reviewPrefetchEngineSubmissionCount(),
            )
        } finally {
            runCatching { firstRuntime?.close() }
            runCatching { resumedRuntime?.close() }
            store.closeForTest()
        }
    }

    private fun load(store: RoomCheckpointStore): CoordinatorCheckpoint? {
        val value = AtomicReference<Result<CoordinatorCheckpoint?>>()
        val completed = CountDownLatch(1)
        store.loadResumable { result ->
            value.set(result)
            completed.countDown()
        }
        assertTrue("Room load timed out", completed.await(10, TimeUnit.SECONDS))
        return requireNotNull(value.get()).getOrThrow()
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "Timed out waiting for native review prefetch"
            }
            Thread.sleep(25L)
        }
    }
}
