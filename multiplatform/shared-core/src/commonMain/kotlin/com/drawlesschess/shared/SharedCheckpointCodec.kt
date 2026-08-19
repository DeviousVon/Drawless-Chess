package com.drawlesschess.shared

import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.BareKingPolicy
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.EngineWdl
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameMode
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.MaterialValues
import com.drawlesschess.core.PrincipalVariation
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.StalematePolicy
import com.drawlesschess.core.TimeControl
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorClock
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.MoveClockSnapshot
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.GameReviewAdjacentKey
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewRootKey
import com.drawlesschess.core.engine.REVIEW_ANALYSIS_VERSION
import com.drawlesschess.core.engine.REVIEW_EVIDENCE_SCHEMA_VERSION
import com.drawlesschess.core.engine.SeededGameReviewAdjacentRoot
import com.drawlesschess.core.engine.SeededGameReviewRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Platform-neutral form of Android checkpoint payload format 1.
 *
 * Keeping this codec in common code lets Apple storage persist the same immutable rules,
 * clock history and assistance facts as Room without exposing platform JSON types.
 */
object SharedCheckpointCodec {
    const val FORMAT_VERSION: Int = 1
    private const val REVIEW_PREFETCH_FORMAT_VERSION: Int = 1

    fun encode(checkpoint: CoordinatorCheckpoint): String = buildJsonObject {
        put("formatVersion", FORMAT_VERSION)
        put("revision", checkpoint.revision)
        put("config", encodeConfig(checkpoint.config))
        put("moves", buildJsonArray { checkpoint.moves.forEach { add(JsonPrimitive(it.value)) } })
        put("currentFen", checkpoint.currentFen)
        putNullable("outcome", checkpoint.outcome?.let(::encodeOutcome))
        put("clock", encodeClock(checkpoint.clock))
        put("moveClocks", buildJsonArray {
            checkpoint.moveClocks.forEach { add(encodeMoveClock(it)) }
        })
        put("assistance", encodeAssistance(checkpoint.assistance))
        put("reviewPrefetch", encodeReviewPrefetch(checkpoint))
    }.toString()

    fun decode(payloadJson: String): CoordinatorCheckpoint {
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        require(payload.requiredInt("formatVersion") == FORMAT_VERSION) {
            "Unsupported checkpoint payload format"
        }
        val config = decodeConfig(payload.requiredObject("config"))
        val moves = payload.requiredArray("moves").map { UciMove(it.jsonPrimitive.content) }
        val outcome = payload.requiredNullableObject("outcome")?.let(::decodeOutcome)
        val (reviewRoots, reviewAdjacentRoots) = decodeReviewPrefetch(
            value = payload["reviewPrefetch"] as? JsonObject,
            config = config,
            moves = moves,
            outcome = outcome,
        )
        return CoordinatorCheckpoint(
            revision = payload.requiredLong("revision"),
            config = config,
            moves = moves,
            currentFen = payload.requiredString("currentFen"),
            outcome = outcome,
            clock = decodeClock(payload.requiredObject("clock")),
            moveClocks = payload.requiredArray("moveClocks").map { decodeMoveClock(it.jsonObject) },
            assistance = decodeAssistance(payload.requiredObject("assistance")),
            reviewPrefetchRoots = reviewRoots,
            reviewPrefetchAdjacentRoots = reviewAdjacentRoots,
        )
    }

    private fun encodeReviewPrefetch(checkpoint: CoordinatorCheckpoint): JsonObject =
        buildJsonObject {
            put("formatVersion", REVIEW_PREFETCH_FORMAT_VERSION)
            put("evidenceSchemaVersion", REVIEW_EVIDENCE_SCHEMA_VERSION)
            put("analysisVersion", REVIEW_ANALYSIS_VERSION)
            put("roots", buildJsonArray {
                checkpoint.reviewPrefetchRoots.forEach { seed ->
                    add(buildJsonObject {
                        put("key", encodeReviewRootKey(seed.key))
                        put("response", encodeEngineResponse(seed.response))
                    })
                }
            })
            put("adjacentRoots", buildJsonArray {
                checkpoint.reviewPrefetchAdjacentRoots.forEach { seed ->
                    add(buildJsonObject {
                        put("key", encodeReviewAdjacentKey(seed.key))
                        put("response", encodeEngineResponse(seed.response))
                    })
                }
            })
        }

    /**
     * Review evidence is only a cache. Unknown versions or malformed/stale entries are ignored
     * without making the playable Apple checkpoint unavailable.
     */
    private fun decodeReviewPrefetch(
        value: JsonObject?,
        config: GameConfig,
        moves: List<UciMove>,
        outcome: GameOutcome?,
    ): Pair<List<SeededGameReviewRoot>, List<SeededGameReviewAdjacentRoot>> {
        if (value == null ||
            value.optionalInt("formatVersion") != REVIEW_PREFETCH_FORMAT_VERSION ||
            value.optionalInt("evidenceSchemaVersion") != REVIEW_EVIDENCE_SCHEMA_VERSION ||
            value.optionalInt("analysisVersion") != REVIEW_ANALYSIS_VERSION
        ) {
            return emptyList<SeededGameReviewRoot>() to emptyList()
        }

        val expectedRootsByPly = runCatching {
            GameReviewPlanner.playerPlan(
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                playerSide = config.humanSide,
            ).roots.associateByTo(linkedMapOf()) { root -> root.ply }.also { roots ->
                if (outcome == null) {
                    val current = GameReviewPlanner.playerRoot(
                        requestId = "${config.gameId}-decode-current-review",
                        gameId = config.gameId,
                        initialFen = config.initialFen,
                        moves = moves,
                        rules = config.rules,
                    )
                    if (ChessPosition.fromFen(current.key.positionFen).sideToMove == config.humanSide) {
                        roots[current.ply] = current
                    }
                }
            }
        }.getOrElse { return emptyList<SeededGameReviewRoot>() to emptyList() }

        val roots = mutableListOf<SeededGameReviewRoot>()
        val seenRootKeys = linkedSetOf<GameReviewRootKey>()
        value.optionalArray("roots").forEach { raw ->
            val seed = runCatching {
                val entry = raw.jsonObject
                val keyValue = entry.requiredObject("key")
                val expected = expectedRootsByPly[keyValue.requiredInt("ply")]
                    ?: return@runCatching null
                if (!matchesReviewRootKey(keyValue, expected.key)) return@runCatching null
                val response = decodeEngineResponse(entry.requiredObject("response"))
                GameReviewPlanner.playerRoot(
                    requestId = response.requestId,
                    gameId = config.gameId,
                    initialFen = config.initialFen,
                    moves = moves.take(expected.ply - 1),
                    rules = config.rules,
                ).takeIf { root -> root.key == expected.key }?.seed(response)
            }.getOrNull() ?: return@forEach
            if (!seenRootKeys.add(seed.key)) {
                return emptyList<SeededGameReviewRoot>() to emptyList()
            }
            roots += seed
        }

        val adjacentRoots = mutableListOf<SeededGameReviewAdjacentRoot>()
        val seenAdjacentKeys = linkedSetOf<GameReviewAdjacentKey>()
        value.optionalArray("adjacentRoots").forEach { raw ->
            val seed = runCatching {
                val entry = raw.jsonObject
                val keyValue = entry.requiredObject("key")
                val rootKeyValue = keyValue.requiredObject("rootKey")
                val expectedRoot = expectedRootsByPly[rootKeyValue.requiredInt("ply")]
                    ?: return@runCatching null
                val playedMove = UciMove(keyValue.requiredString("playedMove"))
                if (moves.getOrNull(expectedRoot.ply - 1) != playedMove ||
                    !matchesReviewRootKey(rootKeyValue, expectedRoot.key)
                ) {
                    return@runCatching null
                }
                val response = decodeEngineResponse(entry.requiredObject("response"))
                GameReviewPlanner.adjacentRoot(
                    requestId = response.requestId,
                    root = expectedRoot,
                    playedMove = playedMove,
                ).takeIf { adjacent -> matchesReviewAdjacentKey(keyValue, adjacent.key) }
                    ?.seed(response)
            }.getOrNull() ?: return@forEach
            if (!seenAdjacentKeys.add(seed.key)) {
                return emptyList<SeededGameReviewRoot>() to emptyList()
            }
            adjacentRoots += seed
        }

        val identities = (roots.map { it.response.engine } +
            adjacentRoots.map { it.response.engine }).distinct()
        return if (identities.size <= 1) {
            roots.sortedBy { it.key.ply } to adjacentRoots.sortedBy { it.key.rootKey.ply }
        } else {
            emptyList<SeededGameReviewRoot>() to emptyList()
        }
    }

    private fun encodeReviewRootKey(key: GameReviewRootKey): JsonObject = buildJsonObject {
        put("evidenceSchemaVersion", key.evidenceSchemaVersion)
        put("analysisVersion", key.analysisVersion)
        put("gameId", key.gameId)
        put("ply", key.ply)
        put("normalizedInitialFen", key.normalizedInitialFen)
        put("movesBefore", buildJsonArray { key.movesBefore.forEach { add(JsonPrimitive(it.value)) } })
        put("rules", encodeRules(key.rules))
        put("positionId", key.positionId)
        put("positionFen", key.positionFen)
        put("strength", encodeEngineStrength(key.strength))
        put("limits", buildJsonObject {
            put("moveTimeMillis", key.limits.moveTimeMillis)
            put("multiPv", key.limits.multiPv)
        })
        put("purpose", key.purpose.name)
    }

    private fun encodeReviewAdjacentKey(key: GameReviewAdjacentKey): JsonObject = buildJsonObject {
        put("rootKey", encodeReviewRootKey(key.rootKey))
        put("playedMove", key.playedMove.value)
        put("positionId", key.positionId)
        put("positionFen", key.positionFen)
    }

    private fun matchesReviewRootKey(value: JsonObject, key: GameReviewRootKey): Boolean =
        runCatching {
            val limits = value.requiredObject("limits")
            value.requiredInt("evidenceSchemaVersion") == key.evidenceSchemaVersion &&
                value.requiredInt("analysisVersion") == key.analysisVersion &&
                value.requiredString("gameId") == key.gameId &&
                value.requiredInt("ply") == key.ply &&
                value.requiredString("normalizedInitialFen") == key.normalizedInitialFen &&
                value.requiredArray("movesBefore").map { UciMove(it.jsonPrimitive.content) } ==
                    key.movesBefore &&
                decodeRules(value.requiredObject("rules")) == key.rules &&
                value.requiredString("positionId") == key.positionId &&
                value.requiredString("positionFen") == key.positionFen &&
                decodeEngineStrength(value.requiredObject("strength")) == key.strength &&
                EngineLimits(
                    limits.requiredLong("moveTimeMillis"),
                    limits.requiredInt("multiPv"),
                ) == key.limits &&
                enumValueOf<EnginePurpose>(value.requiredString("purpose")) == key.purpose
        }.getOrDefault(false)

    private fun matchesReviewAdjacentKey(
        value: JsonObject,
        key: GameReviewAdjacentKey,
    ): Boolean = runCatching {
        matchesReviewRootKey(value.requiredObject("rootKey"), key.rootKey) &&
            UciMove(value.requiredString("playedMove")) == key.playedMove &&
            value.requiredString("positionId") == key.positionId &&
            value.requiredString("positionFen") == key.positionFen
    }.getOrDefault(false)

    private fun encodeEngineResponse(value: EngineResponse): JsonObject = buildJsonObject {
        put("requestId", value.requestId)
        put("gameId", value.gameId)
        put("positionId", value.positionId)
        put("bestMove", value.bestMove.value)
        putNullable("ponderMove", value.ponderMove?.value?.let(::JsonPrimitive))
        put("depth", value.depth)
        put("nodes", value.nodes)
        put("engine", buildJsonObject {
            put("id", value.engine.id)
            put("build", value.engine.build)
            put("patch", value.engine.drawlessPatch)
        })
        put("variations", buildJsonArray { value.variations.forEach { add(encodeVariation(it)) } })
    }

    private fun decodeEngineResponse(value: JsonObject): EngineResponse {
        val identity = value.requiredObject("engine")
        return EngineResponse(
            requestId = value.requiredString("requestId"),
            gameId = value.requiredString("gameId"),
            positionId = value.requiredString("positionId"),
            bestMove = UciMove(value.requiredString("bestMove")),
            ponderMove = value.requiredNullableString("ponderMove")?.let(::UciMove),
            depth = value.requiredInt("depth"),
            nodes = value.requiredLong("nodes"),
            variations = value.requiredArray("variations").map { decodeVariation(it.jsonObject) },
            engine = EngineIdentity(
                id = identity.requiredString("id"),
                build = identity.requiredString("build"),
                drawlessPatch = identity.requiredInt("patch"),
            ),
        )
    }

    private fun encodeVariation(value: PrincipalVariation): JsonObject = buildJsonObject {
        putNullable("cp", value.scoreCentipawns?.let(::JsonPrimitive))
        putNullable("mate", value.mateIn?.let(::JsonPrimitive))
        put("moves", buildJsonArray { value.moves.forEach { add(JsonPrimitive(it.value)) } })
        put("rank", value.rank)
        put("bound", value.bound.name)
        putNullable("depth", value.depth?.let(::JsonPrimitive))
        put("evidence", value.evidenceAvailable)
        value.wdl?.let { wdl ->
            put("wdl", buildJsonObject {
                put("wins", wdl.wins)
                put("draws", wdl.draws)
                put("losses", wdl.losses)
            })
        }
    }

    private fun decodeVariation(value: JsonObject): PrincipalVariation = PrincipalVariation(
        scoreCentipawns = value.requiredNullableInt("cp"),
        mateIn = value.requiredNullableInt("mate"),
        moves = value.requiredArray("moves").map { UciMove(it.jsonPrimitive.content) },
        rank = value.requiredInt("rank"),
        bound = enumValueOf<EngineScoreBound>(value.requiredString("bound")),
        wdl = (value["wdl"] as? JsonObject)?.let { wdl ->
            EngineWdl(
                wins = wdl.requiredInt("wins"),
                draws = wdl.requiredInt("draws"),
                losses = wdl.requiredInt("losses"),
            )
        },
        depth = value.requiredNullableInt("depth"),
        evidenceAvailable = value.requiredBoolean("evidence"),
    )

    private fun encodeConfig(config: GameConfig): JsonObject = buildJsonObject {
        put("gameId", config.gameId)
        put("initialFen", config.initialFen)
        put("rules", encodeRules(config.rules))
        put("mode", config.mode.name)
        put("timeControl", encodeTimeControl(config.timeControl))
        put("humanSide", config.humanSide.name)
        put("engineStrength", encodeEngineStrength(config.engineStrength))
        putNullable("opponentLevelId", config.opponentLevelId?.let(::JsonPrimitive))
        put("engineLimits", buildJsonObject {
            put("moveTimeMillis", config.engineLimits.moveTimeMillis)
            put("multiPv", config.engineLimits.multiPv)
        })
    }

    private fun decodeConfig(value: JsonObject): GameConfig {
        val limits = value.requiredObject("engineLimits")
        val engineStrength = decodeEngineStrength(value.requiredObject("engineStrength"))
        val opponentLevelId = if (value.containsKey("opponentLevelId")) {
            value.requiredNullableString("opponentLevelId")
        } else {
            (engineStrength as? EngineStrength.ApproximateElo)?.elo
                ?.let(BotDifficultyCatalog::legacyLevelIdForElo)
        }
        return GameConfig(
            gameId = value.requiredString("gameId"),
            initialFen = value.requiredString("initialFen"),
            rules = decodeRules(value.requiredObject("rules")),
            mode = enumValueOf(value.requiredString("mode")),
            timeControl = decodeTimeControl(value.requiredObject("timeControl")),
            humanSide = enumValueOf(value.requiredString("humanSide")),
            engineStrength = engineStrength,
            engineLimits = EngineLimits(
                moveTimeMillis = limits.requiredLong("moveTimeMillis"),
                multiPv = limits.requiredInt("multiPv"),
            ),
            opponentLevelId = opponentLevelId,
        )
    }

    private fun encodeRules(rules: RulesContractV1): JsonObject = buildJsonObject {
        put("schemaVersion", rules.schemaVersion)
        put("preset", rules.preset.name)
        put("stalemate", rules.stalemate.name)
        put("deadPosition", rules.deadPosition.name)
        put("bareKing", rules.bareKing.name)
        put("fiftyMove", rules.fiftyMove.name)
        put("repetitionThreshold", rules.repetitionThreshold)
        put("completingPlayerLosesRepetition", rules.completingPlayerLosesRepetition)
        put("forcedRepetitionException", rules.forcedRepetitionException)
        put("materialValues", buildJsonObject {
            put("pawn", rules.materialValues.pawn)
            put("knight", rules.materialValues.knight)
            put("bishop", rules.materialValues.bishop)
            put("rook", rules.materialValues.rook)
            put("queen", rules.materialValues.queen)
        })
    }

    private fun decodeRules(value: JsonObject): RulesContractV1 {
        require(value.requiredInt("schemaVersion") == 1) { "Unsupported rules schema" }
        val material = value.requiredObject("materialValues")
        return RulesContractV1(
            preset = enumValueOf(value.requiredString("preset")),
            stalemate = enumValueOf<StalematePolicy>(value.requiredString("stalemate")),
            deadPosition = enumValueOf<DeadPositionPolicy>(value.requiredString("deadPosition")),
            fiftyMove = enumValueOf<FiftyMovePolicy>(value.requiredString("fiftyMove")),
            repetitionThreshold = value.requiredInt("repetitionThreshold"),
            completingPlayerLosesRepetition = value.requiredBoolean("completingPlayerLosesRepetition"),
            forcedRepetitionException = value.requiredBoolean("forcedRepetitionException"),
            materialValues = MaterialValues(
                pawn = material.requiredInt("pawn"),
                knight = material.requiredInt("knight"),
                bishop = material.requiredInt("bishop"),
                rook = material.requiredInt("rook"),
                queen = material.requiredInt("queen"),
            ),
            bareKing = if (value.containsKey("bareKing")) {
                enumValueOf<BareKingPolicy>(value.requiredString("bareKing"))
            } else {
                BareKingPolicy.CONTINUE
            },
        )
    }

    private fun encodeTimeControl(value: TimeControl): JsonObject = when (value) {
        TimeControl.Untimed -> buildJsonObject { put("kind", "UNTIMED") }
        is TimeControl.Clock -> buildJsonObject {
            put("kind", "CLOCK")
            put("initialMillis", value.initialMillis)
            put("incrementMillis", value.incrementMillis)
        }
    }

    private fun decodeTimeControl(value: JsonObject): TimeControl = when (value.requiredString("kind")) {
        "UNTIMED" -> TimeControl.Untimed
        "CLOCK" -> TimeControl.Clock(
            initialMillis = value.requiredLong("initialMillis"),
            incrementMillis = value.requiredLong("incrementMillis"),
        )
        else -> error("Unknown time-control kind")
    }

    private fun encodeEngineStrength(value: EngineStrength): JsonObject = when (value) {
        is EngineStrength.ApproximateElo -> buildJsonObject {
            put("kind", "APPROXIMATE_ELO")
            put("value", value.elo)
        }
        is EngineStrength.SkillLevel -> buildJsonObject {
            put("kind", "SKILL_LEVEL")
            put("value", value.level)
        }
    }

    private fun decodeEngineStrength(value: JsonObject): EngineStrength =
        when (value.requiredString("kind")) {
            "APPROXIMATE_ELO" -> EngineStrength.ApproximateElo(value.requiredInt("value"))
            "SKILL_LEVEL" -> EngineStrength.SkillLevel(value.requiredInt("value"))
            else -> error("Unknown engine-strength kind")
        }

    private fun encodeOutcome(value: GameOutcome): JsonObject = buildJsonObject {
        put("winner", value.winner.name)
        put("loser", value.loser.name)
        put("reason", value.reason.name)
    }

    private fun decodeOutcome(value: JsonObject): GameOutcome = GameOutcome(
        winner = enumValueOf(value.requiredString("winner")),
        loser = enumValueOf(value.requiredString("loser")),
        reason = enumValueOf<EndReason>(value.requiredString("reason")),
    )

    private fun encodeClock(value: CoordinatorClock): JsonObject = buildJsonObject {
        putNullable("whiteRemainingMillis", value.whiteRemainingMillis?.let(::JsonPrimitive))
        putNullable("blackRemainingMillis", value.blackRemainingMillis?.let(::JsonPrimitive))
        putNullable("runningSide", value.runningSide?.name?.let(::JsonPrimitive))
        putNullable("startedAtMonotonicMillis", value.startedAtMonotonicMillis?.let(::JsonPrimitive))
        putNullable("startedAtEpochMillis", value.startedAtEpochMillis?.let(::JsonPrimitive))
        put("paused", value.paused)
    }

    private fun decodeClock(value: JsonObject): CoordinatorClock = CoordinatorClock(
        whiteRemainingMillis = value.requiredNullableLong("whiteRemainingMillis"),
        blackRemainingMillis = value.requiredNullableLong("blackRemainingMillis"),
        runningSide = value.requiredNullableString("runningSide")?.let { enumValueOf<Side>(it) },
        startedAtMonotonicMillis = value.requiredNullableLong("startedAtMonotonicMillis"),
        startedAtEpochMillis = value.requiredNullableLong("startedAtEpochMillis"),
        paused = value.requiredBoolean("paused"),
    )

    private fun encodeMoveClock(value: MoveClockSnapshot): JsonObject = buildJsonObject {
        put("ply", value.ply)
        putNullable("whiteRemainingMillis", value.whiteRemainingMillis?.let(::JsonPrimitive))
        putNullable("blackRemainingMillis", value.blackRemainingMillis?.let(::JsonPrimitive))
    }

    private fun decodeMoveClock(value: JsonObject): MoveClockSnapshot = MoveClockSnapshot(
        ply = value.requiredInt("ply"),
        whiteRemainingMillis = value.requiredNullableLong("whiteRemainingMillis"),
        blackRemainingMillis = value.requiredNullableLong("blackRemainingMillis"),
    )

    private fun encodeAssistance(value: AssistanceCounts): JsonObject = buildJsonObject {
        put("hints", value.hints)
        put("undos", value.undos)
        put("pauses", value.pauses)
        put("threatIndication", value.threatIndication)
    }

    private fun decodeAssistance(value: JsonObject): AssistanceCounts = AssistanceCounts(
        hints = value.requiredInt("hints"),
        undos = value.requiredInt("undos"),
        pauses = value.requiredInt("pauses"),
        threatIndication = value.optionalStrictBoolean("threatIndication", false),
    )
}

private fun JsonObjectBuilder.putNullable(name: String, value: JsonElement?) {
    put(name, value ?: JsonNull)
}

private fun JsonObject.required(name: String): JsonElement =
    requireNotNull(this[name]) { "Missing '$name'" }

private fun JsonObject.requiredObject(name: String): JsonObject = required(name).jsonObject
private fun JsonObject.requiredArray(name: String): JsonArray = required(name).jsonArray
private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.content
private fun JsonObject.requiredLong(name: String): Long = required(name).jsonPrimitive.long
private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.int
private fun JsonObject.requiredBoolean(name: String): Boolean = required(name).jsonPrimitive.boolean

private fun JsonObject.requiredNullableObject(name: String): JsonObject? =
    required(name).let { if (it is JsonNull) null else it.jsonObject }

private fun JsonObject.requiredNullableString(name: String): String? =
    required(name).jsonPrimitive.contentOrNull

private fun JsonObject.requiredNullableLong(name: String): Long? =
    required(name).let { if (it is JsonNull) null else it.jsonPrimitive.long }

private fun JsonObject.requiredNullableInt(name: String): Int? =
    required(name).let { if (it is JsonNull) null else it.jsonPrimitive.int }

private fun JsonObject.optionalInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.optionalArray(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.optionalStrictBoolean(name: String, defaultValue: Boolean): Boolean {
    val raw = this[name] ?: return defaultValue
    require(raw is JsonPrimitive && !raw.isString && raw.content in setOf("true", "false")) {
        "'$name' must be a JSON boolean"
    }
    return raw.boolean
}
