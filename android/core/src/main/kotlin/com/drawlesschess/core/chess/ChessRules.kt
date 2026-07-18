package com.drawlesschess.core.chess

import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import kotlin.math.abs

object ChessRules {
    private val knightOffsets = listOf(
        -2 to -1, -2 to 1, -1 to -2, -1 to 2,
        1 to -2, 1 to 2, 2 to -1, 2 to 1,
    )
    private val bishopDirections = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val rookDirections = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val kingDirections = bishopDirections + rookDirections
    private val promotions = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)

    fun legalMoves(position: ChessPosition): List<ChessMove> = pseudoLegalMoves(position).filter { move ->
        val moved = applyUnchecked(position, move)
        !isInCheck(moved, position.sideToMove)
    }

    /** Structurally valid moves before own-king safety is applied, including unsafe castling. */
    internal fun movesIgnoringOwnKingSafety(position: ChessPosition): List<ChessMove> =
        pseudoLegalMoves(position, enforceCastlingSafety = false)

    fun legalUciMoves(position: ChessPosition): List<UciMove> = legalMoves(position).map(ChessMove::toUci)

    fun apply(position: ChessPosition, move: ChessMove): ChessPosition {
        require(move in legalMoves(position)) { "Illegal move ${move.toUci().value} in ${position.fen()}" }
        return applyUnchecked(position, move)
    }

    fun apply(position: ChessPosition, move: UciMove): ChessPosition = apply(position, ChessMove.fromUci(move))

    fun isInCheck(position: ChessPosition, side: Side = position.sideToMove): Boolean {
        val king = position.pieces().singleOrNull { (_, piece) ->
            piece.side == side && piece.type == PieceType.KING
        }?.first ?: throw IllegalStateException("Missing ${side.name.lowercase()} king")
        return isSquareAttacked(position, king, side.opposite())
    }

    fun isCheckmate(position: ChessPosition): Boolean = isInCheck(position) && legalMoves(position).isEmpty()

    fun isStalemate(position: ChessPosition): Boolean = !isInCheck(position) && legalMoves(position).isEmpty()

    fun isSquareAttacked(position: ChessPosition, target: Square, bySide: Side): Boolean =
        attackersOf(position, target, bySide).isNotEmpty()

    /** Enemy origins attacking [target], used by both legality and explanatory presentation. */
    fun attackersOf(position: ChessPosition, target: Square, bySide: Side): Set<Square> = buildSet {
        val pawnSourceRank = target.rank - if (bySide == Side.WHITE) 1 else -1
        for (fileOffset in listOf(-1, 1)) {
            val source = Square.at(target.file - fileOffset, pawnSourceRank)
            if (source != null && position[source] == Piece(bySide, PieceType.PAWN)) add(source)
        }

        for ((df, dr) in knightOffsets) {
            val source = Square.at(target.file + df, target.rank + dr)
            if (source != null && position[source] == Piece(bySide, PieceType.KNIGHT)) add(source)
        }

        addAll(rayAttackers(position, target, bySide, bishopDirections, PieceType.BISHOP))
        addAll(rayAttackers(position, target, bySide, rookDirections, PieceType.ROOK))

        for ((df, dr) in kingDirections) {
            val source = Square.at(target.file + df, target.rank + dr)
            if (source != null && position[source] == Piece(bySide, PieceType.KING)) add(source)
        }
    }

    private fun rayAttackers(
        position: ChessPosition,
        target: Square,
        bySide: Side,
        directions: List<Pair<Int, Int>>,
        linePiece: PieceType,
    ): Set<Square> = buildSet {
        for ((df, dr) in directions) {
            var file = target.file + df
            var rank = target.rank + dr
            while (true) {
                val square = Square.at(file, rank) ?: break
                val piece = position[square]
                if (piece != null) {
                    if (piece.side == bySide && (piece.type == linePiece || piece.type == PieceType.QUEEN)) {
                        add(square)
                    }
                    break
                }
                file += df
                rank += dr
            }
        }
    }

    private fun pseudoLegalMoves(
        position: ChessPosition,
        enforceCastlingSafety: Boolean = true,
    ): List<ChessMove> = buildList {
        for ((square, piece) in position.pieces()) {
            if (piece.side != position.sideToMove) continue
            when (piece.type) {
                PieceType.PAWN -> addPawnMoves(position, square, piece.side, this)
                PieceType.KNIGHT -> addJumpMoves(position, square, piece.side, knightOffsets, this)
                PieceType.BISHOP -> addSlidingMoves(position, square, piece.side, bishopDirections, this)
                PieceType.ROOK -> addSlidingMoves(position, square, piece.side, rookDirections, this)
                PieceType.QUEEN -> addSlidingMoves(position, square, piece.side, kingDirections, this)
                PieceType.KING -> {
                    addJumpMoves(position, square, piece.side, kingDirections, this)
                    addCastlingMoves(position, square, piece.side, enforceCastlingSafety, this)
                }
            }
        }
    }

    private fun addPawnMoves(position: ChessPosition, from: Square, side: Side, moves: MutableList<ChessMove>) {
        val direction = if (side == Side.WHITE) 1 else -1
        val startRank = if (side == Side.WHITE) 1 else 6
        val promotionRank = if (side == Side.WHITE) 7 else 0
        val one = Square.at(from.file, from.rank + direction)
        if (one != null && position[one] == null) {
            addPawnDestination(from, one, promotionRank, moves)
            val two = Square.at(from.file, from.rank + 2 * direction)
            if (from.rank == startRank && two != null && position[two] == null) {
                moves += ChessMove(from, two)
            }
        }
        for (fileDelta in listOf(-1, 1)) {
            val to = Square.at(from.file + fileDelta, from.rank + direction) ?: continue
            val target = position[to]
            if (target != null && target.side != side && target.type != PieceType.KING) {
                addPawnDestination(from, to, promotionRank, moves)
            } else if (to == position.enPassantTarget && target == null) {
                val captured = Square.at(to.file, from.rank)
                if (captured != null && position[captured] == Piece(side.opposite(), PieceType.PAWN)) {
                    moves += ChessMove(from, to)
                }
            }
        }
    }

    private fun addPawnDestination(from: Square, to: Square, promotionRank: Int, moves: MutableList<ChessMove>) {
        if (to.rank == promotionRank) promotions.forEach { moves += ChessMove(from, to, it) }
        else moves += ChessMove(from, to)
    }

    private fun addJumpMoves(
        position: ChessPosition,
        from: Square,
        side: Side,
        offsets: List<Pair<Int, Int>>,
        moves: MutableList<ChessMove>,
    ) {
        for ((df, dr) in offsets) {
            val to = Square.at(from.file + df, from.rank + dr) ?: continue
            val target = position[to]
            if (target == null || (target.side != side && target.type != PieceType.KING)) {
                moves += ChessMove(from, to)
            }
        }
    }

    private fun addSlidingMoves(
        position: ChessPosition,
        from: Square,
        side: Side,
        directions: List<Pair<Int, Int>>,
        moves: MutableList<ChessMove>,
    ) {
        for ((df, dr) in directions) {
            var file = from.file + df
            var rank = from.rank + dr
            while (true) {
                val to = Square.at(file, rank) ?: break
                val target = position[to]
                if (target == null) {
                    moves += ChessMove(from, to)
                } else {
                    if (target.side != side && target.type != PieceType.KING) moves += ChessMove(from, to)
                    break
                }
                file += df
                rank += dr
            }
        }
    }

    private fun addCastlingMoves(
        position: ChessPosition,
        king: Square,
        side: Side,
        enforceKingSafety: Boolean,
        moves: MutableList<ChessMove>,
    ) {
        val rank = if (side == Side.WHITE) 0 else 7
        if (king != Square.at(4, rank) || position[king]?.type != PieceType.KING) return
        if (enforceKingSafety && isSquareAttacked(position, king, side.opposite())) return

        val kingSideRight = if (side == Side.WHITE) position.castlingRights.whiteKingSide
        else position.castlingRights.blackKingSide
        if (kingSideRight &&
            position[Square.at(7, rank)!!] == Piece(side, PieceType.ROOK) &&
            position[Square.at(5, rank)!!] == null && position[Square.at(6, rank)!!] == null &&
            (!enforceKingSafety ||
                (!isSquareAttacked(position, Square.at(5, rank)!!, side.opposite()) &&
                    !isSquareAttacked(position, Square.at(6, rank)!!, side.opposite())))) {
            moves += ChessMove(king, Square.at(6, rank)!!)
        }

        val queenSideRight = if (side == Side.WHITE) position.castlingRights.whiteQueenSide
        else position.castlingRights.blackQueenSide
        if (queenSideRight &&
            position[Square.at(0, rank)!!] == Piece(side, PieceType.ROOK) &&
            position[Square.at(1, rank)!!] == null && position[Square.at(2, rank)!!] == null &&
            position[Square.at(3, rank)!!] == null &&
            (!enforceKingSafety ||
                (!isSquareAttacked(position, Square.at(3, rank)!!, side.opposite()) &&
                    !isSquareAttacked(position, Square.at(2, rank)!!, side.opposite())))) {
            moves += ChessMove(king, Square.at(2, rank)!!)
        }
    }

    internal fun applyUnchecked(position: ChessPosition, move: ChessMove): ChessPosition {
        val board = position.boardCopy()
        val moving = board[move.from.index] ?: throw IllegalArgumentException("No piece on ${move.from.algebraic}")
        require(moving.side == position.sideToMove) { "Moving the wrong side" }
        val directCapture = board[move.to.index]
        var wasCapture = directCapture != null

        if (moving.type == PieceType.PAWN && move.to == position.enPassantTarget && directCapture == null &&
            move.from.file != move.to.file) {
            val capturedSquare = Square.at(move.to.file, move.from.rank)!!
            require(board[capturedSquare.index] == Piece(moving.side.opposite(), PieceType.PAWN)) {
                "Invalid en-passant capture"
            }
            board[capturedSquare.index] = null
            wasCapture = true
        }

        board[move.from.index] = null
        val placed = if (move.promotion != null) {
            require(moving.type == PieceType.PAWN && move.to.rank in listOf(0, 7)) { "Invalid promotion" }
            Piece(moving.side, move.promotion)
        } else {
            require(moving.type != PieceType.PAWN || move.to.rank !in listOf(0, 7)) { "Promotion piece required" }
            moving
        }
        board[move.to.index] = placed

        if (moving.type == PieceType.KING && abs(move.to.file - move.from.file) == 2) {
            val rank = move.from.rank
            val rookFrom = if (move.to.file == 6) Square.at(7, rank)!! else Square.at(0, rank)!!
            val rookTo = if (move.to.file == 6) Square.at(5, rank)!! else Square.at(3, rank)!!
            require(board[rookFrom.index] == Piece(moving.side, PieceType.ROOK)) { "Missing castling rook" }
            board[rookTo.index] = board[rookFrom.index]
            board[rookFrom.index] = null
        }

        val rights = updatedCastlingRights(position.castlingRights, moving, move.from, directCapture, move.to)
        val enPassant = if (moving.type == PieceType.PAWN && abs(move.to.rank - move.from.rank) == 2) {
            Square.at(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else null
        val halfmove = if (moving.type == PieceType.PAWN || wasCapture) 0 else position.halfmoveClock + 1
        val fullmove = position.fullmoveNumber + if (position.sideToMove == Side.BLACK) 1 else 0
        return ChessPosition.create(
            board, position.sideToMove.opposite(), rights, enPassant, halfmove, fullmove,
        )
    }

    private fun updatedCastlingRights(
        current: CastlingRights,
        moving: Piece,
        from: Square,
        captured: Piece?,
        capturedOn: Square,
    ): CastlingRights {
        var wk = current.whiteKingSide
        var wq = current.whiteQueenSide
        var bk = current.blackKingSide
        var bq = current.blackQueenSide

        if (moving.type == PieceType.KING) {
            if (moving.side == Side.WHITE) { wk = false; wq = false } else { bk = false; bq = false }
        }
        if (moving.type == PieceType.ROOK) {
            when (from.algebraic) {
                "h1" -> wk = false
                "a1" -> wq = false
                "h8" -> bk = false
                "a8" -> bq = false
            }
        }
        if (captured?.type == PieceType.ROOK) {
            when (capturedOn.algebraic) {
                "h1" -> wk = false
                "a1" -> wq = false
                "h8" -> bk = false
                "a8" -> bq = false
            }
        }
        return CastlingRights(wk, wq, bk, bq)
    }
}
