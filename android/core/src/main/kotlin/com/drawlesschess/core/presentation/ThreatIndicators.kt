package com.drawlesschess.core.presentation

import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.Square

/** Finds the player's occupied squares that are currently attacked by the opponent. */
object ThreatIndicators {
    fun threatenedPieces(position: ChessPosition, humanSide: Side): Set<Square> =
        position.pieces()
            .asSequence()
            .filter { (_, piece) -> piece.side == humanSide }
            .map { (square, _) -> square }
            .filter { square ->
                ChessRules.isSquareAttacked(position, square, humanSide.opposite())
            }
            .toSet()
}
