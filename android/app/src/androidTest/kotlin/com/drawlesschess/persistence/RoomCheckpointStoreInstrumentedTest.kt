package com.drawlesschess.persistence

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import com.drawlesschess.core.engine.BotDifficultyCatalog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
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

        val decoded = CoordinatorCheckpointCodec.decode(entity)
        assertEquals(checkpoint, decoded)
        assertTrue(decoded.assistance.threatIndication)
        val legacyPayload = JSONObject(entity.payloadJson).apply {
            getJSONObject("assistance").remove("threatIndication")
        }
        val legacyDecoded = CoordinatorCheckpointCodec.decode(
            entity.copy(payloadJson = legacyPayload.toString()),
        )
        assertEquals(false, legacyDecoded.assistance.threatIndication)
        val preOpponentIdPayload = JSONObject(entity.payloadJson).apply {
            getJSONObject("config").remove("opponentLevelId")
        }
        assertEquals(
            checkpoint,
            CoordinatorCheckpointCodec.decode(entity.copy(payloadJson = preOpponentIdPayload.toString())),
        )
        val ambiguous1500 = defaultCheckpoint("legacy-club-codec").copy(
            config = defaultCheckpoint("legacy-club-codec").config.copy(
                engineStrength = EngineStrength.ApproximateElo(1_500),
                opponentLevelId = null,
            ),
        )
        val explicitCustom1500 = CoordinatorCheckpointCodec.encode(ambiguous1500, 99_700L)
        assertNull(CoordinatorCheckpointCodec.decode(explicitCustom1500).config.opponentLevelId)
        val missingId1500Payload = JSONObject(explicitCustom1500.payloadJson).apply {
            getJSONObject("config").remove("opponentLevelId")
        }
        val inferredLegacy1500 = CoordinatorCheckpointCodec.decode(
            explicitCustom1500.copy(payloadJson = missingId1500Payload.toString()),
        )
        assertEquals("club", inferredLegacy1500.config.opponentLevelId)
        assertEquals(EngineStrength.ApproximateElo(1_500), inferredLegacy1500.config.engineStrength)
        listOf("true", 1, JSONObject.NULL).forEach { malformedValue ->
            val malformedPayload = JSONObject(entity.payloadJson).apply {
                getJSONObject("assistance").put("threatIndication", malformedValue)
            }
            assertTrue(
                "Malformed threatIndication=$malformedValue must be rejected",
                runCatching {
                    CoordinatorCheckpointCodec.decode(
                        entity.copy(payloadJson = malformedPayload.toString()),
                    )
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
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
    fun oldClubAndNewExpertAt1500KeepDistinctStableIdentities() {
        val legacyClub = completedCheckpoint(
            gameId = "legacy-club-1500",
            playerWon = true,
            opponentElo = 1_500,
            opponentLevelId = "club",
        )
        val currentExpert = completedCheckpoint(
            gameId = "current-expert-1500",
            playerWon = true,
            opponentElo = 1_500,
            opponentLevelId = "expert",
        )

        assertEquals(
            "bot:club",
            CompletedGameRecordFactory.from(legacyClub, "profile", 1L).opponentStableId,
        )
        assertEquals(
            "bot:expert",
            CompletedGameRecordFactory.from(currentExpert, "profile", 2L).opponentStableId,
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

    @Test
    fun completedGamesAreIdempotentAndStatisticsAreDerivedFromHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val clock = AtomicLong(10_000L)
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            // Deliberately runs backward: streak order must use the transactional sequence.
            epochMillis = clock::getAndDecrement,
            localProfileIdSource = { "stable-local-profile" },
        )
        try {
            val learnerElo = BotDifficultyCatalog.named("learner").approximateElo
            val cleanWin = completedCheckpoint(
                gameId = "history-1",
                playerWon = true,
                opponentElo = learnerElo,
            )
            val assistedWin = completedCheckpoint(
                gameId = "history-2",
                playerWon = true,
                opponentElo = learnerElo,
                assistance = AssistanceCounts(
                    hints = 1,
                    undos = 1,
                    pauses = 1,
                    threatIndication = true,
                ),
                timeControl = TimeControl.Clock(300_000L, 0L),
            )
            val loss = completedCheckpoint(
                gameId = "history-3",
                playerWon = false,
                opponentElo = learnerElo,
            )

            store.activateNewGame().also { sink ->
                sink.persist(cleanWin)
                sink.persist(cleanWin) // Same game ID must never double-count.
            }
            store.activateNewGame().persist(assistedWin)
            store.activateNewGame().persist(loss)

            val statistics = loadStats(store).getOrThrow()
            assertEquals("stable-local-profile", statistics.localProfileId)
            assertEquals(3, statistics.completedGames)
            assertEquals("history-3", statistics.latestGameId)
            assertEquals(2, statistics.wins)
            assertEquals(1, statistics.losses)
            assertEquals(200.0 / 3.0, statistics.winPercentage!!, 0.0001)
            assertEquals((100.0 + 70.0) / 3.0, statistics.averageScore!!, 0.0001)
            assertEquals(0, statistics.currentWinStreak)
            assertEquals(2, statistics.bestWinStreak)
            assertEquals(1, statistics.unassistedWins)
            assertEquals(1, statistics.opponents.size)
            assertEquals("bot:learner", statistics.opponents.single().opponentStableId)
            assertEquals(learnerElo, statistics.opponents.single().opponentExactElo)

            val profile = database.activeGameCheckpointDao().loadLocalProfile()!!
            assertEquals("stable-local-profile", profile.localProfileId)
            assertEquals("Player", profile.displayName)
            assertNull(profile.avatarId)
            val allPersisted = database.activeGameCheckpointDao()
                .loadCompletedGames(profile.localProfileId)
            assertEquals(listOf(1L, 2L, 3L), allPersisted.map { it.completionSequence })
            assertTrue(
                "The wall clock should be reversed for this regression",
                allPersisted.zipWithNext().all { (left, right) ->
                    left.completedAtEpochMillis > right.completedAtEpochMillis
                },
            )
            val persisted = allPersisted
                .single { it.gameId == "history-2" }
            assertEquals(AUTHORITY_LOCAL_ONLY, persisted.authority)
            assertEquals(INTEGRITY_NOT_VERIFIED, persisted.integrityState)
            assertEquals(INTEGRITY_ALGORITHM_NONE, persisted.integrityAlgorithm)
            assertNull(persisted.integritySignature)
            assertEquals(learnerElo, persisted.opponentExactElo)
            assertEquals(ChessPosition.START_FEN, persisted.initialFen)
            assertEquals("[]", persisted.movesJson)
            assertEquals(350L, persisted.engineMoveTimeMillis)
            assertEquals(1, persisted.engineMultiPv)
            assertEquals("CLOCK", persisted.timeControlKind)
            assertEquals(300_000L, persisted.timeInitialMillis)
            assertEquals(1, persisted.scoreSystemVersion)
            assertEquals(100, persisted.scoreBasePoints)
            assertEquals(10, persisted.scoreHintPenalty)
            assertEquals(10, persisted.scoreUndoPenalty)
            assertEquals(5, persisted.scorePausePenalty)
            assertEquals(5, persisted.scoreThreatPenalty)
            assertEquals(70, persisted.scoreFinalPoints)
            assertNull(persisted.ratingNamespace)
            assertNull(persisted.playerRatingBefore)
        } finally {
            store.closeForTest()
        }
    }

    @Test
    fun acceptedTerminalWriteSurvivesAnImmediateRematch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val executor = Executors.newSingleThreadExecutor()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            assertTrue(releaseBlocker.await(5, TimeUnit.SECONDS))
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = executor,
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            localProfileIdSource = { "rapid-rematch-profile" },
        )
        try {
            store.activateNewGame().persist(
                completedCheckpoint(
                    "rapid-terminal",
                    playerWon = true,
                    opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
                ),
            )
            val next = defaultCheckpoint("rapid-next")
            store.activateNewGame().persist(next)
            releaseBlocker.countDown()

            assertEquals(1, loadStats(store).getOrThrow().completedGames)
            assertEquals(next, load(store).getOrThrow())
        } finally {
            releaseBlocker.countDown()
            store.closeForTest()
        }
    }

    @Test
    fun generationAcceptanceAndSubmissionAreAtomicWithRematchActivation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val acceptedOldWrite = CountDownLatch(1)
        val releaseOldWrite = CountDownLatch(1)
        val rematchActivated = CountDownLatch(1)
        val pauseFirstSubmission = AtomicBoolean(true)
        val backgroundFailure = AtomicReference<Throwable?>()
        val nextSink = AtomicReference<CheckpointSink>()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            beforeAcceptedCheckpointEnqueue = {
                if (pauseFirstSubmission.compareAndSet(true, false)) {
                    acceptedOldWrite.countDown()
                    check(releaseOldWrite.await(5, TimeUnit.SECONDS))
                }
            },
        )
        try {
            val oldSink = store.activateNewGame()
            val oldThread = Thread {
                runCatching {
                    oldSink.persist(
                        completedCheckpoint(
                            "interleaved-terminal",
                            playerWon = true,
                            opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
                        ),
                    )
                }.onFailure(backgroundFailure::set)
            }
            oldThread.start()
            assertTrue(acceptedOldWrite.await(5, TimeUnit.SECONDS))

            val activationThread = Thread {
                runCatching { store.activateNewGame() }
                    .onSuccess {
                        nextSink.set(it)
                        rematchActivated.countDown()
                    }
                    .onFailure(backgroundFailure::set)
            }
            activationThread.start()
            assertTrue(
                "Rematch activation overtook an accepted old write",
                !rematchActivated.await(200, TimeUnit.MILLISECONDS),
            )

            releaseOldWrite.countDown()
            oldThread.join(5_000L)
            activationThread.join(5_000L)
            assertNull(backgroundFailure.get())
            assertTrue(rematchActivated.await(1, TimeUnit.SECONDS))

            val next = defaultCheckpoint("interleaved-next")
            nextSink.get().persist(next)
            assertEquals(1, loadStats(store).getOrThrow().completedGames)
            assertEquals(next, load(store).getOrThrow())
        } finally {
            releaseOldWrite.countDown()
            store.closeForTest()
        }
    }

    @Test
    fun confirmedForfeitIsDurableBeforeCallbackAndRejectsStaleRuntimeWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            epochMillis = { 90_000L },
            monotonicMillis = { 50_000L },
            localProfileIdSource = { "forfeit-profile" },
        )
        try {
            val live = checkpoint(gameId = "forfeit-live", revision = 4)
            val abandonedSink = store.activateNewGame()
            abandonedSink.persist(live)
            assertEquals(live, load(store).getOrThrow())

            val callbackFailure = AtomicReference<Throwable?>()
            val callbackCompleted = CountDownLatch(1)
            store.forfeitResumable(live.config.gameId) { result ->
                try {
                    assertTrue(result.getOrThrow())
                    val durableCheckpoint = database.activeGameCheckpointDao().loadCurrent()!!
                    assertTrue("Callback ran before the terminal checkpoint committed", durableCheckpoint.completed)
                    val durableHistory = database.activeGameCheckpointDao()
                        .loadCompletedGame(live.config.gameId)
                    assertTrue("Callback ran before the loss entered history", durableHistory != null)
                    assertEquals("LOSS", durableHistory!!.result)
                    assertEquals("RESIGNATION", durableHistory.endReason)
                    assertEquals("WHITE forfeits the game", durableHistory.outcomeExplanation)
                } catch (error: Throwable) {
                    callbackFailure.set(error)
                } finally {
                    callbackCompleted.countDown()
                }
            }
            assertTrue("Forfeit callback timed out", callbackCompleted.await(5, TimeUnit.SECONDS))
            assertNull(callbackFailure.get())

            // A runtime callback already holding the abandoned sink cannot resurrect its game,
            // even with a revision larger than the terminal forfeit.
            abandonedSink.persist(live.copy(revision = 100L, currentFen = "stale-resurrection"))
            assertNull(load(store).getOrThrow())
            val afterForfeit = loadStats(store).getOrThrow()
            assertEquals(1, afterForfeit.completedGames)
            assertEquals(0, afterForfeit.wins)
            assertEquals(1, afterForfeit.losses)
            assertEquals("forfeit-live", afterForfeit.latestGameId)

            // An exact retry is accepted only because the same terminal loss and history row
            // are already durable. A wholly missing expected game must never fail open.
            assertTrue(forfeit(store, live.config.gameId).getOrThrow())

            val replacement = defaultCheckpoint("forfeit-replacement")
            store.activateNewGame().persist(replacement)
            assertEquals(replacement, load(store).getOrThrow())
            assertEquals(1, loadStats(store).getOrThrow().losses)
            assertTrue(forfeit(store, live.config.gameId).isFailure)
            store.discard { }
            assertNull(load(store).getOrThrow())
            assertTrue(forfeit(store, "missing-forfeit-game").isFailure)
            store.discard { }
            assertEquals(1, loadStats(store).getOrThrow().losses)
        } finally {
            store.closeForTest()
        }
    }

    @Test
    fun confirmedForfeitSurvivesDatabaseReopenAsOneCompletedLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(FORFEIT_REOPEN_DATABASE)
        val firstDatabase = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            FORFEIT_REOPEN_DATABASE,
        ).build()
        val firstStore = RoomCheckpointStore(
            database = firstDatabase,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            localProfileIdSource = { "durable-forfeit-profile" },
        )
        try {
            val live = defaultCheckpoint("durable-forfeit")
            firstStore.activateNewGame().persist(live)
            assertEquals(live, load(firstStore).getOrThrow())
            assertTrue(forfeit(firstStore, live.config.gameId).getOrThrow())
        } finally {
            firstStore.closeForTest()
        }

        val secondDatabase = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            FORFEIT_REOPEN_DATABASE,
        ).build()
        val secondStore = RoomCheckpointStore(
            database = secondDatabase,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            assertNull(load(secondStore).getOrThrow())
            val statistics = loadStats(secondStore).getOrThrow()
            assertEquals(1, statistics.completedGames)
            assertEquals(1, statistics.losses)
            assertEquals("durable-forfeit", statistics.latestGameId)
        } finally {
            secondStore.closeForTest()
            context.deleteDatabase(FORFEIT_REOPEN_DATABASE)
        }
    }

    @Test
    fun staleTerminalCannotCreateHistoryAndDivergentDuplicateFails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            val live = defaultCheckpoint("stale-terminal").copy(revision = 2)
            val liveSink = store.activateNewGame()
            liveSink.persist(live)
            liveSink.persist(
                completedCheckpoint(
                    "stale-terminal",
                    playerWon = true,
                    opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
                ).copy(revision = 1),
            )
            assertEquals(0, loadStats(store).getOrThrow().completedGames)
            assertEquals(live, load(store).getOrThrow())

            val original = completedCheckpoint(
                "immutable-game-id",
                playerWon = true,
                opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
            )
            store.activateNewGame().persist(original)
            assertEquals(1, loadStats(store).getOrThrow().wins)

            store.activateNewGame().persist(
                original.copy(
                    outcome = GameOutcome(
                        winner = original.config.humanSide.opposite(),
                        reason = EndReason.RESIGNATION,
                        explanation = "WHITE resigns",
                    ),
                ),
            )
            val conflict = loadStats(store).exceptionOrNull()
            assertTrue(
                "A divergent duplicate game ID must surface an integrity failure",
                generateSequence(conflict) { it.cause }
                    .any { it is CompletedGameConflictException },
            )
            val profile = database.activeGameCheckpointDao().loadLocalProfile()!!
            val preserved = database.activeGameCheckpointDao()
                .loadCompletedGames(profile.localProfileId)
                .single { it.gameId == "immutable-game-id" }
            assertEquals("WIN", preserved.result)

            val activeBeforeFollowUp = database.activeGameCheckpointDao().loadCurrent()
            store.activateNewGame().persist(defaultCheckpoint("after-integrity-conflict"))
            val stillFatal = loadStats(store).exceptionOrNull()
            assertTrue(
                "A later ordinary write must not clear a divergent-history integrity failure",
                generateSequence(stillFatal) { it.cause }
                    .any { it is CompletedGameConflictException },
            )
            assertEquals(activeBeforeFollowUp, database.activeGameCheckpointDao().loadCurrent())
        } finally {
            store.closeForTest()
        }
    }

    @Test
    fun failedTerminalCommitIsRetriedBeforeRematchCanOverwriteIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, DrawlessDatabase::class.java).build()
        val failFirstTerminal = AtomicBoolean(true)
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
            beforeCheckpointTransaction = { _, completedGame ->
                if (completedGame != null && failFirstTerminal.compareAndSet(true, false)) {
                    throw IOException("Injected one-shot terminal transaction failure")
                }
            },
        )
        try {
            store.activateNewGame().persist(
                completedCheckpoint(
                    "retry-terminal",
                    playerWon = true,
                    opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
                ),
            )
            val next = defaultCheckpoint("retry-next-game")
            store.activateNewGame().persist(next)

            val statistics = loadStats(store).getOrThrow()
            assertEquals(1, statistics.completedGames)
            assertEquals("retry-terminal", statistics.latestGameId)
            assertEquals(next, load(store).getOrThrow())
            assertTrue(!failFirstTerminal.get())
        } finally {
            store.closeForTest()
        }
    }

    @Test
    fun migrationPreservesAndBackfillsTheV1TerminalCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MIGRATION_DATABASE)
        val completed = completedCheckpoint(
            gameId = "legacy-completed",
            playerWon = true,
            opponentElo = BotDifficultyCatalog.named("learner").approximateElo,
        )
        val entity = CoordinatorCheckpointCodec.encode(completed, updatedAtEpochMillis = 77_000L)
        createV1Database(context, MIGRATION_DATABASE, entity)

        val database = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            MIGRATION_DATABASE,
        ).addMigrations(MIGRATION_1_2).build()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            val statistics = loadStats(store).getOrThrow()
            assertEquals(1, statistics.completedGames)
            assertEquals(1, statistics.wins)
            assertEquals(100.0, statistics.averageScore!!, 0.0)
            assertNull(load(store).getOrThrow())
        } finally {
            store.closeForTest()
            context.deleteDatabase(MIGRATION_DATABASE)
        }
    }

    @Test
    fun migrationKeepsAV1NonterminalResumableWithoutBackfillingHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MIGRATION_NONTERMINAL_DATABASE)
        val checkpoint = defaultCheckpoint("legacy-in-progress").copy(revision = 4)
        createV1Database(
            context,
            MIGRATION_NONTERMINAL_DATABASE,
            CoordinatorCheckpointCodec.encode(checkpoint, updatedAtEpochMillis = 88_000L),
        )

        val database = Room.databaseBuilder(
            context,
            DrawlessDatabase::class.java,
            MIGRATION_NONTERMINAL_DATABASE,
        ).addMigrations(MIGRATION_1_2).build()
        val store = RoomCheckpointStore(
            database = database,
            ioExecutor = Executors.newSingleThreadExecutor(),
            callbackExecutor = java.util.concurrent.Executor { it.run() },
        )
        try {
            assertEquals(checkpoint, load(store).getOrThrow())
            assertEquals(0, loadStats(store).getOrThrow().completedGames)
        } finally {
            store.closeForTest()
            context.deleteDatabase(MIGRATION_NONTERMINAL_DATABASE)
        }
    }

    private fun createV1Database(
        context: Context,
        databaseName: String,
        entity: ActiveGameCheckpointEntity,
    ) {
        val legacyHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `active_game_checkpoint` (
                                    `slot` INTEGER NOT NULL,
                                    `game_id` TEXT NOT NULL,
                                    `revision` INTEGER NOT NULL,
                                    `checkpoint_format` INTEGER NOT NULL,
                                    `completed` INTEGER NOT NULL,
                                    `updated_at_epoch_millis` INTEGER NOT NULL,
                                    `payload_json` TEXT NOT NULL,
                                    PRIMARY KEY(`slot`)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        legacyHelper.writableDatabase.execSQL(
            """
            INSERT INTO active_game_checkpoint (
                slot, game_id, revision, checkpoint_format, completed,
                updated_at_epoch_millis, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                entity.slot,
                entity.gameId,
                entity.revision,
                entity.checkpointFormat,
                if (entity.completed) 1 else 0,
                entity.updatedAtEpochMillis,
                entity.payloadJson,
            ),
        )
        legacyHelper.close()
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

    private fun loadStats(store: RoomCheckpointStore): Result<PlayerStatistics> {
        val result = AtomicReference<Result<PlayerStatistics>>()
        val completed = CountDownLatch(1)
        store.loadPlayerStats {
            result.set(it)
            completed.countDown()
        }
        assertTrue("Player-statistics load timed out", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun forfeit(store: RoomCheckpointStore, gameId: String): Result<Boolean> {
        val result = AtomicReference<Result<Boolean>>()
        val completed = CountDownLatch(1)
        store.forfeitResumable(gameId) {
            result.set(it)
            completed.countDown()
        }
        assertTrue("Room forfeit timed out", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun completedCheckpoint(
        gameId: String,
        playerWon: Boolean,
        opponentElo: Int,
        assistance: AssistanceCounts = AssistanceCounts(),
        timeControl: TimeControl = TimeControl.Untimed,
        opponentLevelId: String? = BotDifficultyCatalog.namedLevels
            .singleOrNull { it.approximateElo == opponentElo }
            ?.id,
    ): CoordinatorCheckpoint {
        val playerSide = Side.WHITE
        return defaultCheckpoint(gameId).copy(
            revision = 1,
            config = defaultCheckpoint(gameId).config.copy(
                timeControl = timeControl,
                humanSide = playerSide,
                engineStrength = EngineStrength.ApproximateElo(opponentElo),
                opponentLevelId = opponentLevelId,
            ),
            outcome = GameOutcome(
                winner = if (playerWon) playerSide else playerSide.opposite(),
                reason = EndReason.RESIGNATION,
                explanation = if (playerWon) "BLACK resigns" else "WHITE resigns",
            ),
            assistance = assistance,
        )
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
            assistance = AssistanceCounts(
                hints = 1,
                undos = 2,
                pauses = 3,
                threatIndication = true,
            ),
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
            engineStrength = EngineStrength.ApproximateElo(650),
            engineLimits = EngineLimits(moveTimeMillis = 350L),
            opponentLevelId = "casual",
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
        const val FORFEIT_REOPEN_DATABASE = "room-checkpoint-forfeit-reopen-test.db"
        const val MIGRATION_DATABASE = "room-checkpoint-migration-test.db"
        const val MIGRATION_NONTERMINAL_DATABASE = "room-checkpoint-migration-live-test.db"
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
