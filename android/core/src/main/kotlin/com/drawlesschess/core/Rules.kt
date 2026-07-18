package com.drawlesschess.core

enum class StalematePolicy {
    TRAPPED_PLAYER_LOSES,
    TRAPPED_PLAYER_WINS,
}

enum class DeadPositionPolicy {
    MATERIAL_VICTORY,
    FINAL_CAPTURE_VICTORY,
}

enum class BareKingPolicy {
    CONTINUE,
    BARE_KING_LOSES,
}

enum class FiftyMovePolicy {
    DISABLED,
    COMPLETING_PLAYER_LOSES,
    FORCED_MOVE_EXCEPTION,
    MATERIAL_VICTORY,
}

enum class EndReason {
    CHECKMATE,
    STALEMATE,
    REPETITION,
    DEAD_POSITION_MATERIAL,
    DEAD_POSITION_FINAL_CAPTURE,
    BARE_KING,
    FIFTY_MOVE_LIMIT,
    RESIGNATION,
    TIMEOUT,
}

data class MaterialValues(
    val pawn: Int = 1,
    val knight: Int = 3,
    val bishop: Int = 3,
    val rook: Int = 5,
    val queen: Int = 9,
) {
    init {
        require(pawn == 1 && knight == 3 && bishop == 3 && rook == 5 && queen == 9) {
            "Rules contract v1 requires standard material values"
        }
    }
}

data class RulesContractV1(
    val preset: Preset,
    val stalemate: StalematePolicy,
    val deadPosition: DeadPositionPolicy,
    val fiftyMove: FiftyMovePolicy,
    val repetitionThreshold: Int = 3,
    val completingPlayerLosesRepetition: Boolean = true,
    val forcedRepetitionException: Boolean = true,
    val materialValues: MaterialValues = MaterialValues(),
    val bareKing: BareKingPolicy = BareKingPolicy.BARE_KING_LOSES,
) {
    val schemaVersion: Int = 1

    init {
        require(repetitionThreshold == 3) { "Rules contract v1 fixes repetition at three occurrences" }
        require(completingPlayerLosesRepetition) { "Rules contract v1 requires completing-player loss" }
        require(forcedRepetitionException) { "Rules contract v1 requires the forced-move exception" }
        require(
            (preset == Preset.DRAWLESS && stalemate == StalematePolicy.TRAPPED_PLAYER_LOSES) ||
                (preset == Preset.ESCAPE && stalemate == StalematePolicy.TRAPPED_PLAYER_WINS),
        ) { "Preset and stalemate policy disagree" }
    }

    enum class Preset { DRAWLESS, ESCAPE }

    companion object {
        fun drawless(
            deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
        ) = RulesContractV1(
            preset = Preset.DRAWLESS,
            stalemate = StalematePolicy.TRAPPED_PLAYER_LOSES,
            deadPosition = deadPosition,
            fiftyMove = fiftyMove,
        )

        fun escape(
            deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove: FiftyMovePolicy = FiftyMovePolicy.MATERIAL_VICTORY,
        ) = RulesContractV1(
            preset = Preset.ESCAPE,
            stalemate = StalematePolicy.TRAPPED_PLAYER_WINS,
            deadPosition = deadPosition,
            fiftyMove = fiftyMove,
        )
    }
}

data class PositionFacts(
    val mover: Side,
    val legalMovesAfter: Int,
    val sideToMoveInCheck: Boolean,
    val positionOccurrenceCount: Int,
    val repetitionAvoidingAlternativesBeforeMove: Int,
    val halfmoveClockAfter: Int,
    val fiftyMoveAvoidingAlternativesBeforeMove: Int,
    val deadPositionAfter: Boolean,
    val moveWasCapture: Boolean,
    val materialAfter: MaterialScore,
    val lastCaptureBy: Side?,
)

data class GameOutcome(
    val winner: Side,
    val loser: Side = winner.opposite(),
    val reason: EndReason,
) {
    init {
        require(loser == winner.opposite()) { "Outcome winner and loser must be opposite sides" }
    }
}

class DrawlessAdjudicator {
    fun adjudicate(rules: RulesContractV1, facts: PositionFacts): GameOutcome? {
        val sideToMove = facts.mover.opposite()

        if (facts.legalMovesAfter == 0) {
            if (facts.sideToMoveInCheck) {
                return GameOutcome(facts.mover, reason = EndReason.CHECKMATE)
            }
            val winner = if (rules.stalemate == StalematePolicy.TRAPPED_PLAYER_LOSES) {
                facts.mover
            } else {
                sideToMove
            }
            return GameOutcome(winner, reason = EndReason.STALEMATE)
        }

        if (facts.positionOccurrenceCount >= rules.repetitionThreshold) {
            val loser = if (facts.repetitionAvoidingAlternativesBeforeMove == 0) {
                sideToMove
            } else {
                facts.mover
            }
            return GameOutcome(loser.opposite(), reason = EndReason.REPETITION)
        }

        if (rules.bareKing == BareKingPolicy.BARE_KING_LOSES) {
            val winner = when {
                facts.materialAfter.white == 0 && facts.materialAfter.black > 0 -> Side.BLACK
                facts.materialAfter.black == 0 && facts.materialAfter.white > 0 -> Side.WHITE
                else -> null
            }
            if (winner != null) return GameOutcome(winner, reason = EndReason.BARE_KING)
        }

        if (facts.deadPositionAfter) {
            if (rules.deadPosition == DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
                check(facts.moveWasCapture) {
                    "Final-capture adjudication requires the transition move to be a capture"
                }
                return GameOutcome(facts.mover, reason = EndReason.DEAD_POSITION_FINAL_CAPTURE)
            }
            val winner = when {
                facts.materialAfter.white > facts.materialAfter.black -> Side.WHITE
                facts.materialAfter.black > facts.materialAfter.white -> Side.BLACK
                else -> facts.mover
            }
            return GameOutcome(winner, reason = EndReason.DEAD_POSITION_MATERIAL)
        }

        if (facts.halfmoveClockAfter >= 100) {
            val winner = when (rules.fiftyMove) {
                FiftyMovePolicy.DISABLED -> null
                FiftyMovePolicy.COMPLETING_PLAYER_LOSES -> sideToMove
                FiftyMovePolicy.FORCED_MOVE_EXCEPTION -> {
                    if (facts.fiftyMoveAvoidingAlternativesBeforeMove == 0) facts.mover
                    else sideToMove
                }
                FiftyMovePolicy.MATERIAL_VICTORY -> when {
                    facts.materialAfter.white > facts.materialAfter.black -> Side.WHITE
                    facts.materialAfter.black > facts.materialAfter.white -> Side.BLACK
                    facts.lastCaptureBy != null -> facts.lastCaptureBy
                    facts.fiftyMoveAvoidingAlternativesBeforeMove == 0 -> facts.mover
                    else -> sideToMove
                }
            }
            if (winner != null) {
                return GameOutcome(winner, reason = EndReason.FIFTY_MOVE_LIMIT)
            }
        }

        return null
    }
}
