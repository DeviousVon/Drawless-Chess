package com.drawlesschess.core

enum class Side {
    WHITE,
    BLACK;

    fun opposite(): Side = if (this == WHITE) BLACK else WHITE
}

@JvmInline
value class PositionKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Position key cannot be blank" }
    }
}

@JvmInline
value class UciMove(val value: String) {
    init {
        require(UCI_PATTERN.matches(value)) { "Invalid standard-chess UCI move: $value" }
    }

    companion object {
        private val UCI_PATTERN = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")
    }
}

data class MaterialScore(
    val white: Int,
    val black: Int,
) {
    init {
        require(white >= 0 && black >= 0) { "Material scores cannot be negative" }
    }
}

data class MoveAlternative(
    val move: UciMove,
    val resultingPositionKey: PositionKey,
    val resultingHalfmoveClock: Int,
) {
    init {
        require(resultingHalfmoveClock >= 0) { "Halfmove clock cannot be negative" }
    }
}

data class MoveTransition(
    val move: UciMove,
    val mover: Side,
    val resultingPositionKey: PositionKey,
    val legalMovesAfter: Int,
    val sideToMoveInCheck: Boolean,
    val legalAlternativesBeforeMove: List<MoveAlternative>,
    val halfmoveClockAfter: Int,
    val deadPositionAfter: Boolean,
    val moveWasCapture: Boolean,
    val materialAfter: MaterialScore,
) {
    init {
        require(legalMovesAfter >= 0) { "Legal move count cannot be negative" }
        require(halfmoveClockAfter >= 0) { "Halfmove clock cannot be negative" }
        require(legalAlternativesBeforeMove.map { it.move }.distinct().size == legalAlternativesBeforeMove.size) {
            "Legal alternatives must contain unique moves"
        }
        val selected = legalAlternativesBeforeMove.singleOrNull { it.move == move }
            ?: throw IllegalArgumentException("Selected move must appear exactly once in legal alternatives")
        require(selected.resultingPositionKey == resultingPositionKey) {
            "Selected alternative position does not match transition"
        }
        require(selected.resultingHalfmoveClock == halfmoveClockAfter) {
            "Selected alternative halfmove clock does not match transition"
        }
    }
}
