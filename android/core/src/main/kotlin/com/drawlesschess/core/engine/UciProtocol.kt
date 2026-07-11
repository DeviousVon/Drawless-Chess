package com.drawlesschess.core.engine

import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.UciMove

class UciProtocolException(message: String) : IllegalArgumentException(message)

enum class UciOptionType {
    CHECK,
    SPIN,
    COMBO,
    BUTTON,
    STRING,
}

data class UciOption(
    val name: String,
    val type: UciOptionType,
    val defaultValue: String? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val choices: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank())
        require((minimum == null) == (maximum == null))
        require(minimum == null || minimum <= maximum!!)
        require(type == UciOptionType.COMBO || choices.isEmpty())
    }
}

sealed interface UciScore {
    val value: Int
    val bound: EngineScoreBound

    data class Centipawns(
        override val value: Int,
        override val bound: EngineScoreBound = EngineScoreBound.EXACT,
    ) : UciScore

    data class Mate(
        override val value: Int,
        override val bound: EngineScoreBound = EngineScoreBound.EXACT,
    ) : UciScore
}

data class UciWdl(val wins: Int, val draws: Int, val losses: Int) {
    init { require(wins >= 0 && draws >= 0 && losses >= 0) }
}

data class UciInfo(
    val depth: Int? = null,
    val selectiveDepth: Int? = null,
    val multiPv: Int? = null,
    val score: UciScore? = null,
    val nodes: Long? = null,
    val nodesPerSecond: Long? = null,
    val timeMillis: Long? = null,
    val hashFullPermill: Int? = null,
    val tablebaseHits: Long? = null,
    val currentMove: UciMove? = null,
    val currentMoveNumber: Int? = null,
    val wdl: UciWdl? = null,
    val principalVariation: List<UciMove> = emptyList(),
    val text: String? = null,
    val unknownTokens: List<String> = emptyList(),
)

sealed interface UciMessage {
    data class IdName(val value: String) : UciMessage
    data class IdAuthor(val value: String) : UciMessage
    data class Option(val value: UciOption) : UciMessage
    data object UciOk : UciMessage
    data object ReadyOk : UciMessage
    data class Info(val value: UciInfo) : UciMessage
    data class BestMove(val move: UciMove?, val ponder: UciMove?) : UciMessage
    data class Unknown(val raw: String) : UciMessage
}

object UciProtocol {
    fun parse(line: String): UciMessage {
        val clean = line.trim()
        if (clean.isEmpty()) return UciMessage.Unknown("")
        val tokens = clean.split(Regex("\\s+"))
        return when (tokens.first()) {
            "id" -> parseId(tokens, clean)
            "option" -> UciMessage.Option(parseOption(tokens, clean))
            "uciok" -> exactMarker(tokens, UciMessage.UciOk, clean)
            "readyok" -> exactMarker(tokens, UciMessage.ReadyOk, clean)
            "info" -> UciMessage.Info(parseInfo(tokens.drop(1), clean))
            "bestmove" -> parseBestMove(tokens, clean)
            else -> UciMessage.Unknown(clean)
        }
    }

    private fun parseId(tokens: List<String>, raw: String): UciMessage {
        if (tokens.size < 3) malformed(raw, "id line requires a kind and value")
        val value = tokens.drop(2).joinToString(" ")
        return when (tokens[1]) {
            "name" -> UciMessage.IdName(value)
            "author" -> UciMessage.IdAuthor(value)
            else -> UciMessage.Unknown(raw)
        }
    }

    private fun parseOption(tokens: List<String>, raw: String): UciOption {
        if (tokens.size < 5 || tokens[1] != "name") malformed(raw, "option requires name and type")
        val typeIndex = (3 until tokens.size - 1).firstOrNull { index ->
            tokens[index] == "type" && optionType(tokens[index + 1]) != null
        } ?: malformed(raw, "option type is missing or unsupported")
        val name = tokens.subList(2, typeIndex).joinToString(" ")
        if (name.isBlank()) malformed(raw, "option name is blank")
        val type = optionType(tokens[typeIndex + 1])!!
        val tail = tokens.drop(typeIndex + 2)

        var defaultValue: String? = null
        var minimum: Int? = null
        var maximum: Int? = null
        val choices = mutableListOf<String>()
        var index = 0
        while (index < tail.size) {
            when (tail[index]) {
                "default" -> {
                    val end = nextOptionMarker(tail, index + 1, type)
                    defaultValue = tail.subList(index + 1, end).joinToString(" ")
                    index = end
                }
                "min" -> {
                    if (index + 1 >= tail.size) malformed(raw, "min requires a number")
                    minimum = parseInt(tail[index + 1], raw, "min")
                    index += 2
                }
                "max" -> {
                    if (index + 1 >= tail.size) malformed(raw, "max requires a number")
                    maximum = parseInt(tail[index + 1], raw, "max")
                    index += 2
                }
                "var" -> {
                    if (type != UciOptionType.COMBO || index + 1 >= tail.size) {
                        malformed(raw, "var is valid only for combo options")
                    }
                    val end = (index + 1 until tail.size).firstOrNull { tail[it] == "var" } ?: tail.size
                    choices += tail.subList(index + 1, end).joinToString(" ")
                    index = end
                }
                else -> malformed(raw, "unexpected option token '${tail[index]}'")
            }
        }
        if ((minimum == null) != (maximum == null)) malformed(raw, "spin range requires min and max")
        if (type == UciOptionType.SPIN && minimum != null && minimum > maximum!!) {
            malformed(raw, "spin minimum exceeds maximum")
        }
        return UciOption(name, type, defaultValue, minimum, maximum, choices)
    }

    private fun nextOptionMarker(tokens: List<String>, start: Int, type: UciOptionType): Int {
        if (type == UciOptionType.STRING) return tokens.size
        for (index in start until tokens.size) {
            if (tokens[index] in setOf("min", "max", "var")) return index
        }
        return tokens.size
    }

    private fun parseInfo(tokens: List<String>, raw: String): UciInfo {
        var depth: Int? = null
        var selectiveDepth: Int? = null
        var multiPv: Int? = null
        var score: UciScore? = null
        var nodes: Long? = null
        var nodesPerSecond: Long? = null
        var timeMillis: Long? = null
        var hashFull: Int? = null
        var tablebaseHits: Long? = null
        var currentMove: UciMove? = null
        var currentMoveNumber: Int? = null
        var wdl: UciWdl? = null
        var pv = emptyList<UciMove>()
        var text: String? = null
        val unknown = mutableListOf<String>()
        var index = 0

        fun requiredValue(field: String): String {
            if (index + 1 >= tokens.size) malformed(raw, "$field requires a value")
            return tokens[index + 1]
        }

        while (index < tokens.size) {
            when (val token = tokens[index]) {
                "depth" -> { depth = nonNegativeInt(requiredValue(token), raw, token); index += 2 }
                "seldepth" -> { selectiveDepth = nonNegativeInt(requiredValue(token), raw, token); index += 2 }
                "multipv" -> {
                    val parsed = parseInt(requiredValue(token), raw, token)
                    if (parsed < 1) malformed(raw, "multipv must be positive")
                    multiPv = parsed
                    index += 2
                }
                "nodes" -> { nodes = nonNegativeLong(requiredValue(token), raw, token); index += 2 }
                "nps" -> { nodesPerSecond = nonNegativeLong(requiredValue(token), raw, token); index += 2 }
                "time" -> { timeMillis = nonNegativeLong(requiredValue(token), raw, token); index += 2 }
                "hashfull" -> { hashFull = nonNegativeInt(requiredValue(token), raw, token); index += 2 }
                "tbhits" -> { tablebaseHits = nonNegativeLong(requiredValue(token), raw, token); index += 2 }
                "currmove" -> {
                    currentMove = parseMove(requiredValue(token), raw)
                    index += 2
                }
                "currmovenumber" -> {
                    val parsed = parseInt(requiredValue(token), raw, token)
                    if (parsed < 1) malformed(raw, "currmovenumber must be positive")
                    currentMoveNumber = parsed
                    index += 2
                }
                "score" -> {
                    if (index + 2 >= tokens.size) malformed(raw, "score requires a kind and value")
                    val value = parseInt(tokens[index + 2], raw, "score")
                    var bound = EngineScoreBound.EXACT
                    var consumed = 3
                    if (index + 3 < tokens.size) {
                        bound = when (tokens[index + 3]) {
                            "lowerbound" -> EngineScoreBound.LOWER
                            "upperbound" -> EngineScoreBound.UPPER
                            else -> EngineScoreBound.EXACT
                        }
                        if (bound != EngineScoreBound.EXACT) consumed++
                    }
                    score = when (tokens[index + 1]) {
                        "cp" -> UciScore.Centipawns(value, bound)
                        "mate" -> UciScore.Mate(value, bound)
                        else -> malformed(raw, "unsupported score kind '${tokens[index + 1]}'")
                    }
                    index += consumed
                }
                "wdl" -> {
                    if (index + 3 >= tokens.size) malformed(raw, "wdl requires three values")
                    wdl = UciWdl(
                        nonNegativeInt(tokens[index + 1], raw, "wdl wins"),
                        nonNegativeInt(tokens[index + 2], raw, "wdl draws"),
                        nonNegativeInt(tokens[index + 3], raw, "wdl losses"),
                    )
                    index += 4
                }
                "pv" -> {
                    if (index + 1 >= tokens.size) malformed(raw, "pv requires at least one move")
                    pv = tokens.drop(index + 1).map { parseMove(it, raw) }
                    index = tokens.size
                }
                "string" -> {
                    text = tokens.drop(index + 1).joinToString(" ")
                    index = tokens.size
                }
                else -> {
                    unknown += token
                    index++
                }
            }
        }
        return UciInfo(
            depth, selectiveDepth, multiPv, score, nodes, nodesPerSecond, timeMillis,
            hashFull, tablebaseHits, currentMove, currentMoveNumber, wdl, pv, text, unknown,
        )
    }

    private fun parseBestMove(tokens: List<String>, raw: String): UciMessage.BestMove {
        if (tokens.size !in setOf(2, 4)) malformed(raw, "bestmove has an invalid token count")
        val move = parseOptionalMove(tokens[1], raw)
        val ponder = if (tokens.size == 4) {
            if (tokens[2] != "ponder") malformed(raw, "bestmove suffix must be ponder")
            parseMove(tokens[3], raw)
        } else null
        if (move == null && ponder != null) malformed(raw, "a null bestmove cannot ponder")
        return UciMessage.BestMove(move, ponder)
    }

    private fun parseOptionalMove(value: String, raw: String): UciMove? = when (value) {
        "(none)", "0000" -> null
        else -> parseMove(value, raw)
    }

    private fun parseMove(value: String, raw: String): UciMove = try {
        UciMove(value)
    } catch (_: IllegalArgumentException) {
        malformed(raw, "invalid UCI move '$value'")
    }

    private fun optionType(value: String): UciOptionType? = when (value) {
        "check" -> UciOptionType.CHECK
        "spin" -> UciOptionType.SPIN
        "combo" -> UciOptionType.COMBO
        "button" -> UciOptionType.BUTTON
        "string" -> UciOptionType.STRING
        else -> null
    }

    private fun parseInt(value: String, raw: String, field: String): Int =
        value.toIntOrNull() ?: malformed(raw, "$field is not an integer")

    private fun nonNegativeInt(value: String, raw: String, field: String): Int =
        parseInt(value, raw, field).also { if (it < 0) malformed(raw, "$field cannot be negative") }

    private fun nonNegativeLong(value: String, raw: String, field: String): Long =
        (value.toLongOrNull() ?: malformed(raw, "$field is not an integer")).also {
            if (it < 0) malformed(raw, "$field cannot be negative")
        }

    private fun <T : UciMessage> exactMarker(tokens: List<String>, marker: T, raw: String): T {
        if (tokens.size != 1) malformed(raw, "marker contains trailing tokens")
        return marker
    }

    private fun malformed(raw: String, reason: String): Nothing =
        throw UciProtocolException("Malformed UCI line ($reason): $raw")
}

object UciCommands {
    fun setOption(name: String, value: String? = null): String {
        requireTokenText(name, "option name")
        value?.let { requireTokenText(it, "option value") }
        return if (value == null) "setoption name $name" else "setoption name $name value $value"
    }

    fun position(initialFen: String, moves: List<UciMove>): String {
        requireTokenText(initialFen, "FEN")
        val root = if (initialFen == START_FEN) "startpos" else "fen $initialFen"
        return buildString {
            append("position ").append(root)
            if (moves.isNotEmpty()) append(" moves ").append(moves.joinToString(" ") { it.value })
        }
    }

    fun goMoveTime(moveTimeMillis: Long): String {
        require(moveTimeMillis > 0)
        return "go movetime $moveTimeMillis"
    }

    private fun requireTokenText(value: String, label: String) {
        require(value.isNotBlank()) { "$label cannot be blank" }
        require('\n' !in value && '\r' !in value) { "$label cannot contain a line break" }
    }

    private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
}
