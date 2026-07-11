package com.drawlesschess.core

enum class StalematePolicy {
    TRAPPED_PLAYER_LOSES,
    TRAPPED_PLAYER_WINS,
}

enum class DeadPositionPolicy {
    MATERIAL_VICTORY,
    FINAL_CAPTURE_VICTORY,
}

enum class FiftyMovePolicy {
    DISABLED,
    COMPLETING_PLAYER_LOSES,
    FORCED_MOVE_EXCEPTION,
}

enum class EndReason {
    CHECKMATE,
    STALEMATE,
    REPETITION,
    DEAD_POSITION_MATERIAL,
    DEAD_POSITION_FINAL_CAPTURE,
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
            fiftyMove: FiftyMovePolicy = FiftyMovePolicy.DISABLED,
        ) = RulesContractV1(
            preset = Preset.DRAWLESS,
            stalemate = StalematePolicy.TRAPPED_PLAYER_LOSES,
            deadPosition = deadPosition,
            fiftyMove = fiftyMove,
        )

        fun escape(
            deadPosition: DeadPositionPolicy = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove: FiftyMovePolicy = FiftyMovePolicy.DISABLED,
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
)

data class GameOutcome(
    val winner: Side,
    val loser: Side = winner.opposite(),
    val reason: EndReason,
    val explanation: String,
)

class DrawlessAdjudicator {
    fun adjudicate(rules: RulesContractV1, facts: PositionFacts): GameOutcome? {
        val sideToMove = facts.mover.opposite()

        if (facts.legalMovesAfter == 0) {
            if (facts.sideToMoveInCheck) {
                return GameOutcome(facts.mover, reason = EndReason.CHECKMATE,
                    explanation = "${facts.mover} wins by checkmate")
            }
            val winner = if (rules.stalemate == StalematePolicy.TRAPPED_PLAYER_LOSES) {
                facts.mover
            } else {
                sideToMove
            }
            return GameOutcome(winner, reason = EndReason.STALEMATE,
                explanation = "$winner wins under the ${rules.preset.name.lowercase()} stalemate rule")
        }

        if (facts.positionOccurrenceCount >= rules.repetitionThreshold) {
            val loser = if (facts.repetitionAvoidingAlternativesBeforeMove == 0) {
                sideToMove
            } else {
                facts.mover
            }
            return GameOutcome(loser.opposite(), reason = EndReason.REPETITION,
                explanation = "$loser loses by causing a third repetition")
        }

        if (facts.deadPositionAfter) {
            if (rules.deadPosition == DeadPositionPolicy.FINAL_CAPTURE_VICTORY) {
                check(facts.moveWasCapture) {
                    "Final-capture adjudication requires the transition move to be a capture"
                }
                return GameOutcome(facts.mover, reason = EndReason.DEAD_POSITION_FINAL_CAPTURE,
                    explanation = "${facts.mover} wins by making the final meaningful capture")
            }
            val winner = when {
                facts.materialAfter.white > facts.materialAfter.black -> Side.WHITE
                facts.materialAfter.black > facts.materialAfter.white -> Side.BLACK
                else -> facts.mover
            }
            return GameOutcome(winner, reason = EndReason.DEAD_POSITION_MATERIAL,
                explanation = "$winner wins the dead position by material adjudication")
        }

        if (facts.halfmoveClockAfter >= 100 && rules.fiftyMove != FiftyMovePolicy.DISABLED) {
            val forced = rules.fiftyMove == FiftyMovePolicy.FORCED_MOVE_EXCEPTION &&
                facts.fiftyMoveAvoidingAlternativesBeforeMove == 0
            val loser = if (forced) sideToMove else facts.mover
            return GameOutcome(loser.opposite(), reason = EndReason.FIFTY_MOVE_LIMIT,
                explanation = "$loser loses by reaching the configured 50-move limit")
        }

        return null
    }
}
