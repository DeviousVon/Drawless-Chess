package com.drawlesschess.shared

import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules

/** Small exported surface used to prove that the native Apple host is executing shared game law. */
class SharedCoreSmoke {
    fun isHealthy(): Boolean {
        val start = ChessPosition.starting()
        return start.fen() == ChessPosition.START_FEN &&
            ChessRules.legalMoves(start).size == STARTING_LEGAL_MOVES &&
            ChessAdapter.perft(start, 2) == STARTING_PERFT_DEPTH_TWO &&
            RulesContractV1.drawless().schemaVersion == RULES_SCHEMA
    }

    fun verificationSummary(): String {
        check(isHealthy()) { "Shared Drawless core smoke verification failed" }
        return "$STARTING_LEGAL_MOVES legal moves • perft(2) $STARTING_PERFT_DEPTH_TWO • rules v$RULES_SCHEMA"
    }

    companion object {
        private const val STARTING_LEGAL_MOVES = 20
        private const val STARTING_PERFT_DEPTH_TWO = 400L
        private const val RULES_SCHEMA = 1
    }
}
