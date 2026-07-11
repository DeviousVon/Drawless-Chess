package com.drawlesschess.core.chess

import com.drawlesschess.core.UciMove
import kotlin.math.abs

object SanNotation {
    fun format(position: ChessPosition, uci: UciMove): String {
        val move = ChessMove.fromUci(uci)
        val legal = ChessRules.legalMoves(position)
        require(move in legal) { "Cannot format illegal move ${uci.value}" }
        val moving = position[move.from]!!

        val base = if (moving.type == PieceType.KING && abs(move.to.file - move.from.file) == 2) {
            if (move.to.file == 6) "O-O" else "O-O-O"
        } else {
            buildString {
                if (moving.type != PieceType.PAWN) append(pieceLetter(moving.type))
                val capture = isCapture(position, move)
                if (moving.type == PieceType.PAWN && capture) {
                    append(('a'.code + move.from.file).toChar())
                } else if (moving.type != PieceType.PAWN) {
                    append(disambiguation(position, legal, move, moving.type))
                }
                if (capture) append('x')
                append(move.to.algebraic)
                if (move.promotion != null) {
                    append('=')
                    append(pieceLetter(move.promotion))
                }
            }
        }

        val after = ChessRules.apply(position, move)
        return base + when {
            ChessRules.isCheckmate(after) -> "#"
            ChessRules.isInCheck(after) -> "+"
            else -> ""
        }
    }

    private fun disambiguation(
        position: ChessPosition,
        legal: List<ChessMove>,
        move: ChessMove,
        type: PieceType,
    ): String {
        val others = legal.filter { candidate ->
            candidate != move && candidate.to == move.to && position[candidate.from]?.type == type
        }
        if (others.isEmpty()) return ""
        val sameFile = others.any { it.from.file == move.from.file }
        val sameRank = others.any { it.from.rank == move.from.rank }
        return when {
            !sameFile -> ('a'.code + move.from.file).toChar().toString()
            !sameRank -> (move.from.rank + 1).toString()
            else -> move.from.algebraic
        }
    }

    private fun isCapture(position: ChessPosition, move: ChessMove): Boolean =
        position[move.to] != null ||
            (position[move.from]?.type == PieceType.PAWN && move.from.file != move.to.file)

    private fun pieceLetter(type: PieceType): Char = when (type) {
        PieceType.KNIGHT -> 'N'
        PieceType.BISHOP -> 'B'
        PieceType.ROOK -> 'R'
        PieceType.QUEEN -> 'Q'
        PieceType.KING -> 'K'
        PieceType.PAWN -> error("Pawns have no SAN piece letter")
    }
}
