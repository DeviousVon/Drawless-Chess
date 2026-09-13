package com.drawlesschess.persistence

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.coordinator.CheckpointSink
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.TimeReading
import com.drawlesschess.core.coordinator.forfeitByHuman
import com.drawlesschess.shared.SharedCheckpointCodec
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

@Entity(tableName = "active_game_checkpoint")
internal data class ActiveGameCheckpointEntity(
    @PrimaryKey
    val slot: Int,
    @ColumnInfo(name = "game_id")
    val gameId: String,
    val revision: Long,
    @ColumnInfo(name = "checkpoint_format")
    val checkpointFormat: Int,
    val completed: Boolean,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
)

@Dao
internal abstract class ActiveGameCheckpointDao {
    @Query("SELECT * FROM active_game_checkpoint WHERE slot = 1 LIMIT 1")
    abstract fun loadCurrent(): ActiveGameCheckpointEntity?

    @Query("SELECT * FROM active_game_checkpoint WHERE slot = 1 AND completed = 0 LIMIT 1")
    abstract fun loadResumable(): ActiveGameCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun replace(entity: ActiveGameCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertLocalProfile(profile: LocalPlayerProfileEntity): Long

    @Query("SELECT * FROM local_player_profile WHERE singleton_id = 1 LIMIT 1")
    abstract fun loadLocalProfile(): LocalPlayerProfileEntity?

    @Insert
    protected abstract fun insertCompletedGameOrThrow(game: CompletedGameEntity)

    @Query("SELECT * FROM completed_game WHERE game_id = :gameId LIMIT 1")
    abstract fun loadCompletedGame(gameId: String): CompletedGameEntity?

    @Query(
        "SELECT COALESCE(MAX(completion_sequence), 0) + 1 FROM completed_game " +
            "WHERE local_profile_id = :localProfileId",
    )
    protected abstract fun nextCompletionSequence(localProfileId: String): Long

    @Query(
        "SELECT * FROM completed_game " +
            "WHERE local_profile_id = :localProfileId " +
            "ORDER BY completion_sequence ASC",
    )
    abstract fun loadCompletedGames(localProfileId: String): List<CompletedGameEntity>

    @Insert
    protected abstract fun insertGameReviewOrThrow(review: GameReviewEntity)

    @Update
    protected abstract fun updateGameReview(review: GameReviewEntity)

    @Query("SELECT * FROM game_review WHERE game_id = :gameId LIMIT 1")
    abstract fun loadGameReview(gameId: String): GameReviewEntity?

    @Query("DELETE FROM active_game_checkpoint")
    abstract fun clear()

    @Transaction
    open fun persistIfNewer(entity: ActiveGameCheckpointEntity) {
        persistCheckpointAndCompletion(entity, null)
    }

    /** The terminal checkpoint and append-only history fact commit in one SQLite transaction. */
    @Transaction
    open fun persistCheckpointAndCompletion(
        entity: ActiveGameCheckpointEntity,
        completedGame: CompletedGameEntity?,
    ) {
        require(entity.slot == ACTIVE_GAME_SLOT)
        val current = loadCurrent()
        val accepted = current == null ||
            current.gameId != entity.gameId ||
            entity.revision > current.revision
        if (!accepted) {
            // Validate an already-recorded exact retry, but never let a rejected stale terminal
            // checkpoint create history that was not accepted as the active authority.
            completedGame?.let { candidate ->
                loadCompletedGame(candidate.gameId)?.let { existing ->
                    requireSameCompletedGame(existing, candidate)
                }
            }
            return
        }
        completedGame?.let(::appendCompletedGameLocked)
        replace(entity)
    }

    @Transaction
    open fun appendCompletedGame(completedGame: CompletedGameEntity) {
        appendCompletedGameLocked(completedGame)
    }

    @Transaction
    open fun persistGameReview(review: GameReviewEntity) {
        val game = CompletedGameHistoryCodec.decode(requireNotNull(loadCompletedGame(review.gameId)) {
            "A completed Review cannot exist without its completed game"
        })
        if (GameReviewEntityDecoder.decode(review, game) == null) {
            throw GameReviewConflictException(review.gameId)
        }
        val existing = loadGameReview(review.gameId)
        if (existing == null) {
            insertGameReviewOrThrow(review)
        } else if (!existing.hasSameImmutableFactsAs(review)) {
            if (existing.evidenceSchemaVersion == review.evidenceSchemaVersion &&
                (GameReviewEntityDecoder.decode(existing, game) == null ||
                    (existing.evidencePayloadSha256 == review.evidencePayloadSha256 &&
                        existing.evidenceCacheKey == review.evidenceCacheKey))
            ) {
                // Explicit reanalysis repairs known stale evidence atomically. Unknown formats
                // stay intact, and conflicting complete current evidence is never overwritten.
                updateGameReview(review)
            } else {
                throw GameReviewConflictException(review.gameId)
            }
        }
    }

    private fun appendCompletedGameLocked(candidate: CompletedGameEntity) {
        val existing = loadCompletedGame(candidate.gameId)
        if (existing != null) {
            requireSameCompletedGame(existing, candidate)
            return
        }
        require(candidate.completionSequence == UNASSIGNED_COMPLETION_SEQUENCE) {
            "Completion sequence is assigned only by the local database"
        }
        val completedGames = loadCompletedGames(candidate.localProfileId)
        val candidateWithRating = AdaptiveRatingHistory.withCurrentSnapshot(
            candidate = candidate,
            current = AdaptiveRatingHistory.current(completedGames),
        )
        insertCompletedGameOrThrow(
            candidateWithRating.copy(
                completionSequence = nextCompletionSequence(candidate.localProfileId),
            ),
        )
    }

    private fun requireSameCompletedGame(
        existing: CompletedGameEntity,
        candidate: CompletedGameEntity,
    ) {
        // The rating columns are database-derived. Restore the durable snapshot before comparing
        // the checkpoint-derived facts, otherwise an exact retry after a rating change appears
        // divergent simply because its factory saw the already-advanced rating.
        val comparable = AdaptiveRatingHistory.withStoredSnapshot(candidate, existing)
        if (!existing.hasSameImmutableFactsAs(comparable)) {
            throw CompletedGameConflictException(candidate.gameId)
        }
    }
}

@Database(
    entities = [
        ActiveGameCheckpointEntity::class,
        LocalPlayerProfileEntity::class,
        CompletedGameEntity::class,
        GameReviewEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class DrawlessDatabase : RoomDatabase() {
    abstract fun activeGameCheckpointDao(): ActiveGameCheckpointDao
}

/**
 * Application-scoped Room adapter. Every read and write uses one FIFO executor so a later game
 * cannot be observed before its earlier checkpoint writes have reached SQLite.
 */
internal class RoomCheckpointStore(
    private val database: DrawlessDatabase,
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        PersistenceThreadFactory(),
    ),
    private val callbackExecutor: Executor = mainThreadExecutor(),
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val localProfileIdSource: () -> String = { UUID.randomUUID().toString() },
    private val beforeAcceptedCheckpointEnqueue: () -> Unit = {},
    private val beforeCheckpointTransaction: (
        ActiveGameCheckpointEntity,
        CompletedGameEntity?,
    ) -> Unit = { _, _ -> },
) {
    private val dao = database.activeGameCheckpointDao()
    private val generationLock = Any()
    private val generationCounter = AtomicLong()
    private val writeFailure = AtomicReference<Throwable?>()
    private val fatalWriteFailure = AtomicReference<Throwable?>()
    private var pendingTerminalWrite: PendingTerminalWrite? = null

    @Volatile
    private var activeGeneration = 0L

    init {
        ioExecutor.execute {
            runCatching(::initializeStorage).onFailure(::recordWriteFailure)
        }
    }

    fun activateNewGame(): CheckpointSink = activate()

    fun activateResume(): CheckpointSink = activate()

    fun loadResumable(onResult: (Result<CoordinatorCheckpoint?>) -> Unit) {
        ioExecutor.execute {
            val result = runCatching {
                throwIfFatalPersistenceFailure()
                retryPendingTerminalWrite()
                writeFailure.get()?.let { throw IllegalStateException("Saving the game failed", it) }
                dao.loadResumable()?.let(CoordinatorCheckpointCodec::decode)
            }.onFailure(::recordWriteFailure)
            callbackExecutor.execute { onResult(result) }
        }
    }

    fun loadPlayerStats(onResult: (Result<PlayerStatistics>) -> Unit) {
        ioExecutor.execute {
            val result = runCatching {
                throwIfFatalPersistenceFailure()
                retryPendingTerminalWrite()
                writeFailure.get()?.let { throw IllegalStateException("Saving player data failed", it) }
                val profile = requireNotNull(dao.loadLocalProfile()) {
                    "The local player profile has not been initialized"
                }
                PlayerStatisticsCalculator.calculate(
                    profile = profile,
                    games = dao.loadCompletedGames(profile.localProfileId),
                )
            }.onFailure(::recordWriteFailure)
            callbackExecutor.execute { onResult(result) }
        }
    }

    fun saveCompletedGameReview(
        review: GameReviewResult,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        ioExecutor.execute {
            val result = runCatching {
                retryPendingTerminalWrite()
                val completedEntity = requireNotNull(dao.loadCompletedGame(review.gameId)) {
                    "The completed game was not durable before its Review finished"
                }
                val completedGame = CompletedGameHistoryCodec.decode(completedEntity)
                val emptyReview = completedGame.emptyReviewOrNull()
                if (emptyReview != null) {
                    require(review == emptyReview) { "Empty Review does not match canonical history" }
                    return@runCatching
                }
                val entity = GameReviewRecordFactory.from(
                    result = review,
                    completedGame = completedGame,
                    completedAtEpochMillis = epochMillis(),
                )
                dao.persistGameReview(entity)
            }.onFailure { error -> Log.e(REVIEW_LOG_TAG, "Saving completed Review failed", error) }
            callbackExecutor.execute { onResult(result) }
        }
    }

    fun loadGameHistory(onResult: (Result<List<GameHistoryEntry>>) -> Unit) {
        ioExecutor.execute {
            val result = runCatching {
                retryPendingTerminalWrite()
                val profile = requireNotNull(dao.loadLocalProfile()) {
                    "The local player profile has not been initialized"
                }
                dao.loadCompletedGames(profile.localProfileId)
                    .asReversed()
                    .map { entity ->
                        val game = CompletedGameHistoryCodec.decode(entity)
                        GameHistoryEntry(
                            game = game,
                            reviewAvailability = GameReviewEntityDecoder.availability(
                                dao.loadGameReview(game.gameId),
                                game,
                            ),
                        )
                    }
            }
            callbackExecutor.execute { onResult(result) }
        }
    }

    fun loadHistoricalGameReview(
        gameId: String,
        onResult: (Result<HistoricalGameReview?>) -> Unit,
    ) {
        require(gameId.isNotBlank())
        ioExecutor.execute {
            val result = runCatching {
                retryPendingTerminalWrite()
                dao.loadCompletedGame(gameId)?.let { entity ->
                    val game = CompletedGameHistoryCodec.decode(entity)
                    val reviewEntity = dao.loadGameReview(gameId)
                    val review = game.emptyReviewOrNull()
                        ?: reviewEntity?.let { GameReviewEntityDecoder.decode(it, game) }
                    HistoricalGameReview(
                        game = game,
                        review = review,
                        reviewAvailability = when {
                            review != null -> HistoricalReviewAvailability.READY
                            reviewEntity == null -> HistoricalReviewAvailability.NOT_ANALYZED
                            else -> HistoricalReviewAvailability.STALE
                        },
                    )
                }
            }
            callbackExecutor.execute { onResult(result) }
        }
    }

    /**
     * Invalidates the active runtime generation, then commits the current resumable game as a
     * completed loss before reporting success. Because generation invalidation and FIFO
     * submission share [generationLock], an already-accepted write is ordered before the
     * forfeit and every later write from the abandoned runtime is rejected.
     */
    fun forfeitResumable(
        expectedGameId: String,
        onResult: (Result<Boolean>) -> Unit,
    ) {
        require(expectedGameId.isNotBlank()) { "The expected game ID cannot be blank" }
        synchronized(generationLock) {
            activeGeneration = generationCounter.incrementAndGet()
            ioExecutor.execute {
                val result = runCatching {
                    throwIfFatalPersistenceFailure()
                    retryPendingTerminalWrite()
                    val liveEntity = dao.loadResumable()
                    if (liveEntity == null) {
                        // A retry may have committed its pending terminal transaction before
                        // reaching this lookup. Treat that as success only when the exact game
                        // now has both halves of the durable forfeit fact. Every other missing or
                        // stale state must fail closed so the caller cannot launch a replacement.
                        val terminal = dao.loadCurrent()
                        val history = dao.loadCompletedGame(expectedGameId)
                        check(
                            terminal?.gameId == expectedGameId &&
                                terminal.completed &&
                                history?.result == "LOSS" &&
                                history.endReason == EndReason.RESIGNATION.name,
                        ) { "The saved game was no longer available to forfeit" }
                        return@runCatching true
                    }
                    check(liveEntity.gameId == expectedGameId) {
                        "The saved game changed before its forfeit was confirmed"
                    }
                    val savedAt = epochMillis()
                    val forfeited = CoordinatorCheckpointCodec.decode(liveEntity).forfeitByHuman(
                        TimeReading(
                            monotonicMillis = monotonicMillis(),
                            epochMillis = savedAt,
                        ),
                    )
                    persistCheckpoint(forfeited, savedAt)
                    true
                }.onFailure(::recordWriteFailure)
                callbackExecutor.execute { onResult(result) }
            }
        }
    }

    fun discard(onResult: (Result<Unit>) -> Unit) {
        synchronized(generationLock) {
            activeGeneration = generationCounter.incrementAndGet()
            ioExecutor.execute {
                val result = runCatching {
                    throwIfFatalPersistenceFailure()
                    retryPendingTerminalWrite()
                    dao.clear()
                    clearRecoverableWriteFailure()
                }.onFailure(::recordWriteFailure)
                callbackExecutor.execute { onResult(result) }
            }
        }
    }

    internal fun closeForTest() {
        synchronized(generationLock) {
            activeGeneration = generationCounter.incrementAndGet()
            ioExecutor.shutdown()
        }
        if (!ioExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            ioExecutor.shutdownNow()
        }
        database.close()
    }

    private fun activate(): CheckpointSink {
        val generation = synchronized(generationLock) {
            generationCounter.incrementAndGet().also { activeGeneration = it }
        }
        return CheckpointSink { checkpoint ->
            synchronized(generationLock) {
                if (activeGeneration != generation) return@CheckpointSink
                // Generation acceptance and FIFO submission are one critical section. A rematch
                // can therefore either reject this write or follow it, but can never overtake it.
                beforeAcceptedCheckpointEnqueue()
                ioExecutor.execute {
                    runCatching { persistCheckpoint(checkpoint) }.onFailure(::recordWriteFailure)
                }
            }
        }
    }

    private fun persistCheckpoint(
        checkpoint: CoordinatorCheckpoint,
        savedAt: Long = epochMillis(),
    ) {
        // A terminal write owns the front of the durable queue until both its checkpoint and
        // immutable history row commit. A later game's success must not erase its failure.
        throwIfFatalPersistenceFailure()
        retryPendingTerminalWrite()
        val entity = CoordinatorCheckpointCodec.encode(checkpoint, savedAt)
        val completedGame = checkpoint.outcome?.let {
            val profile = requireNotNull(dao.loadLocalProfile()) {
                "The local player profile has not been initialized"
            }
            CompletedGameRecordFactory.from(
                checkpoint = checkpoint,
                localProfileId = profile.localProfileId,
                completedAtEpochMillis = savedAt,
            )
        }
        try {
            beforeCheckpointTransaction(entity, completedGame)
            dao.persistCheckpointAndCompletion(entity, completedGame)
        } catch (error: Throwable) {
            if (completedGame != null && error !is CompletedGameConflictException) {
                pendingTerminalWrite = PendingTerminalWrite(entity, completedGame)
            }
            throw error
        }
        clearRecoverableWriteFailure()
    }

    private fun retryPendingTerminalWrite() {
        val pending = pendingTerminalWrite ?: return
        beforeCheckpointTransaction(pending.checkpoint, pending.completedGame)
        dao.persistCheckpointAndCompletion(pending.checkpoint, pending.completedGame)
        pendingTerminalWrite = null
        clearRecoverableWriteFailure()
    }

    private fun initializeStorage() {
        val createdAt = epochMillis()
        val candidateProfileId = localProfileIdSource()
        require(candidateProfileId.isNotBlank()) { "The local profile ID cannot be blank" }
        dao.insertLocalProfile(
            LocalPlayerProfileEntity(
                singletonId = LOCAL_PROFILE_SINGLETON_ID,
                localProfileId = candidateProfileId,
                serverProfileId = null,
                displayName = DEFAULT_DISPLAY_NAME,
                avatarId = null,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = createdAt,
                profileSchemaVersion = LOCAL_PROFILE_SCHEMA_VERSION,
                uploadConsentState = UPLOAD_CONSENT_NOT_REQUESTED,
            ),
        )
        val profile = requireNotNull(dao.loadLocalProfile()) {
            "The local player profile could not be initialized"
        }

        // A v1 install could contain one terminal active checkpoint. Preserve it exactly once.
        val legacyCompletion = dao.loadCurrent()?.takeIf { it.completed } ?: return
        if (dao.loadCompletedGame(legacyCompletion.gameId) != null) return
        val checkpoint = CoordinatorCheckpointCodec.decode(legacyCompletion)
        dao.appendCompletedGame(
            CompletedGameRecordFactory.from(
                checkpoint = checkpoint,
                localProfileId = profile.localProfileId,
                completedAtEpochMillis = legacyCompletion.updatedAtEpochMillis,
            ),
        )
    }

    private fun recordWriteFailure(error: Throwable) {
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<CompletedGameConflictException>()
            .firstOrNull()
            ?.let { fatalWriteFailure.compareAndSet(null, it) }
        writeFailure.set(error)
        Log.e(LOG_TAG, "Room checkpoint write failed", error)
    }

    private fun throwIfFatalPersistenceFailure() {
        fatalWriteFailure.get()?.let { fatal ->
            throw IllegalStateException("Immutable game history failed its integrity check", fatal)
        }
    }

    private fun clearRecoverableWriteFailure() {
        if (fatalWriteFailure.get() == null) writeFailure.set(null)
    }

    companion object {
        fun create(context: android.content.Context): RoomCheckpointStore {
            val database = Room.databaseBuilder(
                context.applicationContext,
                DrawlessDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
            return RoomCheckpointStore(database)
        }

        private const val DATABASE_NAME = "drawless-chess.db"
        private const val LOG_TAG = "DrawlessChessSave"
        private const val REVIEW_LOG_TAG = "DrawlessChessReview"
    }
}

private data class PendingTerminalWrite(
    val checkpoint: ActiveGameCheckpointEntity,
    val completedGame: CompletedGameEntity,
)

internal object CoordinatorCheckpointCodec {
    fun encodeRulesForHistory(rules: RulesContractV1): String =
        SharedCheckpointCodec.encodeRulesForHistory(rules)

    fun encode(
        checkpoint: CoordinatorCheckpoint,
        updatedAtEpochMillis: Long,
    ): ActiveGameCheckpointEntity = ActiveGameCheckpointEntity(
        slot = ACTIVE_GAME_SLOT,
        gameId = checkpoint.config.gameId,
        revision = checkpoint.revision,
        checkpointFormat = SharedCheckpointCodec.FORMAT_VERSION,
        completed = checkpoint.outcome != null,
        updatedAtEpochMillis = updatedAtEpochMillis,
        payloadJson = SharedCheckpointCodec.encode(checkpoint),
    )

    fun decode(entity: ActiveGameCheckpointEntity): CoordinatorCheckpoint {
        require(entity.slot == ACTIVE_GAME_SLOT) { "Unknown active-game slot ${entity.slot}" }
        require(entity.checkpointFormat == SharedCheckpointCodec.FORMAT_VERSION) {
            "Unsupported checkpoint format ${entity.checkpointFormat}"
        }
        val checkpoint = SharedCheckpointCodec.decode(entity.payloadJson)
        require(checkpoint.config.gameId == entity.gameId) {
            "Checkpoint game ID does not match its row"
        }
        require(checkpoint.revision == entity.revision) {
            "Checkpoint revision does not match its row"
        }
        require((checkpoint.outcome != null) == entity.completed) {
            "Checkpoint completion state does not match its row"
        }
        return checkpoint
    }
}

private fun mainThreadExecutor(): Executor {
    val handler = Handler(Looper.getMainLooper())
    return Executor { action ->
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }
}

private class PersistenceThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread(
        task,
        "drawless-room-${sequence.incrementAndGet()}",
    ).apply { isDaemon = true }
}

private const val ACTIVE_GAME_SLOT = 1
