package com.drawlesschess.core.engine

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey

const val DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS = 350L
const val GAME_REVIEW_MULTI_PV = 3

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

/**
 * Stable identity for one unconstrained review search at a played-move root. Request IDs are
 * deliberately excluded: a foreground pre-analysis request receives a different attempt ID when
 * it is consumed later, but every evaluation-affecting input must still match this key exactly.
 */
data class GameReviewRootKey(
    val evidenceSchemaVersion: Int,
    val analysisVersion: Int,
    val gameId: String,
    val ply: Int,
    val normalizedInitialFen: String,
    val movesBefore: List<UciMove>,
    val rules: RulesContractV1,
    val positionId: String,
    val strength: EngineStrength,
    val limits: EngineLimits,
    val purpose: EnginePurpose,
) {
    init {
        require(gameId.isNotBlank() && ply >= 1)
        require(movesBefore.size == ply - 1)
        require(purpose == EnginePurpose.REVIEW)
        require(strength == EngineStrength.SkillLevel(20))
        require(limits.multiPv == GAME_REVIEW_MULTI_PV)
        var position = ChessPosition.fromFen(normalizedInitialFen)
        require(position.fen() == normalizedInitialFen) { "Review root initial FEN is not canonical" }
        var session = GameSession.newGame(gameId, rules, RepetitionKey.of(position), position.sideToMove)
        movesBefore.forEachIndexed { index, move ->
            require(session.outcome == null) { "Review root history continues after ply $index" }
            val transition = ChessAdapter.transition(position, move)
            position = ChessRules.apply(position, move)
            session = session.apply(transition)
        }
        require(session.outcome == null) { "A review decision root cannot already be terminal" }
        require(positionId == "$gameId:review:${ply - 1}:${RepetitionKey.of(position).value}") {
            "Review root key position identity is not canonical for its history"
        }
    }
}

data class GameReviewRoot(
    val ply: Int,
    val request: EngineRequest,
) {
    init {
        require(ply >= 1 && request.moves.size == ply - 1)
        require(request.purpose == EnginePurpose.REVIEW)
        val position = ChessAdapter.replay(request.initialFen, request.moves)
        require(
            request.positionId ==
                "${request.gameId}:review:${ply - 1}:${RepetitionKey.of(position).value}",
        ) { "Review root position identity is not canonical for its history" }
    }

    val key: GameReviewRootKey = GameReviewRootKey(
        evidenceSchemaVersion = REVIEW_EVIDENCE_SCHEMA_VERSION,
        analysisVersion = REVIEW_ANALYSIS_VERSION,
        gameId = request.gameId,
        ply = ply,
        normalizedInitialFen = ChessPosition.fromFen(request.initialFen).fen(),
        movesBefore = request.moves.toList(),
        rules = request.rules,
        positionId = request.positionId,
        strength = request.strength,
        limits = request.limits,
        purpose = request.purpose,
    )

    fun seed(response: EngineResponse): SeededGameReviewRoot {
        require(response.matches(request)) {
            "Seeded review response identity does not match its canonical root request"
        }
        return SeededGameReviewRoot(key, response)
    }
}

/** Constructed only through [GameReviewRoot.seed], after the original response identity matches. */
class SeededGameReviewRoot internal constructor(
    val key: GameReviewRootKey,
    val response: EngineResponse,
)

data class PlayerGameReviewPlan(
    val gameId: String,
    val playerSide: Side,
    val gameMoves: List<UciMove>,
    val roots: List<GameReviewRoot>,
) {
    init {
        require(gameId.isNotBlank())
        require(roots.map { it.ply } == roots.map { it.ply }.sorted())
        require(roots.map { it.ply }.distinct().size == roots.size)
        require(roots.all { it.request.gameId == gameId })
        require(roots.all { root -> root.request.moves == gameMoves.take(root.ply - 1) })
        require(roots.all { root ->
            ChessAdapter.replay(root.request.initialFen, root.request.moves).sideToMove == playerSide
        })
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
                limits = EngineLimits(moveTimeMillis, GAME_REVIEW_MULTI_PV),
                purpose = EnginePurpose.REVIEW,
            )
        }
        // Replay the complete list as a validation gate, including the final move.
        ChessAdapter.replay(initialFen, moves)
        return GameReviewPlan(gameId, requests)
    }

    /**
     * Creates sparse player coverage while retaining the complete canonical move list. A root can
     * also be built during foreground play with [playerRoot] and safely seeded into the later run.
     */
    fun playerPlan(
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        playerSide: Side,
        moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
    ): PlayerGameReviewPlan {
        val all = plan(gameId, initialFen, moves, rules, moveTimeMillis)
        val roots = all.requests.mapIndexedNotNull { index, request ->
            val mover = ChessAdapter.replay(initialFen, moves.take(index)).sideToMove
            request.takeIf { mover == playerSide }?.let { GameReviewRoot(index + 1, it) }
        }
        return PlayerGameReviewPlan(gameId, playerSide, moves.toList(), roots)
    }

    fun playerRoot(
        requestId: String,
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
    ): GameReviewRoot {
        require(requestId.isNotBlank() && gameId.isNotBlank() && initialFen.isNotBlank() && moveTimeMillis > 0)
        val position = ChessAdapter.replay(initialFen, moves)
        val ply = moves.size + 1
        return GameReviewRoot(
            ply = ply,
            request = EngineRequest(
                requestId = requestId,
                gameId = gameId,
                positionId = "$gameId:review:${ply - 1}:${RepetitionKey.of(position).value}",
                initialFen = initialFen,
                moves = moves.toList(),
                rules = rules,
                strength = EngineStrength.SkillLevel(20),
                limits = EngineLimits(moveTimeMillis, GAME_REVIEW_MULTI_PV),
                purpose = EnginePurpose.REVIEW,
            ),
        )
    }
}
