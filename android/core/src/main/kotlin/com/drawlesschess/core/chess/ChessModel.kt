package com.drawlesschess.core.chess

import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove

enum class PieceType(val fenLower: Char) {
    PAWN('p'),
    KNIGHT('n'),
    BISHOP('b'),
    ROOK('r'),
    QUEEN('q'),
    KING('k');

    companion object {
        fun fromFen(char: Char): PieceType = entries.singleOrNull { it.fenLower == char.lowercaseChar() }
            ?: throw IllegalArgumentException("Invalid FEN piece: $char")
    }
}

data class Piece(val side: Side, val type: PieceType) {
    fun toFen(): Char = if (side == Side.WHITE) type.fenLower.uppercaseChar() else type.fenLower
}

@JvmInline
value class Square(val index: Int) {
    init {
        require(index in 0..63) { "Square index must be between 0 and 63" }
    }

    val file: Int get() = index % 8
    val rank: Int get() = index / 8
    val algebraic: String get() = "${('a'.code + file).toChar()}${rank + 1}"
    val isLight: Boolean get() = (file + rank) % 2 == 1

    companion object {
        fun at(file: Int, rank: Int): Square? =
            if (file in 0..7 && rank in 0..7) Square(rank * 8 + file) else null

        fun parse(value: String): Square {
            require(value.length == 2 && value[0] in 'a'..'h' && value[1] in '1'..'8') {
                "Invalid square: $value"
            }
            return Square((value[1] - '1') * 8 + (value[0] - 'a'))
        }
    }
}

data class ChessMove(
    val from: Square,
    val to: Square,
    val promotion: PieceType? = null,
) {
    init {
        require(from != to) { "A chess move must change squares" }
        require(promotion == null || promotion in PROMOTIONS) { "Invalid promotion piece" }
    }

    fun toUci(): UciMove = UciMove(
        from.algebraic + to.algebraic + (promotion?.fenLower?.toString() ?: ""),
    )

    companion object {
        private val PROMOTIONS = setOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

        fun fromUci(move: UciMove): ChessMove {
            val value = move.value
            val promotion = if (value.length == 5) PieceType.fromFen(value[4]) else null
            return ChessMove(Square.parse(value.substring(0, 2)), Square.parse(value.substring(2, 4)), promotion)
        }
    }
}

data class CastlingRights(
    val whiteKingSide: Boolean = false,
    val whiteQueenSide: Boolean = false,
    val blackKingSide: Boolean = false,
    val blackQueenSide: Boolean = false,
) {
    fun fen(): String = buildString {
        if (whiteKingSide) append('K')
        if (whiteQueenSide) append('Q')
        if (blackKingSide) append('k')
        if (blackQueenSide) append('q')
    }.ifEmpty { "-" }

    companion object {
        fun parse(value: String): CastlingRights {
            if (value == "-") return CastlingRights()
            require(value.isNotEmpty() && value.all { it in "KQkq" } && value.toSet().size == value.length) {
                "Invalid castling rights: $value"
            }
            return CastlingRights('K' in value, 'Q' in value, 'k' in value, 'q' in value)
        }
    }
}
