package com.drawlesschess.core

data class MoveRecord(
    val ply: Int,
    val move: UciMove,
    val mover: Side,
    val resultingPositionKey: PositionKey,
    val halfmoveClockAfter: Int,
)

data class GameSession(
    val gameId: String,
    val rules: RulesContractV1,
    val sideToMove: Side,
    val history: PositionHistory,
    val moves: List<MoveRecord> = emptyList(),
    val outcome: GameOutcome? = null,
) {
    init {
        require(gameId.isNotBlank()) { "Game ID cannot be blank" }
    }

    val positionId: String get() = "$gameId:${moves.size}:${history.current.value}"

    fun apply(
        transition: MoveTransition,
        adjudicator: DrawlessAdjudicator = DrawlessAdjudicator(),
    ): GameSession {
        check(outcome == null) { "Cannot apply a move to a completed game" }
        require(transition.mover == sideToMove) { "Transition mover does not match side to move" }

        val occurrenceAfter = history.occurrences(transition.resultingPositionKey) + 1
        val repetitionAvoiding = transition.legalAlternativesBeforeMove.count { alternative ->
            history.occurrences(alternative.resultingPositionKey) + 1 < rules.repetitionThreshold
        }
        val fiftyMoveAvoiding = transition.legalAlternativesBeforeMove.count { alternative ->
            alternative.resultingHalfmoveClock < 100
        }
        val facts = PositionFacts(
            mover = transition.mover,
            legalMovesAfter = transition.legalMovesAfter,
            sideToMoveInCheck = transition.sideToMoveInCheck,
            positionOccurrenceCount = occurrenceAfter,
            repetitionAvoidingAlternativesBeforeMove = repetitionAvoiding,
            halfmoveClockAfter = transition.halfmoveClockAfter,
            fiftyMoveAvoidingAlternativesBeforeMove = fiftyMoveAvoiding,
            deadPositionAfter = transition.deadPositionAfter,
            moveWasCapture = transition.moveWasCapture,
            materialAfter = transition.materialAfter,
        )
        val nextOutcome = adjudicator.adjudicate(rules, facts)
        val nextRecord = MoveRecord(
            ply = moves.size + 1,
            move = transition.move,
            mover = transition.mover,
            resultingPositionKey = transition.resultingPositionKey,
            halfmoveClockAfter = transition.halfmoveClockAfter,
        )
        return copy(
            sideToMove = sideToMove.opposite(),
            history = history.record(transition.resultingPositionKey),
            moves = moves + nextRecord,
            outcome = nextOutcome,
        )
    }

    companion object {
        fun newGame(
            gameId: String,
            rules: RulesContractV1,
            initialPositionKey: PositionKey,
            sideToMove: Side = Side.WHITE,
        ): GameSession = GameSession(
            gameId = gameId,
            rules = rules,
            sideToMove = sideToMove,
            history = PositionHistory.startingAt(initialPositionKey),
        )
    }
}
