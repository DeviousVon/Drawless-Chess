package com.drawlesschess.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.coordinator.CheckpointSink
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorClock
import com.drawlesschess.core.coordinator.CoordinatorIdSource
import com.drawlesschess.core.coordinator.CoordinatorTimeSource
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.GameCoordinator
import com.drawlesschess.core.coordinator.MoveClockSnapshot
import com.drawlesschess.core.coordinator.TimeReading
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCheckpointStoreInstrumentedTest {
    @Test
    fun codecRoundTripPreservesTheExactCoordinatorCheckpoint() {
        val checkpoint = checkpoint(gameId = "codec-game", revision = 7)
        val entity = CoordinatorCheckpointCodec.encode(checkpoint, updatedAtEpochMillis = 99_000L)

        assertEquals(checkpoint, CoordinatorCheckpointCodec.decode(entity))
        val defaults = defaultCheckpoint("default-codec-game")
        assertEquals(
            defaults,
            CoordinatorCheckpointCodec.decode(
                CoordinatorCheckpointCodec.encode(defaults, updatedAtEpochMillis = 99_500L),
            ),
        )
        val completed = checkpoint.copy(
            revision = 8,
            outcome = GameOutcome(
                winner = Side.BLACK,
                reason = EndReason.RESIGNATION,
                explanation = "WHITE resigns",
            ),
            clock = checkpoint.clock.stop(TimeReading(41_000L, 81_000L)),
        )
        assertEquals(
            completed,
            CoordinatorCheckpointCodec.decode(
                CoordinatorCheckpointCodec.encode(completed, updatedAtEpochMillis = 100_000L),
            ),
        )
        assertTrue(
            runCatching {
                CoordinatorCheckpointCodec.decode(entity.copy(checkpointFormat = 2))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun singleWriterRejectsOlderAndStaleGameWritesAndHidesCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val clock = AtomicLong(1_000L)
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            epochMillis = clock::incrementAndGet,
        )
        try {
            val first = checkpoint(gameId = "first-game", revision = 2)
            val firstSink = store.activateNewGame()
            firstSink.persist(first)
            firstSink.persist(first.copy(revision = 1))
            firstSink.persist(first.copy(currentFen = "must-not-replace", revision = 2))
            assertEquals(first, load(store).getOrThrow())

            val second = checkpoint(gameId = "second-game", revision = 0)
            val secondSink = store.activateNewGame()
            secondSink.persist(second)
            firstSink.persist(first.copy(revision = 3))
            assertEquals(second, load(store).getOrThrow())

            val completed = second.copy(
                revision = 1,
                outcome = GameOutcome(
                    winner = Side.BLACK,
                    reason = EndReason.RESIGNATION,
                    explanation = "WHITE resigns",
                ),
                clock = second.clock.stop(TimeReading(50_000L, 90_000L)),
            )
            secondSink.persist(completed)
            assertNull(load(store).getOrThrow())
        } finally {
            store.closeForTest()
        }
    }

    @Test
    fun fileBackedDatabaseReopensAndRestoresThroughTheCoordinatorAuthority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(REOPEN_DATABASE)
        val checkpoint = checkpoint(gameId = "restore-game", revision = 4)
        val firstDatabase = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            REOPEN_DATABASE,
        ).build()
        val firstStore = RoomCheckpointStore(
            database = firstDatabase,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            firstStore.activateNewGame().persist(checkpoint)
            assertEquals(checkpoint, load(firstStore).getOrThrow())
        } finally {
            firstStore.closeForTest()
        }

        val secondDatabase = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            REOPEN_DATABASE,
        ).build()
        val secondStore = RoomCheckpointStore(
            database = secondDatabase,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            val decoded = load(secondStore).getOrThrow()!!
            val engine = NoOpEngine()
            val coordinator = GameCoordinator.restore(
                checkpoint = decoded,
                engine = engine,
                checkpointSink = CheckpointSink {},
                timeSource = CoordinatorTimeSource { TimeReading(40_000L, 80_000L) },
                idSource = CoordinatorIdSource { "restore-request" },
            )

            coordinator.start()

            assertEquals(checkpoint, decoded)
            assertEquals(checkpoint.config.gameId, coordinator.snapshot().session.gameId)
            assertEquals(checkpoint.currentFen, coordinator.snapshot().currentFen)
            assertEquals(1, engine.analysisCalls.get())
        } finally {
            secondStore.closeForTest()
            context.deleteDatabase(REOPEN_DATABASE)
        }
    }

    private fun load(store: RoomCheckpointStore): Result<CoordinatorCheckpoint?> {
        val result = AtomicReference<Result<CoordinatorCheckpoint?>>()
        val completed = CountDownLatch(1)
        store.loadResumable {
            result.set(it)
            completed.countDown()
        }
        assertTrue("Room load timed out", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun checkpoint(gameId: String, revision: Long): CoordinatorCheckpoint {
        val rules = RulesContractV1.escape(
            deadPosition = DeadPositionPolicy.FINAL_CAPTURE_VICTORY,
            fiftyMove = FiftyMovePolicy.FORCED_MOVE_EXCEPTION,
        )
        val config = GameConfig(
            gameId = gameId,
            initialFen = ChessPosition.START_FEN,
            rules = rules,
            mode = GameMode.CASUAL,
            timeControl = TimeControl.Clock(300_000L, 2_000L),
            humanSide = Side.WHITE,
            engineStrength = EngineStrength.SkillLevel(7),
            engineLimits = EngineLimits(moveTimeMillis = 750L, multiPv = 2),
        )
        val move = UciMove("e2e4")
        val currentFen = ChessRules.apply(ChessPosition.fromFen(ChessPosition.START_FEN), move).fen()
        return CoordinatorCheckpoint(
            revision = revision,
            config = config,
            moves = listOf(move),
            currentFen = currentFen,
            outcome = null,
            clock = CoordinatorClock(
                whiteRemainingMillis = 301_000L,
                blackRemainingMillis = 300_000L,
                runningSide = Side.BLACK,
                startedAtMonotonicMillis = 40_000L,
                startedAtEpochMillis = 80_000L,
            ),
            moveClocks = listOf(MoveClockSnapshot(1, 301_000L, 300_000L)),
            assistance = AssistanceCounts(hints = 1, undos = 2, pauses = 3),
        )
    }

    private fun defaultCheckpoint(gameId: String): CoordinatorCheckpoint = CoordinatorCheckpoint(
        revision = 0,
        config = GameConfig(
            gameId = gameId,
            initialFen = ChessPosition.START_FEN,
            rules = RulesContractV1.drawless(),
            mode = GameMode.CASUAL,
            timeControl = TimeControl.Untimed,
            humanSide = Side.WHITE,
            engineStrength = EngineStrength.ApproximateElo(900),
            engineLimits = EngineLimits(moveTimeMillis = 350L),
        ),
        moves = emptyList(),
        currentFen = ChessPosition.START_FEN,
        outcome = null,
        clock = CoordinatorClock(null, null, null, null, null),
        moveClocks = emptyList(),
        assistance = AssistanceCounts(),
    )

    private companion object {
        const val REOPEN_DATABASE = "room-checkpoint-reopen-test.db"
    }
}

private class NoOpEngine : ChessEngine {
    val analysisCalls = AtomicInteger()

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        analysisCalls.incrementAndGet()
        return EngineCancellation {}
    }
}
