package com.drawlesschess.core

data class GameScore(
    val points: Int,
    val maximumPoints: Int,
    val threatIndicationPenalty: Int,
    val hintPenalty: Int = 0,
    val undoPenalty: Int = 0,
    val timedPausePenalty: Int = 0,
    val scoringVersion: Int = GameScoring.SCORING_VERSION,
) {
    init {
        require(points >= 0)
        require(maximumPoints > 0)
        require(points <= maximumPoints)
        require(
            listOf(
                threatIndicationPenalty,
                hintPenalty,
                undoPenalty,
                timedPausePenalty,
            ).all { it in 0..maximumPoints },
        ) { "Score penalties must be between zero and the maximum score" }
        require(scoringVersion > 0) { "Scoring version must be positive" }
    }

    val totalPenalty: Int
        get() = listOf(
            threatIndicationPenalty,
            hintPenalty,
            undoPenalty,
            timedPausePenalty,
        ).sumOf(Int::toLong).coerceAtMost(maximumPoints.toLong()).toInt()
}

/** Versioned motivational game points. This score is deliberately separate from player Elo. */
object GameScoring {
    const val SCORING_VERSION = 1
    const val WIN_POINTS = 100
    const val HINT_PENALTY_PER_USE = 10
    const val UNDO_PENALTY_PER_USE = 10
    const val TIMED_PAUSE_PENALTY_PER_USE = 5
    const val THREAT_INDICATION_PENALTY = 5

    fun forResult(
        playerWon: Boolean,
        assistance: AssistanceCounts,
        timeControl: TimeControl = TimeControl.Untimed,
    ): GameScore {
        if (!playerWon) {
            return GameScore(
                points = 0,
                maximumPoints = WIN_POINTS,
                threatIndicationPenalty = 0,
            )
        }

        val hintPenalty = cappedPenalty(assistance.hints, HINT_PENALTY_PER_USE)
        val undoPenalty = cappedPenalty(assistance.undos, UNDO_PENALTY_PER_USE)
        val timedPausePenalty = if (timeControl is TimeControl.Clock) {
            cappedPenalty(assistance.pauses, TIMED_PAUSE_PENALTY_PER_USE)
        } else {
            0
        }
        val threatPenalty = if (assistance.threatIndication) THREAT_INDICATION_PENALTY else 0
        val totalPenalty = listOf(
            hintPenalty,
            undoPenalty,
            timedPausePenalty,
            threatPenalty,
        ).sumOf(Int::toLong).coerceAtMost(WIN_POINTS.toLong()).toInt()

        return GameScore(
            points = WIN_POINTS - totalPenalty,
            maximumPoints = WIN_POINTS,
            threatIndicationPenalty = threatPenalty,
            hintPenalty = hintPenalty,
            undoPenalty = undoPenalty,
            timedPausePenalty = timedPausePenalty,
        )
    }

    private fun cappedPenalty(count: Int, pointsPerUse: Int): Int =
        (count.toLong() * pointsPerUse.toLong()).coerceAtMost(WIN_POINTS.toLong()).toInt()
}
