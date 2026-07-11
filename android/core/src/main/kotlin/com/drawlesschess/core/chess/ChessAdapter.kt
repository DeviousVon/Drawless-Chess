package com.drawlesschess.core.chess

import com.drawlesschess.core.MaterialScore
import com.drawlesschess.core.MoveAlternative
import com.drawlesschess.core.MoveTransition
import com.drawlesschess.core.PositionKey
import com.drawlesschess.core.UciMove

object RepetitionKey {
    fun of(position: ChessPosition): PositionKey {
        val side = if (position.sideToMove == com.drawlesschess.core.Side.WHITE) "w" else "b"
        val enPassant = effectiveEnPassantTarget(position)?.algebraic ?: "-"
        return PositionKey(
            "${position.placementFen()} $side ${position.castlingRights.fen()} $enPassant",
        )
    }

    private fun effectiveEnPassantTarget(position: ChessPosition): Square? {
        val target = position.enPassantTarget ?: return null
        return if (ChessRules.legalMoves(position).any { move ->
                move.to == target && position[move.from]?.type == PieceType.PAWN && move.from.file != move.to.file
            }) target else null
    }
}

object DeadPositionDetector {
    /**
     * Exact for the standard material-only dead positions used by major chess software:
     * bare kings, a single bishop/knight, or bishops confined to one square color.
     * It intentionally returns false for exotic blocked-piece dead positions until the
     * production engine supplies a general proof.
     */
    fun isKnownDead(position: ChessPosition): Boolean {
        val nonKings = position.pieces().filter { (_, piece) -> piece.type != PieceType.KING }
        if (nonKings.isEmpty()) return true
        if (nonKings.any { (_, piece) -> piece.type in setOf(PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN) }) {
            return false
        }
        if (nonKings.size == 1) return true
        if (nonKings.all { (_, piece) -> piece.type == PieceType.BISHOP }) {
            return nonKings.map { (square, _) -> square.isLight }.distinct().size == 1
        }
        return false
    }
}

object ChessAdapter {
    fun transition(position: ChessPosition, selected: UciMove): MoveTransition {
        val legal = ChessRules.legalMoves(position)
        val selectedMove = ChessMove.fromUci(selected)
        require(selectedMove in legal) { "Illegal move ${selected.value}" }

        val resultingByMove = legal.associateWith { ChessRules.applyUnchecked(position, it) }
        val after = resultingByMove.getValue(selectedMove)
        val alternatives = legal.map { move ->
            val result = resultingByMove.getValue(move)
            MoveAlternative(move.toUci(), RepetitionKey.of(result), result.halfmoveClock)
        }
        val legalAfter = ChessRules.legalMoves(after)
        return MoveTransition(
            move = selected,
            mover = position.sideToMove,
            resultingPositionKey = RepetitionKey.of(after),
            legalMovesAfter = legalAfter.size,
            sideToMoveInCheck = ChessRules.isInCheck(after),
            legalAlternativesBeforeMove = alternatives,
            halfmoveClockAfter = after.halfmoveClock,
            deadPositionAfter = DeadPositionDetector.isKnownDead(after),
            moveWasCapture = isCapture(position, selectedMove),
            materialAfter = material(after),
        )
    }

    fun replay(initialFen: String, moves: List<UciMove>): ChessPosition {
        var position = ChessPosition.fromFen(initialFen)
        moves.forEachIndexed { index, move ->
            try {
                position = ChessRules.apply(position, move)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Illegal replay move at ply ${index + 1}: ${move.value}", error)
            }
        }
        return position
    }

    fun perft(position: ChessPosition, depth: Int): Long {
        require(depth >= 0)
        if (depth == 0) return 1
        return ChessRules.legalMoves(position).sumOf { move ->
            perft(ChessRules.applyUnchecked(position, move), depth - 1)
        }
    }

    private fun isCapture(position: ChessPosition, move: ChessMove): Boolean {
        if (position[move.to] != null) return true
        val moving = position[move.from]
        return moving?.type == PieceType.PAWN && move.to == position.enPassantTarget &&
            move.from.file != move.to.file
    }

    private fun material(position: ChessPosition): MaterialScore {
        fun value(type: PieceType): Int = when (type) {
            PieceType.PAWN -> 1
            PieceType.KNIGHT, PieceType.BISHOP -> 3
            PieceType.ROOK -> 5
            PieceType.QUEEN -> 9
            PieceType.KING -> 0
        }
        var white = 0
        var black = 0
        for ((_, piece) in position.pieces()) {
            if (piece.side == com.drawlesschess.core.Side.WHITE) white += value(piece.type)
            else black += value(piece.type)
        }
        return MaterialScore(white, black)
    }
}
