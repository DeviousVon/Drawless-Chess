package com.drawlesschess.core.chess

import com.drawlesschess.core.Side

class ChessPosition private constructor(
    private val board: List<Piece?>,
    val sideToMove: Side,
    val castlingRights: CastlingRights,
    val enPassantTarget: Square?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    init {
        require(board.size == 64)
        require(halfmoveClock >= 0) { "Halfmove clock cannot be negative" }
        require(fullmoveNumber >= 1) { "Fullmove number must be positive" }
        require(board.count { it == Piece(Side.WHITE, PieceType.KING) } == 1) { "Position needs one white king" }
        require(board.count { it == Piece(Side.BLACK, PieceType.KING) } == 1) { "Position needs one black king" }
        if (enPassantTarget != null) {
            require(enPassantTarget.rank == 2 || enPassantTarget.rank == 5) {
                "En-passant target must be on rank 3 or 6"
            }
        }
    }

    operator fun get(square: Square): Piece? = board[square.index]
    fun pieces(): List<Pair<Square, Piece>> = board.mapIndexedNotNull { index, piece ->
        piece?.let { Square(index) to it }
    }

    internal fun boardCopy(): MutableList<Piece?> = board.toMutableList()

    fun fen(): String = listOf(
        placementFen(),
        if (sideToMove == Side.WHITE) "w" else "b",
        castlingRights.fen(),
        enPassantTarget?.algebraic ?: "-",
        halfmoveClock.toString(),
        fullmoveNumber.toString(),
    ).joinToString(" ")

    internal fun placementFen(): String = (7 downTo 0).joinToString("/") { rank ->
        buildString {
            var empty = 0
            for (file in 0..7) {
                val piece = board[rank * 8 + file]
                if (piece == null) {
                    empty++
                } else {
                    if (empty != 0) append(empty)
                    empty = 0
                    append(piece.toFen())
                }
            }
            if (empty != 0) append(empty)
        }
    }

    override fun equals(other: Any?): Boolean = other is ChessPosition &&
        board == other.board && sideToMove == other.sideToMove &&
        castlingRights == other.castlingRights && enPassantTarget == other.enPassantTarget &&
        halfmoveClock == other.halfmoveClock && fullmoveNumber == other.fullmoveNumber

    override fun hashCode(): Int {
        var result = board.hashCode()
        result = 31 * result + sideToMove.hashCode()
        result = 31 * result + castlingRights.hashCode()
        result = 31 * result + (enPassantTarget?.hashCode() ?: 0)
        result = 31 * result + halfmoveClock
        return 31 * result + fullmoveNumber
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun starting(): ChessPosition = fromFen(START_FEN)

        fun fromFen(fen: String): ChessPosition {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size == 6) { "FEN must contain six fields" }
            val ranks = fields[0].split('/')
            require(ranks.size == 8) { "FEN placement must contain eight ranks" }
            val board = MutableList<Piece?>(64) { null }
            ranks.forEachIndexed { fenRankIndex, text ->
                val rank = 7 - fenRankIndex
                var file = 0
                for (char in text) {
                    if (char.isDigit()) {
                        val empty = char.digitToInt()
                        require(empty in 1..8) { "Invalid empty-square count" }
                        file += empty
                    } else {
                        require(file in 0..7) { "Too many squares in FEN rank" }
                        val side = if (char.isUpperCase()) Side.WHITE else Side.BLACK
                        board[rank * 8 + file] = Piece(side, PieceType.fromFen(char))
                        file++
                    }
                }
                require(file == 8) { "FEN rank does not contain eight squares" }
            }
            val side = when (fields[1]) {
                "w" -> Side.WHITE
                "b" -> Side.BLACK
                else -> throw IllegalArgumentException("Invalid side to move")
            }
            val enPassant = if (fields[3] == "-") null else Square.parse(fields[3])
            return ChessPosition(
                board = board,
                sideToMove = side,
                castlingRights = CastlingRights.parse(fields[2]),
                enPassantTarget = enPassant,
                halfmoveClock = fields[4].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid halfmove clock"),
                fullmoveNumber = fields[5].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid fullmove number"),
            )
        }

        internal fun create(
            board: List<Piece?>,
            sideToMove: Side,
            castlingRights: CastlingRights,
            enPassantTarget: Square?,
            halfmoveClock: Int,
            fullmoveNumber: Int,
        ): ChessPosition = ChessPosition(
            board, sideToMove, castlingRights, enPassantTarget, halfmoveClock, fullmoveNumber,
        )
    }
}
