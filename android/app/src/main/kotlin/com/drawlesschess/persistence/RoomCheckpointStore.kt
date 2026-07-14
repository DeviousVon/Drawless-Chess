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
import androidx.room.Transaction
import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.MaterialValues
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.StalematePolicy
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.coordinator.CheckpointSink
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorClock
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.MoveClockSnapshot
import com.drawlesschess.core.coordinator.TimeReading
import com.drawlesschess.core.coordinator.forfeitByHuman
import com.drawlesschess.core.engine.BotDifficultyCatalog
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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
                    if (!existing.hasSameImmutableFactsAs(candidate)) {
                        throw CompletedGameConflictException(candidate.gameId)
                    }
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

    private fun appendCompletedGameLocked(candidate: CompletedGameEntity) {
        val existing = loadCompletedGame(candidate.gameId)
        if (existing != null) {
            if (!existing.hasSameImmutableFactsAs(candidate)) {
                throw CompletedGameConflictException(candidate.gameId)
            }
            return
        }
        require(candidate.completionSequence == UNASSIGNED_COMPLETION_SEQUENCE) {
            "Completion sequence is assigned only by the local database"
        }
        insertCompletedGameOrThrow(
            candidate.copy(
                completionSequence = nextCompletionSequence(candidate.localProfileId),
            ),
        )
    }
}

@Database(
    entities = [
        ActiveGameCheckpointEntity::class,
        LocalPlayerProfileEntity::class,
        CompletedGameEntity::class,
    ],
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build()
            return RoomCheckpointStore(database)
        }

        private const val DATABASE_NAME = "drawless-chess.db"
        private const val LOG_TAG = "DrawlessChessSave"
    }
}

private data class PendingTerminalWrite(
    val checkpoint: ActiveGameCheckpointEntity,
    val completedGame: CompletedGameEntity,
)

internal object CoordinatorCheckpointCodec {
    private const val FORMAT_VERSION = 1

    fun encodeRulesForHistory(rules: RulesContractV1): String = encodeRules(rules).toString()

    fun encode(
        checkpoint: CoordinatorCheckpoint,
        updatedAtEpochMillis: Long,
    ): ActiveGameCheckpointEntity {
        val payload = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("revision", checkpoint.revision)
            .put("config", encodeConfig(checkpoint.config))
            .put("moves", JSONArray().apply { checkpoint.moves.forEach { put(it.value) } })
            .put("currentFen", checkpoint.currentFen)
            .putNullable("outcome", checkpoint.outcome?.let(::encodeOutcome))
            .put("clock", encodeClock(checkpoint.clock))
            .put(
                "moveClocks",
                JSONArray().apply { checkpoint.moveClocks.forEach { put(encodeMoveClock(it)) } },
            )
            .put("assistance", encodeAssistance(checkpoint.assistance))

        return ActiveGameCheckpointEntity(
            slot = ACTIVE_GAME_SLOT,
            gameId = checkpoint.config.gameId,
            revision = checkpoint.revision,
            checkpointFormat = FORMAT_VERSION,
            completed = checkpoint.outcome != null,
            updatedAtEpochMillis = updatedAtEpochMillis,
            payloadJson = payload.toString(),
        )
    }

    fun decode(entity: ActiveGameCheckpointEntity): CoordinatorCheckpoint {
        require(entity.slot == ACTIVE_GAME_SLOT) { "Unknown active-game slot ${entity.slot}" }
        require(entity.checkpointFormat == FORMAT_VERSION) {
            "Unsupported checkpoint format ${entity.checkpointFormat}"
        }
        val payload = JSONObject(entity.payloadJson)
        require(payload.getInt("formatVersion") == FORMAT_VERSION) {
            "Checkpoint payload format does not match its row"
        }
        val checkpoint = CoordinatorCheckpoint(
            revision = payload.getLong("revision"),
            config = decodeConfig(payload.getJSONObject("config")),
            moves = payload.getJSONArray("moves").mapObjects { UciMove(getString(it)) },
            currentFen = payload.getString("currentFen"),
            outcome = payload.requiredNullableObject("outcome")?.let(::decodeOutcome),
            clock = decodeClock(payload.getJSONObject("clock")),
            moveClocks = payload.getJSONArray("moveClocks").mapObjects {
                decodeMoveClock(getJSONObject(it))
            },
            assistance = decodeAssistance(payload.getJSONObject("assistance")),
        )
        require(checkpoint.config.gameId == entity.gameId) { "Checkpoint game ID does not match its row" }
        require(checkpoint.revision == entity.revision) { "Checkpoint revision does not match its row" }
        require((checkpoint.outcome != null) == entity.completed) {
            "Checkpoint completion state does not match its row"
        }
        return checkpoint
    }

    private fun encodeConfig(config: GameConfig): JSONObject = JSONObject()
        .put("gameId", config.gameId)
        .put("initialFen", config.initialFen)
        .put("rules", encodeRules(config.rules))
        .put("mode", config.mode.name)
        .put("timeControl", encodeTimeControl(config.timeControl))
        .put("humanSide", config.humanSide.name)
        .put("engineStrength", encodeEngineStrength(config.engineStrength))
        .putNullable("opponentLevelId", config.opponentLevelId)
        .put(
            "engineLimits",
            JSONObject()
                .put("moveTimeMillis", config.engineLimits.moveTimeMillis)
                .put("multiPv", config.engineLimits.multiPv),
        )

    private fun decodeConfig(value: JSONObject): GameConfig {
        val limits = value.getJSONObject("engineLimits")
        val engineStrength = decodeEngineStrength(value.getJSONObject("engineStrength"))
        val opponentLevelId = if (value.has("opponentLevelId")) {
            value.optionalNullableString("opponentLevelId")
        } else {
            // Format-v1 checkpoints shipped before named opponent IDs existed. Infer only exact
            // values from that immutable ladder; present JSON null means a genuine custom bot.
            (engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?.let(BotDifficultyCatalog::legacyLevelIdForElo)
        }
        return GameConfig(
            gameId = value.getString("gameId"),
            initialFen = value.getString("initialFen"),
            rules = decodeRules(value.getJSONObject("rules")),
            mode = enumValueOf(value.getString("mode")),
            timeControl = decodeTimeControl(value.getJSONObject("timeControl")),
            humanSide = enumValueOf(value.getString("humanSide")),
            engineStrength = engineStrength,
            engineLimits = EngineLimits(
                moveTimeMillis = limits.getLong("moveTimeMillis"),
                multiPv = limits.getInt("multiPv"),
            ),
            opponentLevelId = opponentLevelId,
        )
    }

    private fun encodeRules(rules: RulesContractV1): JSONObject = JSONObject()
        .put("schemaVersion", rules.schemaVersion)
        .put("preset", rules.preset.name)
        .put("stalemate", rules.stalemate.name)
        .put("deadPosition", rules.deadPosition.name)
        .put("fiftyMove", rules.fiftyMove.name)
        .put("repetitionThreshold", rules.repetitionThreshold)
        .put("completingPlayerLosesRepetition", rules.completingPlayerLosesRepetition)
        .put("forcedRepetitionException", rules.forcedRepetitionException)
        .put(
            "materialValues",
            JSONObject()
                .put("pawn", rules.materialValues.pawn)
                .put("knight", rules.materialValues.knight)
                .put("bishop", rules.materialValues.bishop)
                .put("rook", rules.materialValues.rook)
                .put("queen", rules.materialValues.queen),
        )

    private fun decodeRules(value: JSONObject): RulesContractV1 {
        require(value.getInt("schemaVersion") == 1) { "Unsupported rules schema" }
        val material = value.getJSONObject("materialValues")
        return RulesContractV1(
            preset = enumValueOf(value.getString("preset")),
            stalemate = enumValueOf(value.getString("stalemate")),
            deadPosition = enumValueOf(value.getString("deadPosition")),
            fiftyMove = enumValueOf(value.getString("fiftyMove")),
            repetitionThreshold = value.getInt("repetitionThreshold"),
            completingPlayerLosesRepetition = value.getBoolean("completingPlayerLosesRepetition"),
            forcedRepetitionException = value.getBoolean("forcedRepetitionException"),
            materialValues = MaterialValues(
                pawn = material.getInt("pawn"),
                knight = material.getInt("knight"),
                bishop = material.getInt("bishop"),
                rook = material.getInt("rook"),
                queen = material.getInt("queen"),
            ),
        )
    }

    private fun encodeTimeControl(value: TimeControl): JSONObject = when (value) {
        TimeControl.Untimed -> JSONObject().put("kind", "UNTIMED")
        is TimeControl.Clock -> JSONObject()
            .put("kind", "CLOCK")
            .put("initialMillis", value.initialMillis)
            .put("incrementMillis", value.incrementMillis)
    }

    private fun decodeTimeControl(value: JSONObject): TimeControl = when (value.getString("kind")) {
        "UNTIMED" -> TimeControl.Untimed
        "CLOCK" -> TimeControl.Clock(
            initialMillis = value.getLong("initialMillis"),
            incrementMillis = value.getLong("incrementMillis"),
        )
        else -> error("Unknown time-control kind")
    }

    private fun encodeEngineStrength(value: EngineStrength): JSONObject = when (value) {
        is EngineStrength.ApproximateElo -> JSONObject()
            .put("kind", "APPROXIMATE_ELO")
            .put("value", value.elo)
        is EngineStrength.SkillLevel -> JSONObject()
            .put("kind", "SKILL_LEVEL")
            .put("value", value.level)
    }

    private fun decodeEngineStrength(value: JSONObject): EngineStrength = when (value.getString("kind")) {
        "APPROXIMATE_ELO" -> EngineStrength.ApproximateElo(value.getInt("value"))
        "SKILL_LEVEL" -> EngineStrength.SkillLevel(value.getInt("value"))
        else -> error("Unknown engine-strength kind")
    }

    private fun encodeOutcome(value: GameOutcome): JSONObject = JSONObject()
        .put("winner", value.winner.name)
        .put("loser", value.loser.name)
        .put("reason", value.reason.name)
        .put("explanation", value.explanation)

    private fun decodeOutcome(value: JSONObject): GameOutcome = GameOutcome(
        winner = enumValueOf(value.getString("winner")),
        loser = enumValueOf(value.getString("loser")),
        reason = enumValueOf(value.getString("reason")),
        explanation = value.getString("explanation"),
    )

    private fun encodeClock(value: CoordinatorClock): JSONObject = JSONObject()
        .putNullable("whiteRemainingMillis", value.whiteRemainingMillis)
        .putNullable("blackRemainingMillis", value.blackRemainingMillis)
        .putNullable("runningSide", value.runningSide?.name)
        .putNullable("startedAtMonotonicMillis", value.startedAtMonotonicMillis)
        .putNullable("startedAtEpochMillis", value.startedAtEpochMillis)
        .put("paused", value.paused)

    private fun decodeClock(value: JSONObject): CoordinatorClock = CoordinatorClock(
        whiteRemainingMillis = value.requiredNullableLong("whiteRemainingMillis"),
        blackRemainingMillis = value.requiredNullableLong("blackRemainingMillis"),
        runningSide = value.requiredNullableString("runningSide")?.let { enumValueOf<Side>(it) },
        startedAtMonotonicMillis = value.requiredNullableLong("startedAtMonotonicMillis"),
        startedAtEpochMillis = value.requiredNullableLong("startedAtEpochMillis"),
        paused = value.getBoolean("paused"),
    )

    private fun encodeMoveClock(value: MoveClockSnapshot): JSONObject = JSONObject()
        .put("ply", value.ply)
        .putNullable("whiteRemainingMillis", value.whiteRemainingMillis)
        .putNullable("blackRemainingMillis", value.blackRemainingMillis)

    private fun decodeMoveClock(value: JSONObject): MoveClockSnapshot = MoveClockSnapshot(
        ply = value.getInt("ply"),
        whiteRemainingMillis = value.requiredNullableLong("whiteRemainingMillis"),
        blackRemainingMillis = value.requiredNullableLong("blackRemainingMillis"),
    )

    private fun encodeAssistance(value: AssistanceCounts): JSONObject = JSONObject()
        .put("hints", value.hints)
        .put("undos", value.undos)
        .put("pauses", value.pauses)
        .put("threatIndication", value.threatIndication)

    private fun decodeAssistance(value: JSONObject): AssistanceCounts = AssistanceCounts(
        hints = value.getInt("hints"),
        undos = value.getInt("undos"),
        pauses = value.getInt("pauses"),
        threatIndication = value.optionalStrictBoolean("threatIndication", false),
    )
}

private fun JSONObject.optionalStrictBoolean(name: String, defaultValue: Boolean): Boolean {
    if (!has(name)) return defaultValue
    val rawValue = get(name)
    require(rawValue is Boolean) { "'$name' must be a JSON boolean" }
    return rawValue
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun JSONObject.requiredNullableObject(name: String): JSONObject? {
    require(has(name)) { "Missing '$name'" }
    return if (isNull(name)) null else getJSONObject(name)
}

private fun JSONObject.requiredNullableLong(name: String): Long? {
    require(has(name)) { "Missing '$name'" }
    return if (isNull(name)) null else getLong(name)
}

private fun JSONObject.requiredNullableString(name: String): String? {
    require(has(name)) { "Missing '$name'" }
    return if (isNull(name)) null else getString(name)
}

private fun JSONObject.optionalNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

private inline fun <T> JSONArray.mapObjects(transform: JSONArray.(Int) -> T): List<T> =
    List(length()) { index -> transform(index) }

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
