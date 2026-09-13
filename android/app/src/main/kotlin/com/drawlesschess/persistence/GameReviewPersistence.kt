package com.drawlesschess.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.engine.CandidateEvidence
import com.drawlesschess.core.engine.DRAWLESS_ACCURACY_VERSION
import com.drawlesschess.core.engine.DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS
import com.drawlesschess.core.engine.GAME_REVIEW_MULTI_PV
import com.drawlesschess.core.engine.GameReviewScope
import com.drawlesschess.core.engine.GameReviewResult
import com.drawlesschess.core.engine.MoveEvidenceV2
import com.drawlesschess.core.engine.REVIEW_CLASSIFIER_VERSION
import com.drawlesschess.core.engine.REVIEW_CONSTRAINED_ROOT_POLICY_VERSION
import com.drawlesschess.core.engine.REVIEW_EVIDENCE_SCHEMA_VERSION
import com.drawlesschess.core.engine.REVIEW_EXPLANATION_VERSION
import com.drawlesschess.core.engine.REVIEW_RETAINED_PV_LENGTH
import com.drawlesschess.core.engine.REVIEW_SUMMARY_VERSION
import com.drawlesschess.core.engine.ReviewAnalysisProfile
import com.drawlesschess.core.engine.ReviewEvaluation
import com.drawlesschess.core.engine.ReviewEvidenceV2
import com.drawlesschess.core.engine.ReviewExplanationFacts
import com.drawlesschess.core.engine.ReviewFingerprints
import com.drawlesschess.core.engine.ReviewGradingPolicy
import com.drawlesschess.core.engine.ReviewLineOrigin
import com.drawlesschess.core.engine.ReviewScoreSource
import com.drawlesschess.core.engine.toGameReviewResult
import com.drawlesschess.engine.AndroidFairyEngineFactory
import com.drawlesschess.shared.SharedCheckpointCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.json.JSONArray

@Entity(
    tableName = "game_review",
    foreignKeys = [
        ForeignKey(
            entity = CompletedGameEntity::class,
            parentColumns = ["game_id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["completed_at_epoch_millis"])],
)
internal data class GameReviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "game_id")
    val gameId: String,
    @ColumnInfo(name = "evidence_schema_version")
    val evidenceSchemaVersion: Int,
    @ColumnInfo(name = "game_fingerprint")
    val gameFingerprint: String,
    @ColumnInfo(name = "rules_fingerprint")
    val rulesFingerprint: String,
    @ColumnInfo(name = "evidence_payload_json")
    val evidencePayloadJson: String,
    @ColumnInfo(name = "evidence_payload_sha256")
    val evidencePayloadSha256: String,
    @ColumnInfo(name = "evidence_cache_key")
    val evidenceCacheKey: String,
    @ColumnInfo(name = "engine_id")
    val engineId: String,
    @ColumnInfo(name = "engine_build")
    val engineBuild: String,
    @ColumnInfo(name = "engine_drawless_patch")
    val engineDrawlessPatch: Int,
    @ColumnInfo(name = "move_time_millis")
    val moveTimeMillis: Long,
    @ColumnInfo(name = "multi_pv")
    val multiPv: Int,
    @ColumnInfo(name = "constrained_root_policy_version")
    val constrainedRootPolicyVersion: Int,
    @ColumnInfo(name = "retained_pv_length")
    val retainedPvLength: Int,
    @ColumnInfo(name = "classifier_version")
    val classifierVersion: Int,
    @ColumnInfo(name = "grading_policy_version")
    val gradingPolicyVersion: Int,
    @ColumnInfo(name = "accuracy_version")
    val accuracyVersion: Int,
    @ColumnInfo(name = "summary_version")
    val summaryVersion: Int,
    @ColumnInfo(name = "explanation_version")
    val explanationVersion: Int,
    @ColumnInfo(name = "derived_review_key")
    val derivedReviewKey: String,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long,
) {
    fun hasSameImmutableFactsAs(other: GameReviewEntity): Boolean =
        copy(completedAtEpochMillis = 0L) == other.copy(completedAtEpochMillis = 0L)
}

internal enum class HistoricalReviewAvailability {
    READY,
    NOT_ANALYZED,
    STALE,
}

internal data class CompletedGameHistory(
    val gameId: String,
    val completedAtEpochMillis: Long,
    val completionSequence: Long,
    val recordFormatVersion: Int,
    val opponentStableId: String,
    val opponentExactElo: Int?,
    val initialFen: String,
    val moves: List<UciMove>,
    val rules: RulesContractV1,
    val playerSide: Side,
    val outcome: GameOutcome,
    val playerWon: Boolean,
)

/** No engine ran when the player made no decisions; canonical history is sufficient. */
internal fun CompletedGameHistory.emptyReviewOrNull(): GameReviewResult? {
    val firstMover = ChessPosition.fromFen(initialFen).sideToMove
    if (moves.indices.any { index ->
            (if (index % 2 == 0) firstMover else firstMover.opposite()) == playerSide
        }) return null
    return GameReviewResult(
        gameId = gameId,
        initialFen = initialFen,
        rules = rules,
        outcome = outcome,
        moves = emptyList(),
        engine = null,
        scope = GameReviewScope.PlayerMoves(playerSide),
        gameMoves = moves,
    )
}

internal data class GameHistoryEntry(
    val game: CompletedGameHistory,
    val reviewAvailability: HistoricalReviewAvailability,
)

internal data class HistoricalGameReview(
    val game: CompletedGameHistory,
    val review: GameReviewResult?,
    val reviewAvailability: HistoricalReviewAvailability,
)

internal object CompletedGameHistoryCodec {
    fun decode(entity: CompletedGameEntity): CompletedGameHistory {
        require(entity.recordFormatVersion == COMPLETED_GAME_FORMAT_VERSION) {
            "Unsupported completed-game format ${entity.recordFormatVersion}"
        }
        val movesArray = JSONArray(entity.movesJson)
        val moves = List(movesArray.length()) { index ->
            UciMove(movesArray.getString(index))
        }
        require(moves.size == entity.moveCount) { "Completed-game move count does not match its payload" }
        val rules = SharedCheckpointCodec.decodeRulesForHistory(entity.rulesJson)
        require(rules.schemaVersion == entity.rulesSchemaVersion)
        require(rules.preset.name == entity.rulesPreset)
        val playerSide = enumValueOf<Side>(entity.playerSide)
        val outcome = GameOutcome(
            winner = enumValueOf(entity.winnerSide),
            reason = enumValueOf<EndReason>(entity.endReason),
        )
        require((entity.result == "WIN") == (outcome.winner == playerSide)) {
            "Completed-game result does not match its recorded sides"
        }
        require(entity.result == "WIN" || entity.result == "LOSS")
        val normalizedFen = ChessPosition.fromFen(entity.initialFen).fen()
        ChessAdapter.replay(normalizedFen, moves)
        return CompletedGameHistory(
            gameId = entity.gameId,
            completedAtEpochMillis = entity.completedAtEpochMillis,
            completionSequence = entity.completionSequence,
            recordFormatVersion = entity.recordFormatVersion,
            opponentStableId = entity.opponentStableId,
            opponentExactElo = entity.opponentExactElo,
            initialFen = normalizedFen,
            moves = moves,
            rules = rules,
            playerSide = playerSide,
            outcome = outcome,
            playerWon = outcome.winner == playerSide,
        )
    }
}

internal object GameReviewRecordFactory {
    fun from(
        result: GameReviewResult,
        completedGame: CompletedGameHistory,
        completedAtEpochMillis: Long,
    ): GameReviewEntity {
        require(result.gameId == completedGame.gameId)
        require(result.initialFen == completedGame.initialFen)
        require(result.gameMoves == completedGame.moves)
        require(result.rules == completedGame.rules)
        require(result.outcome == completedGame.outcome)
        val evidence = ReviewEvidenceV2.from(result)
        require(evidence.scopeSide == completedGame.playerSide)
        require(
            evidence.gameFingerprint == ReviewFingerprints.game(
                initialFen = completedGame.initialFen,
                moves = completedGame.moves,
                rules = completedGame.rules,
                outcome = completedGame.outcome,
                recordFormatVersion = completedGame.recordFormatVersion,
            ),
        ) { "Completed Review fingerprint does not match history" }
        val payload = GameReviewPayloadCodec.encode(evidence)
        val payloadDigest = ReviewFingerprints.payloadDigest(payload.encodeToByteArray())
        return GameReviewEntity(
            gameId = result.gameId,
            evidenceSchemaVersion = evidence.schemaVersion,
            gameFingerprint = evidence.gameFingerprint,
            rulesFingerprint = evidence.rulesFingerprint,
            evidencePayloadJson = payload,
            evidencePayloadSha256 = payloadDigest,
            evidenceCacheKey = ReviewFingerprints.evidenceCacheKey(evidence),
            engineId = evidence.engine.id,
            engineBuild = evidence.engine.build,
            engineDrawlessPatch = evidence.engine.drawlessPatch,
            moveTimeMillis = evidence.profile.moveTimeMillis,
            multiPv = evidence.profile.multiPv,
            constrainedRootPolicyVersion = evidence.profile.constrainedRootPolicyVersion,
            retainedPvLength = evidence.profile.retainedPvLength,
            classifierVersion = REVIEW_CLASSIFIER_VERSION,
            gradingPolicyVersion = ReviewGradingPolicy.CURRENT.version,
            accuracyVersion = DRAWLESS_ACCURACY_VERSION,
            summaryVersion = REVIEW_SUMMARY_VERSION,
            explanationVersion = REVIEW_EXPLANATION_VERSION,
            derivedReviewKey = ReviewFingerprints.derivedReviewKey(payloadDigest),
            completedAtEpochMillis = completedAtEpochMillis,
        )
    }
}

internal object GameReviewEntityDecoder {
    fun availability(
        entity: GameReviewEntity?,
        game: CompletedGameHistory,
    ): HistoricalReviewAvailability = when {
        game.emptyReviewOrNull() != null -> HistoricalReviewAvailability.READY
        entity == null -> HistoricalReviewAvailability.NOT_ANALYZED
        decode(entity, game) == null -> HistoricalReviewAvailability.STALE
        else -> HistoricalReviewAvailability.READY
    }

    fun decode(entity: GameReviewEntity, game: CompletedGameHistory): GameReviewResult? =
        runCatching {
            require(entity.gameId == game.gameId)
            require(entity.evidenceSchemaVersion == REVIEW_EVIDENCE_SCHEMA_VERSION)
            val payloadDigest = ReviewFingerprints.payloadDigest(
                entity.evidencePayloadJson.encodeToByteArray(),
            )
            require(payloadDigest == entity.evidencePayloadSha256)
            val evidence = GameReviewPayloadCodec.decode(entity.evidencePayloadJson)
            require(evidence.scopeSide == game.playerSide)
            require(evidence.gameFingerprint == entity.gameFingerprint)
            require(evidence.rulesFingerprint == entity.rulesFingerprint)
            require(ReviewFingerprints.evidenceCacheKey(evidence) == entity.evidenceCacheKey)
            require(evidence.engine == EngineIdentity(
                entity.engineId,
                entity.engineBuild,
                entity.engineDrawlessPatch,
            ))
            require(evidence.profile == ReviewAnalysisProfile(
                moveTimeMillis = entity.moveTimeMillis,
                multiPv = entity.multiPv,
                constrainedRootPolicyVersion = entity.constrainedRootPolicyVersion,
                retainedPvLength = entity.retainedPvLength,
            ))
            require(evidence.profile == ReviewAnalysisProfile(
                DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
                GAME_REVIEW_MULTI_PV,
            ))
            require(AndroidFairyEngineFactory.acceptsPersistedReviewEvidence(evidence.engine))
            // Derived keys and versions never invalidate expensive evidence. Current derived models
            // are rebuilt below from the compatible evidence payload instead.
            evidence.toGameReviewResult(
                gameId = game.gameId,
                initialFen = game.initialFen,
                gameMoves = game.moves,
                rules = game.rules,
                outcome = game.outcome,
                recordFormatVersion = game.recordFormatVersion,
            )
        }.getOrNull()
}

internal object GameReviewPayloadCodec {
    private const val PAYLOAD_FORMAT_VERSION = 1

    fun encode(evidence: ReviewEvidenceV2): String = buildJsonObject {
        put("formatVersion", PAYLOAD_FORMAT_VERSION)
        put("schemaVersion", evidence.schemaVersion)
        put("gameFingerprint", evidence.gameFingerprint)
        put("rulesFingerprint", evidence.rulesFingerprint)
        put("scopeSide", evidence.scopeSide.name)
        put("engine", buildJsonObject {
            put("id", evidence.engine.id)
            put("build", evidence.engine.build)
            put("drawlessPatch", evidence.engine.drawlessPatch)
        })
        put("profile", buildJsonObject {
            put("moveTimeMillis", evidence.profile.moveTimeMillis)
            put("multiPv", evidence.profile.multiPv)
            put("constrainedRootPolicyVersion", evidence.profile.constrainedRootPolicyVersion)
            put("retainedPvLength", evidence.profile.retainedPvLength)
        })
        put("moves", buildJsonArray { evidence.moves.forEach { add(encodeMove(it)) } })
    }.toString()

    fun decode(payload: String): ReviewEvidenceV2 {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredInt("formatVersion") == PAYLOAD_FORMAT_VERSION)
        val engine = root.requiredObject("engine")
        val profile = root.requiredObject("profile")
        return ReviewEvidenceV2(
            schemaVersion = root.requiredInt("schemaVersion"),
            gameFingerprint = root.requiredString("gameFingerprint"),
            rulesFingerprint = root.requiredString("rulesFingerprint"),
            scopeSide = enumValueOf(root.requiredString("scopeSide")),
            engine = EngineIdentity(
                id = engine.requiredString("id"),
                build = engine.requiredString("build"),
                drawlessPatch = engine.requiredInt("drawlessPatch"),
            ),
            profile = ReviewAnalysisProfile(
                moveTimeMillis = profile.requiredLong("moveTimeMillis"),
                multiPv = profile.requiredInt("multiPv"),
                constrainedRootPolicyVersion = profile.requiredInt("constrainedRootPolicyVersion"),
                retainedPvLength = profile.requiredInt("retainedPvLength"),
            ),
            moves = root.requiredArray("moves").map { decodeMove(it.jsonObject) },
        )
    }

    private fun encodeMove(move: MoveEvidenceV2): JsonObject = buildJsonObject {
        put("ply", move.ply)
        put("mover", move.mover.name)
        put("playedMove", move.playedMove.value)
        put("positionKey", move.positionKey)
        putNullable("authoritativeOutcomeAfter", move.authoritativeOutcomeAfter?.let(::encodeOutcome))
        put("best", encodeCandidate(move.best))
        put("played", encodeCandidate(move.played))
        put("candidates", buildJsonArray { move.candidates.forEach { add(encodeCandidate(it)) } })
        put("legalMoveCount", move.legalMoveCount)
        put("facts", encodeFacts(move.facts))
    }

    private fun decodeMove(value: JsonObject): MoveEvidenceV2 = MoveEvidenceV2(
        ply = value.requiredInt("ply"),
        mover = enumValueOf(value.requiredString("mover")),
        playedMove = UciMove(value.requiredString("playedMove")),
        positionKey = value.requiredString("positionKey"),
        authoritativeOutcomeAfter = value.optionalObject("authoritativeOutcomeAfter")?.let(::decodeOutcome),
        best = decodeCandidate(value.requiredObject("best")),
        played = decodeCandidate(value.requiredObject("played")),
        candidates = value.requiredArray("candidates").map { decodeCandidate(it.jsonObject) },
        legalMoveCount = value.requiredInt("legalMoveCount"),
        facts = decodeFacts(value.requiredObject("facts")),
    )

    private fun encodeCandidate(candidate: CandidateEvidence): JsonObject = buildJsonObject {
        put("rank", candidate.rank)
        put("rootMove", candidate.rootMove.value)
        put("evaluation", encodeEvaluation(candidate.evaluation))
        put("expectedPoints", candidate.expectedPoints)
        put("source", candidate.source.name)
        put("bound", candidate.bound.name)
        putNullable("depth", candidate.depth?.let(::JsonPrimitive))
        putNullable("nodes", candidate.nodes?.let(::JsonPrimitive))
        put("principalVariation", buildJsonArray {
            candidate.principalVariation.forEach { add(JsonPrimitive(it.value)) }
        })
        put("origin", candidate.origin.name)
    }

    private fun decodeCandidate(value: JsonObject): CandidateEvidence = CandidateEvidence(
        rank = value.requiredInt("rank"),
        rootMove = UciMove(value.requiredString("rootMove")),
        evaluation = decodeEvaluation(value.requiredObject("evaluation")),
        expectedPoints = value.requiredDouble("expectedPoints"),
        source = enumValueOf<ReviewScoreSource>(value.requiredString("source")),
        bound = enumValueOf<EngineScoreBound>(value.requiredString("bound")),
        depth = value.optionalInt("depth"),
        nodes = value.optionalLong("nodes"),
        principalVariation = value.requiredArray("principalVariation").map {
            UciMove(it.jsonPrimitive.content)
        },
        origin = enumValueOf<ReviewLineOrigin>(value.requiredString("origin")),
    )

    private fun encodeEvaluation(evaluation: ReviewEvaluation): JsonObject = when (evaluation) {
        is ReviewEvaluation.Centipawns -> buildJsonObject {
            put("kind", "CENTIPAWNS")
            put("value", evaluation.value)
        }
        is ReviewEvaluation.Mate -> buildJsonObject {
            put("kind", "MATE")
            put("value", evaluation.mateIn)
        }
        is ReviewEvaluation.Terminal -> buildJsonObject {
            put("kind", "TERMINAL")
            put("winner", evaluation.winner.name)
        }
    }

    private fun decodeEvaluation(value: JsonObject): ReviewEvaluation =
        when (value.requiredString("kind")) {
            "CENTIPAWNS" -> ReviewEvaluation.Centipawns(value.requiredInt("value"))
            "MATE" -> ReviewEvaluation.Mate(value.requiredInt("value"))
            "TERMINAL" -> ReviewEvaluation.Terminal(enumValueOf(value.requiredString("winner")))
            else -> error("Unknown Review evaluation kind")
        }

    private fun encodeFacts(facts: ReviewExplanationFacts): JsonObject = buildJsonObject {
        put("capture", facts.capture)
        put("gaveCheck", facts.gaveCheck)
        put("forcedMove", facts.forcedMove)
        put("materialSwingForMover", facts.materialSwingForMover)
        putNullable("terminalReason", facts.terminalReason?.name?.let(::JsonPrimitive))
        putNullable("terminalWinner", facts.terminalWinner?.name?.let(::JsonPrimitive))
    }

    private fun decodeFacts(value: JsonObject): ReviewExplanationFacts = ReviewExplanationFacts(
        capture = value.requiredBoolean("capture"),
        gaveCheck = value.requiredBoolean("gaveCheck"),
        forcedMove = value.requiredBoolean("forcedMove"),
        materialSwingForMover = value.requiredInt("materialSwingForMover"),
        terminalReason = value.optionalString("terminalReason")?.let { enumValueOf(it) },
        terminalWinner = value.optionalString("terminalWinner")?.let { enumValueOf(it) },
    )

    private fun encodeOutcome(outcome: GameOutcome): JsonObject = buildJsonObject {
        put("winner", outcome.winner.name)
        put("loser", outcome.loser.name)
        put("reason", outcome.reason.name)
    }

    private fun decodeOutcome(value: JsonObject): GameOutcome = GameOutcome(
        winner = enumValueOf(value.requiredString("winner")),
        loser = enumValueOf(value.requiredString("loser")),
        reason = enumValueOf(value.requiredString("reason")),
    )
}

private fun JsonObject.requiredElement(name: String): JsonElement =
    requireNotNull(this[name]) { "Missing Review payload field '$name'" }

private fun JsonObject.requiredObject(name: String): JsonObject = requiredElement(name).jsonObject
private fun JsonObject.requiredArray(name: String): JsonArray = requiredElement(name).jsonArray
private fun JsonObject.requiredString(name: String): String = requiredElement(name).jsonPrimitive.content
private fun JsonObject.requiredInt(name: String): Int = requiredElement(name).jsonPrimitive.int
private fun JsonObject.requiredLong(name: String): Long = requiredElement(name).jsonPrimitive.long
private fun JsonObject.requiredDouble(name: String): Double = requiredElement(name).jsonPrimitive.double
private fun JsonObject.requiredBoolean(name: String): Boolean =
    requiredElement(name).jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.optionalObject(name: String): JsonObject? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> value.jsonObject
}

private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> value.jsonPrimitive.content
}

private fun JsonObject.optionalInt(name: String): Int? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> value.jsonPrimitive.int
}

private fun JsonObject.optionalLong(name: String): Long? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> value.jsonPrimitive.long
}

private fun JsonObjectBuilder.putNullable(name: String, value: JsonElement?) {
    put(name, value ?: JsonNull)
}

internal class GameReviewConflictException(gameId: String) : IllegalStateException(
    "Completed Review '$gameId' conflicts with its immutable evidence",
)
