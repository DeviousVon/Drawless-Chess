package com.drawlesschess.shared

import com.drawlesschess.core.AssistanceCounts
import com.drawlesschess.core.BareKingPolicy
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
import com.drawlesschess.core.coordinator.CoordinatorCheckpoint
import com.drawlesschess.core.coordinator.CoordinatorClock
import com.drawlesschess.core.coordinator.GameConfig
import com.drawlesschess.core.coordinator.MoveClockSnapshot
import com.drawlesschess.core.engine.BotDifficultyCatalog
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
    }.toString()

    fun decode(payloadJson: String): CoordinatorCheckpoint {
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        require(payload.requiredInt("formatVersion") == FORMAT_VERSION) {
            "Unsupported checkpoint payload format"
        }
        return CoordinatorCheckpoint(
            revision = payload.requiredLong("revision"),
            config = decodeConfig(payload.requiredObject("config")),
            moves = payload.requiredArray("moves").map { UciMove(it.jsonPrimitive.content) },
            currentFen = payload.requiredString("currentFen"),
            outcome = payload.requiredNullableObject("outcome")?.let(::decodeOutcome),
            clock = decodeClock(payload.requiredObject("clock")),
            moveClocks = payload.requiredArray("moveClocks").map { decodeMoveClock(it.jsonObject) },
            assistance = decodeAssistance(payload.requiredObject("assistance")),
        )
    }

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

private fun JsonObject.optionalStrictBoolean(name: String, defaultValue: Boolean): Boolean {
    val raw = this[name] ?: return defaultValue
    require(raw is JsonPrimitive && !raw.isString && raw.content in setOf("true", "false")) {
        "'$name' must be a JSON boolean"
    }
    return raw.boolean
}
