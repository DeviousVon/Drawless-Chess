package com.drawlesschess.core.engine

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.RepetitionKey

object AnalysisRequests {
    fun hint(
        requestId: String,
        gameId: String,
        positionId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        mode: GameMode,
        moveTimeMillis: Long = 800,
        alternatives: Int = 3,
    ): EngineRequest {
        require(mode == GameMode.CASUAL) { "Hints are unavailable in rated games" }
        return EngineRequest(
            requestId = requestId,
            gameId = gameId,
            positionId = positionId,
            initialFen = initialFen,
            moves = moves,
            rules = rules,
            strength = EngineStrength.SkillLevel(20),
            limits = EngineLimits(moveTimeMillis, alternatives),
            purpose = EnginePurpose.HINT,
        )
    }
}

data class GameReviewPlan(
    val gameId: String,
    val requests: List<EngineRequest>,
) {
    init {
        require(gameId.isNotBlank())
        require(requests.all { it.gameId == gameId && it.purpose == EnginePurpose.REVIEW })
        require(requests.map { it.requestId }.distinct().size == requests.size)
    }
}

object GameReviewPlanner {
    /** Creates one full-strength request for every position in which a played move was chosen. */
    fun plan(
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        moveTimeMillis: Long = 1_200,
    ): GameReviewPlan {
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(moveTimeMillis > 0)
        val requests = moves.indices.map { ply ->
            val prefix = moves.take(ply)
            val position = ChessAdapter.replay(initialFen, prefix)
            val positionId = "$gameId:review:$ply:${RepetitionKey.of(position).value}"
            EngineRequest(
                requestId = "$gameId-review-$ply",
                gameId = gameId,
                positionId = positionId,
                initialFen = initialFen,
                moves = prefix,
                rules = rules,
                strength = EngineStrength.SkillLevel(20),
                limits = EngineLimits(moveTimeMillis, 1),
                purpose = EnginePurpose.REVIEW,
            )
        }
        // Replay the complete list as a validation gate, including the final move.
        ChessAdapter.replay(initialFen, moves)
        return GameReviewPlan(gameId, requests)
    }
}
