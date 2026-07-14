package com.drawlesschess.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.GameScoring
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.engine.BotDifficultyCatalog
import java.util.UUID
import org.json.JSONArray

/** The one installation-local profile. A future account ID is deliberately a separate field. */
@Entity(
    tableName = "local_player_profile",
    indices = [Index(value = ["local_profile_id"], unique = true)],
)
internal data class LocalPlayerProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int,
    @ColumnInfo(name = "local_profile_id")
    val localProfileId: String,
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "avatar_id")
    val avatarId: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "profile_schema_version")
    val profileSchemaVersion: Int,
    @ColumnInfo(name = "upload_consent_state")
    val uploadConsentState: String,
)

/**
 * Append-only facts for one completed game. Aggregates are always rebuilt from these rows.
 *
 * The integrity columns are intentionally honest placeholders. They must remain NOT_VERIFIED/NONE
 * until an Android Keystore-backed signer is implemented; a locally generated UUID is not proof.
 */
@Entity(
    tableName = "completed_game",
    indices = [
        Index(value = ["local_profile_id", "completed_at_epoch_millis"]),
        Index(value = ["local_profile_id", "completion_sequence"], unique = true),
        Index(value = ["opponent_stable_id", "completed_at_epoch_millis"]),
    ],
)
internal data class CompletedGameEntity(
    @PrimaryKey
    @ColumnInfo(name = "game_id")
    val gameId: String,
    @ColumnInfo(name = "local_profile_id")
    val localProfileId: String,
    @ColumnInfo(name = "record_format_version")
    val recordFormatVersion: Int,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
    @ColumnInfo(name = "completion_sequence")
    val completionSequence: Long,
    val authority: String,
    @ColumnInfo(name = "integrity_state")
    val integrityState: String,
    @ColumnInfo(name = "integrity_algorithm")
    val integrityAlgorithm: String,
    @ColumnInfo(name = "integrity_signature")
    val integritySignature: String?,
    @ColumnInfo(name = "previous_integrity_signature")
    val previousIntegritySignature: String?,
    @ColumnInfo(name = "opponent_stable_id")
    val opponentStableId: String,
    @ColumnInfo(name = "opponent_type")
    val opponentType: String,
    @ColumnInfo(name = "opponent_exact_elo")
    val opponentExactElo: Int?,
    @ColumnInfo(name = "opponent_strength_kind")
    val opponentStrengthKind: String,
    @ColumnInfo(name = "opponent_strength_value")
    val opponentStrengthValue: Int,
    @ColumnInfo(name = "engine_move_time_millis")
    val engineMoveTimeMillis: Long,
    @ColumnInfo(name = "engine_multi_pv")
    val engineMultiPv: Int,
    @ColumnInfo(name = "game_mode")
    val gameMode: String,
    @ColumnInfo(name = "initial_fen")
    val initialFen: String,
    @ColumnInfo(name = "moves_json")
    val movesJson: String,
    @ColumnInfo(name = "rules_schema_version")
    val rulesSchemaVersion: Int,
    @ColumnInfo(name = "rules_preset")
    val rulesPreset: String,
    @ColumnInfo(name = "rules_json")
    val rulesJson: String,
    @ColumnInfo(name = "time_control_kind")
    val timeControlKind: String,
    @ColumnInfo(name = "time_initial_millis")
    val timeInitialMillis: Long?,
    @ColumnInfo(name = "time_increment_millis")
    val timeIncrementMillis: Long?,
    @ColumnInfo(name = "player_side")
    val playerSide: String,
    @ColumnInfo(name = "winner_side")
    val winnerSide: String,
    val result: String,
    @ColumnInfo(name = "end_reason")
    val endReason: String,
    @ColumnInfo(name = "outcome_explanation")
    val outcomeExplanation: String,
    @ColumnInfo(name = "move_count")
    val moveCount: Int,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "undo_count")
    val undoCount: Int,
    @ColumnInfo(name = "pause_count")
    val pauseCount: Int,
    @ColumnInfo(name = "threat_indication_enabled")
    val threatIndicationEnabled: Boolean,
    @ColumnInfo(name = "score_system_version")
    val scoreSystemVersion: Int,
    @ColumnInfo(name = "score_base_points")
    val scoreBasePoints: Int,
    @ColumnInfo(name = "score_hint_penalty")
    val scoreHintPenalty: Int,
    @ColumnInfo(name = "score_undo_penalty")
    val scoreUndoPenalty: Int,
    @ColumnInfo(name = "score_pause_penalty")
    val scorePausePenalty: Int,
    @ColumnInfo(name = "score_threat_penalty")
    val scoreThreatPenalty: Int,
    @ColumnInfo(name = "score_final_points")
    val scoreFinalPoints: Int,
    @ColumnInfo(name = "rating_namespace")
    val ratingNamespace: String?,
    @ColumnInfo(name = "rating_pool")
    val ratingPool: String?,
    @ColumnInfo(name = "player_rating_before")
    val playerRatingBefore: Int?,
    @ColumnInfo(name = "player_rating_after")
    val playerRatingAfter: Int?,
) {
    /** Wall-clock time and the locally assigned sequence are metadata, not game facts. */
    fun hasSameImmutableFactsAs(other: CompletedGameEntity): Boolean =
        copy(completedAtEpochMillis = 0L, completionSequence = 0L) ==
            other.copy(completedAtEpochMillis = 0L, completionSequence = 0L)
}

internal data class OpponentStatistics(
    val opponentStableId: String,
    /** Most recent exact Elo snapshot; null only when the source configuration had no Elo. */
    val opponentExactElo: Int?,
    val completedGames: Int,
    val wins: Int,
    val losses: Int,
    val winPercentage: Double,
    val averageScore: Double,
)

internal data class PlayerStatistics(
    val localProfileId: String,
    val displayName: String = DEFAULT_DISPLAY_NAME,
    val avatarId: String? = null,
    val latestGameId: String? = null,
    val completedGames: Int,
    val wins: Int,
    val losses: Int,
    val winPercentage: Double?,
    val averageScore: Double?,
    val currentWinStreak: Int,
    val bestWinStreak: Int,
    val unassistedWins: Int,
    val opponents: List<OpponentStatistics>,
)

internal object CompletedGameRecordFactory {
    fun from(
        checkpoint: CoordinatorCheckpoint,
        localProfileId: String,
        completedAtEpochMillis: Long,
    ): CompletedGameEntity {
        val outcome = requireNotNull(checkpoint.outcome) { "Only completed games enter history" }
        val config = checkpoint.config
        val playerWon = outcome.winner == config.humanSide
        val score = GameScoring.forResult(
            playerWon = playerWon,
            assistance = checkpoint.assistance,
            timeControl = config.timeControl,
        )
        val opponent = opponentIdentity(config)
        val time = config.timeControl

        return CompletedGameEntity(
            gameId = config.gameId,
            localProfileId = localProfileId,
            recordFormatVersion = COMPLETED_GAME_FORMAT_VERSION,
            completedAtEpochMillis = completedAtEpochMillis,
            completionSequence = UNASSIGNED_COMPLETION_SEQUENCE,
            authority = AUTHORITY_LOCAL_ONLY,
            integrityState = INTEGRITY_NOT_VERIFIED,
            integrityAlgorithm = INTEGRITY_ALGORITHM_NONE,
            integritySignature = null,
            previousIntegritySignature = null,
            opponentStableId = opponent.stableId,
            opponentType = OPPONENT_TYPE_BOT,
            opponentExactElo = opponent.exactElo,
            opponentStrengthKind = opponent.strengthKind,
            opponentStrengthValue = opponent.strengthValue,
            engineMoveTimeMillis = config.engineLimits.moveTimeMillis,
            engineMultiPv = config.engineLimits.multiPv,
            gameMode = config.mode.name,
            initialFen = config.initialFen,
            movesJson = JSONArray().apply {
                checkpoint.moves.forEach { put(it.value) }
            }.toString(),
            rulesSchemaVersion = config.rules.schemaVersion,
            rulesPreset = config.rules.preset.name,
            rulesJson = CoordinatorCheckpointCodec.encodeRulesForHistory(config.rules),
            timeControlKind = if (time is TimeControl.Clock) TIME_CONTROL_CLOCK else TIME_CONTROL_UNTIMED,
            timeInitialMillis = (time as? TimeControl.Clock)?.initialMillis,
            timeIncrementMillis = (time as? TimeControl.Clock)?.incrementMillis,
            playerSide = config.humanSide.name,
            winnerSide = outcome.winner.name,
            result = if (playerWon) RESULT_WIN else RESULT_LOSS,
            endReason = outcome.reason.name,
            outcomeExplanation = outcome.explanation,
            moveCount = checkpoint.moves.size,
            hintCount = checkpoint.assistance.hints,
            undoCount = checkpoint.assistance.undos,
            pauseCount = checkpoint.assistance.pauses,
            threatIndicationEnabled = checkpoint.assistance.threatIndication,
            scoreSystemVersion = score.scoringVersion,
            scoreBasePoints = score.maximumPoints,
            scoreHintPenalty = score.hintPenalty,
            scoreUndoPenalty = score.undoPenalty,
            scorePausePenalty = score.timedPausePenalty,
            scoreThreatPenalty = score.threatIndicationPenalty,
            // The core scoring contract is authoritative; breakdown columns explain that value.
            scoreFinalPoints = score.points,
            // Reserved for rated play; casual games cannot truthfully supply these snapshots.
            ratingNamespace = null,
            ratingPool = null,
            playerRatingBefore = null,
            playerRatingAfter = null,
        )
    }

    private fun opponentIdentity(config: GameConfig): OpponentIdentity = when (val strength = config.engineStrength) {
        is EngineStrength.ApproximateElo -> {
            val namedLevelId = BotDifficultyCatalog.namedOrNull(config.opponentLevelId)?.id
            OpponentIdentity(
                stableId = namedLevelId?.let { "bot:$it" } ?: "bot:elo:${strength.elo}",
                exactElo = strength.elo,
                strengthKind = STRENGTH_APPROXIMATE_ELO,
                strengthValue = strength.elo,
            )
        }
        is EngineStrength.SkillLevel -> OpponentIdentity(
            stableId = "bot:skill:${strength.level}",
            exactElo = null,
            strengthKind = STRENGTH_SKILL_LEVEL,
            strengthValue = strength.level,
        )
    }
}

internal object PlayerStatisticsCalculator {
    fun calculate(
        profile: LocalPlayerProfileEntity,
        games: List<CompletedGameEntity>,
    ): PlayerStatistics {
        val wins = games.count { it.result == RESULT_WIN }
        val losses = games.size - wins
        var currentStreak = 0
        var runningStreak = 0
        var bestStreak = 0
        games.forEach { game ->
            if (game.result == RESULT_WIN) {
                runningStreak++
                bestStreak = maxOf(bestStreak, runningStreak)
            } else {
                runningStreak = 0
            }
        }
        currentStreak = runningStreak

        val opponents = games
            .groupBy { it.opponentStableId }
            .map { (opponentStableId, records) ->
                val opponentWins = records.count { it.result == RESULT_WIN }
                OpponentStatistics(
                    opponentStableId = opponentStableId,
                    opponentExactElo = records.last().opponentExactElo,
                    completedGames = records.size,
                    wins = opponentWins,
                    losses = records.size - opponentWins,
                    winPercentage = percent(opponentWins, records.size),
                    averageScore = records.map { it.scoreFinalPoints }.average(),
                )
            }
            .sortedWith(
                compareBy<OpponentStatistics> { it.opponentExactElo ?: Int.MAX_VALUE }
                    .thenBy { it.opponentStableId },
            )

        return PlayerStatistics(
            localProfileId = profile.localProfileId,
            displayName = profile.displayName,
            avatarId = profile.avatarId,
            latestGameId = games.lastOrNull()?.gameId,
            completedGames = games.size,
            wins = wins,
            losses = losses,
            winPercentage = games.takeIf { it.isNotEmpty() }?.let { percent(wins, it.size) },
            averageScore = games.takeIf { it.isNotEmpty() }
                ?.map { it.scoreFinalPoints }
                ?.average(),
            currentWinStreak = currentStreak,
            bestWinStreak = bestStreak,
            unassistedWins = games.count { game ->
                game.result == RESULT_WIN &&
                    game.scoreHintPenalty == 0 &&
                    game.scoreUndoPenalty == 0 &&
                    game.scorePausePenalty == 0 &&
                    game.scoreThreatPenalty == 0
            },
            opponents = opponents,
        )
    }

    private fun percent(numerator: Int, denominator: Int): Double =
        numerator.toDouble() * 100.0 / denominator
}

/** Explicit, additive v1 -> v2 migration. Existing active checkpoints are never deleted. */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_player_profile` (
                `singleton_id` INTEGER NOT NULL,
                `local_profile_id` TEXT NOT NULL,
                `server_profile_id` TEXT,
                `display_name` TEXT NOT NULL,
                `avatar_id` TEXT,
                `created_at_epoch_millis` INTEGER NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                `profile_schema_version` INTEGER NOT NULL,
                `upload_consent_state` TEXT NOT NULL,
                PRIMARY KEY(`singleton_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_player_profile_local_profile_id` " +
                "ON `local_player_profile` (`local_profile_id`)",
        )
        db.execSQL(completedGameTableSql())
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_completed_game_local_profile_id_completed_at_epoch_millis` " +
                "ON `completed_game` (`local_profile_id`, `completed_at_epoch_millis`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_completed_game_local_profile_id_completion_sequence` " +
                "ON `completed_game` (`local_profile_id`, `completion_sequence`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_completed_game_opponent_stable_id_completed_at_epoch_millis` " +
                "ON `completed_game` (`opponent_stable_id`, `completed_at_epoch_millis`)",
        )

        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO `local_player_profile` (
                `singleton_id`, `local_profile_id`, `server_profile_id`, `display_name`, `avatar_id`,
                `created_at_epoch_millis`, `updated_at_epoch_millis`,
                `profile_schema_version`, `upload_consent_state`
            ) VALUES (?, ?, NULL, ?, NULL, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                LOCAL_PROFILE_SINGLETON_ID,
                UUID.randomUUID().toString(),
                DEFAULT_DISPLAY_NAME,
                now,
                now,
                LOCAL_PROFILE_SCHEMA_VERSION,
                UPLOAD_CONSENT_NOT_REQUESTED,
            ),
        )
    }
}

private fun completedGameTableSql(): String =
    """
    CREATE TABLE IF NOT EXISTS `completed_game` (
        `game_id` TEXT NOT NULL,
        `local_profile_id` TEXT NOT NULL,
                `record_format_version` INTEGER NOT NULL,
                `completed_at_epoch_millis` INTEGER NOT NULL,
                `completion_sequence` INTEGER NOT NULL,
        `authority` TEXT NOT NULL,
        `integrity_state` TEXT NOT NULL,
        `integrity_algorithm` TEXT NOT NULL,
        `integrity_signature` TEXT,
        `previous_integrity_signature` TEXT,
        `opponent_stable_id` TEXT NOT NULL,
        `opponent_type` TEXT NOT NULL,
        `opponent_exact_elo` INTEGER,
        `opponent_strength_kind` TEXT NOT NULL,
        `opponent_strength_value` INTEGER NOT NULL,
        `engine_move_time_millis` INTEGER NOT NULL,
        `engine_multi_pv` INTEGER NOT NULL,
                `game_mode` TEXT NOT NULL,
                `initial_fen` TEXT NOT NULL,
                `moves_json` TEXT NOT NULL,
        `rules_schema_version` INTEGER NOT NULL,
        `rules_preset` TEXT NOT NULL,
        `rules_json` TEXT NOT NULL,
        `time_control_kind` TEXT NOT NULL,
        `time_initial_millis` INTEGER,
        `time_increment_millis` INTEGER,
        `player_side` TEXT NOT NULL,
        `winner_side` TEXT NOT NULL,
        `result` TEXT NOT NULL,
        `end_reason` TEXT NOT NULL,
        `outcome_explanation` TEXT NOT NULL,
        `move_count` INTEGER NOT NULL,
        `hint_count` INTEGER NOT NULL,
        `undo_count` INTEGER NOT NULL,
        `pause_count` INTEGER NOT NULL,
        `threat_indication_enabled` INTEGER NOT NULL,
        `score_system_version` INTEGER NOT NULL,
        `score_base_points` INTEGER NOT NULL,
        `score_hint_penalty` INTEGER NOT NULL,
        `score_undo_penalty` INTEGER NOT NULL,
        `score_pause_penalty` INTEGER NOT NULL,
        `score_threat_penalty` INTEGER NOT NULL,
        `score_final_points` INTEGER NOT NULL,
        `rating_namespace` TEXT,
        `rating_pool` TEXT,
        `player_rating_before` INTEGER,
        `player_rating_after` INTEGER,
        PRIMARY KEY(`game_id`)
    )
    """.trimIndent()

private data class OpponentIdentity(
    val stableId: String,
    val exactElo: Int?,
    val strengthKind: String,
    val strengthValue: Int,
)

internal const val LOCAL_PROFILE_SINGLETON_ID = 1
internal const val LOCAL_PROFILE_SCHEMA_VERSION = 1
internal const val COMPLETED_GAME_FORMAT_VERSION = 1
internal const val UNASSIGNED_COMPLETION_SEQUENCE = 0L
internal const val AUTHORITY_LOCAL_ONLY = "LOCAL_ONLY"
internal const val INTEGRITY_NOT_VERIFIED = "NOT_VERIFIED"
internal const val INTEGRITY_ALGORITHM_NONE = "NONE"
internal const val UPLOAD_CONSENT_NOT_REQUESTED = "NOT_REQUESTED"
internal const val DEFAULT_DISPLAY_NAME = "Player"

internal class CompletedGameConflictException(gameId: String) : IllegalStateException(
    "Completed game '$gameId' conflicts with its immutable history",
)

private const val OPPONENT_TYPE_BOT = "BOT"
private const val RESULT_WIN = "WIN"
private const val RESULT_LOSS = "LOSS"
private const val TIME_CONTROL_CLOCK = "CLOCK"
private const val TIME_CONTROL_UNTIMED = "UNTIMED"
private const val STRENGTH_APPROXIMATE_ELO = "APPROXIMATE_ELO"
private const val STRENGTH_SKILL_LEVEL = "SKILL_LEVEL"
