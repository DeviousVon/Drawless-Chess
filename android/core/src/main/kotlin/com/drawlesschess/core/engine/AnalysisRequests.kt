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
@ConsistentCopyVisibility
data class GameReviewRootKey internal constructor(
    val evidenceSchemaVersion: Int,
    val analysisVersion: Int,
    val gameId: String,
    val ply: Int,
    val normalizedInitialFen: String,
    val movesBefore: List<UciMove>,
    val rules: RulesContractV1,
    val positionId: String,
    val positionFen: String,
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
        require(ChessPosition.fromFen(normalizedInitialFen).fen() == normalizedInitialFen) {
            "Review root initial FEN is not canonical"
        }
        val position = ChessPosition.fromFen(positionFen)
        require(position.fen() == positionFen) { "Review root position FEN is not canonical" }
        require(positionId == "$gameId:review:${ply - 1}:${RepetitionKey.of(position).value}") {
            "Review root key position identity does not match its canonical position"
        }
    }
}

/**
 * A root can be created only by [GameReviewPlanner], which validates its history while advancing
 * one shared position cursor. Keeping construction structural is important: review startup copies
 * roots with fresh attempt IDs, and replaying every prefix in each constructor made that path
 * quadratic before the first engine request could start.
 */
@ConsistentCopyVisibility
data class GameReviewRoot internal constructor(
    val ply: Int,
    val request: EngineRequest,
    val key: GameReviewRootKey,
) {
    init {
        require(ply >= 1 && request.moves.size == ply - 1)
        require(request.purpose == EnginePurpose.REVIEW)
        require(
            request.positionId.startsWith("${request.gameId}:review:${ply - 1}:") &&
                request.positionId.last() != ':',
        ) { "Review root position identity is malformed" }
        require(key.gameId == request.gameId && key.ply == ply)
        require(key.normalizedInitialFen == ChessPosition.fromFen(request.initialFen).fen())
        require(key.movesBefore == request.moves && key.rules == request.rules)
        require(key.positionId == request.positionId)
        require(key.strength == request.strength && key.limits == request.limits)
        require(key.purpose == request.purpose)
    }

    fun seed(response: EngineResponse): SeededGameReviewRoot {
        require(response.matches(request)) {
            "Seeded review response identity does not match its canonical root request"
        }
        require(response.engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
            "Seeded Game Review evidence requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
        }
        return SeededGameReviewRoot(key, response)
    }
}

/** Constructed only through [GameReviewRoot.seed], after the original response identity matches. */
class SeededGameReviewRoot internal constructor(
    val key: GameReviewRootKey,
    val response: EngineResponse,
)

/**
 * Stable identity for the fallback search immediately after one played move. The parent root and
 * played move are both part of the key: an undo can revisit the same root and choose a different
 * continuation, and evidence for those continuations must never be interchanged.
 */
@ConsistentCopyVisibility
data class GameReviewAdjacentKey internal constructor(
    val rootKey: GameReviewRootKey,
    val playedMove: UciMove,
    val positionId: String,
    val positionFen: String,
) {
    init {
        val before = ChessPosition.fromFen(rootKey.positionFen)
        val after = ChessRules.apply(before, playedMove)
        require(after.fen() == positionFen) { "Adjacent review position FEN is not canonical" }
        require(
            positionId ==
                "${rootKey.gameId}:review:${rootKey.ply}:${RepetitionKey.of(after).value}",
        ) { "Adjacent review position identity does not match its parent root and played move" }
    }
}

/** One exact fallback request which can be completed speculatively during a later player turn. */
@ConsistentCopyVisibility
data class GameReviewAdjacentRoot internal constructor(
    val request: EngineRequest,
    val key: GameReviewAdjacentKey,
) {
    init {
        val rootKey = key.rootKey
        require(request.gameId == rootKey.gameId)
        require(request.initialFen == rootKey.normalizedInitialFen)
        require(request.moves == rootKey.movesBefore + key.playedMove)
        require(request.rules == rootKey.rules)
        require(request.positionId == key.positionId)
        require(request.strength == rootKey.strength && request.limits == rootKey.limits)
        require(request.purpose == EnginePurpose.REVIEW)
    }

    fun seed(response: EngineResponse): SeededGameReviewAdjacentRoot {
        require(response.matches(request)) {
            "Seeded adjacent response identity does not match its canonical fallback request"
        }
        require(response.engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
            "Seeded Game Review evidence requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
        }
        return SeededGameReviewAdjacentRoot(key, response)
    }
}

/** Constructed only through [GameReviewAdjacentRoot.seed] after exact response validation. */
class SeededGameReviewAdjacentRoot internal constructor(
    val key: GameReviewAdjacentKey,
    val response: EngineResponse,
)

@ConsistentCopyVisibility
data class PlayerGameReviewPlan internal constructor(
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
        require(roots.all { it.ply <= gameMoves.size })
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
        var position = ChessPosition.fromFen(initialFen)
        val prefix = ArrayList<UciMove>(moves.size)
        val requests = ArrayList<EngineRequest>(moves.size)
        moves.forEachIndexed { index, move ->
            requests += reviewRequest(
                requestId = "$gameId-review-$index",
                gameId = gameId,
                initialFen = initialFen,
                prefix = prefix,
                rules = rules,
                moveTimeMillis = moveTimeMillis,
                position = position,
            )
            position = applyReviewMove(position, move, index)
            prefix += move
        }
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
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(moveTimeMillis > 0)
        var position = ChessPosition.fromFen(initialFen)
        val normalizedInitialFen = position.fen()
        val prefix = ArrayList<UciMove>(moves.size)
        val roots = ArrayList<GameReviewRoot>((moves.size + 1) / 2)
        moves.forEachIndexed { index, move ->
            if (position.sideToMove == playerSide) {
                roots += reviewRoot(
                    ply = index + 1,
                    requestId = "$gameId-review-$index",
                    gameId = gameId,
                    initialFen = initialFen,
                    normalizedInitialFen = normalizedInitialFen,
                    prefix = prefix,
                    rules = rules,
                    moveTimeMillis = moveTimeMillis,
                    position = position,
                )
            }
            position = applyReviewMove(position, move, index)
            prefix += move
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
        return reviewRoot(
            ply = ply,
            requestId = requestId,
            gameId = gameId,
            initialFen = initialFen,
            normalizedInitialFen = ChessPosition.fromFen(initialFen).fen(),
            prefix = moves,
            rules = rules,
            moveTimeMillis = moveTimeMillis,
            position = position,
        )
    }

    /**
     * Builds the current speculative root from the coordinator's already-validated position.
     *
     * Foreground play advances one position cursor as moves are committed. Replaying the complete
     * history again for every idle review request made a game quadratic and, on slower devices,
     * put hundreds of milliseconds of reconstruction work in the input path.
     */
    internal fun playerRootAtPosition(
        requestId: String,
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        position: ChessPosition,
        moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
    ): GameReviewRoot {
        require(requestId.isNotBlank() && gameId.isNotBlank() && initialFen.isNotBlank() && moveTimeMillis > 0)
        return reviewRoot(
            ply = moves.size + 1,
            requestId = requestId,
            gameId = gameId,
            initialFen = initialFen,
            normalizedInitialFen = ChessPosition.fromFen(initialFen).fen(),
            prefix = moves,
            rules = rules,
            moveTimeMillis = moveTimeMillis,
            position = position,
        )
    }

    fun adjacentRoot(
        requestId: String,
        root: GameReviewRoot,
        playedMove: UciMove,
    ): GameReviewAdjacentRoot = adjacentRoot(requestId, root.key, playedMove)

    /**
     * Recreates an adjacent fallback directly from a completed root's stable key.
     *
     * The key already contains the canonical pre-move FEN and exact move prefix, so historical
     * roots never need to be replayed merely to schedule their one-ply fallback.
     */
    internal fun adjacentRoot(
        requestId: String,
        rootKey: GameReviewRootKey,
        playedMove: UciMove,
    ): GameReviewAdjacentRoot {
        require(requestId.isNotBlank())
        val before = ChessPosition.fromFen(rootKey.positionFen)
        val after = ChessRules.apply(before, playedMove)
        val positionId =
            "${rootKey.gameId}:review:${rootKey.ply}:${RepetitionKey.of(after).value}"
        return GameReviewAdjacentRoot(
            request = EngineRequest(
                requestId = requestId,
                gameId = rootKey.gameId,
                positionId = positionId,
                initialFen = rootKey.normalizedInitialFen,
                moves = rootKey.movesBefore + playedMove,
                rules = rootKey.rules,
                strength = rootKey.strength,
                limits = rootKey.limits,
                purpose = EnginePurpose.REVIEW,
            ),
            key = GameReviewAdjacentKey(
                rootKey = rootKey,
                playedMove = playedMove,
                positionId = positionId,
                positionFen = after.fen(),
            ),
        )
    }

    private fun reviewRoot(
        ply: Int,
        requestId: String,
        gameId: String,
        initialFen: String,
        normalizedInitialFen: String,
        prefix: List<UciMove>,
        rules: RulesContractV1,
        moveTimeMillis: Long,
        position: ChessPosition,
    ): GameReviewRoot {
        val request = reviewRequest(
            requestId = requestId,
            gameId = gameId,
            initialFen = initialFen,
            prefix = prefix,
            rules = rules,
            moveTimeMillis = moveTimeMillis,
            position = position,
        )
        return GameReviewRoot(
            ply = ply,
            request = request,
            key = GameReviewRootKey(
                evidenceSchemaVersion = REVIEW_EVIDENCE_SCHEMA_VERSION,
                analysisVersion = REVIEW_ANALYSIS_VERSION,
                gameId = gameId,
                ply = ply,
                normalizedInitialFen = normalizedInitialFen,
                movesBefore = request.moves,
                rules = rules,
                positionId = request.positionId,
                positionFen = position.fen(),
                strength = request.strength,
                limits = request.limits,
                purpose = request.purpose,
            ),
        )
    }

    private fun reviewRequest(
        requestId: String,
        gameId: String,
        initialFen: String,
        prefix: List<UciMove>,
        rules: RulesContractV1,
        moveTimeMillis: Long,
        position: ChessPosition,
    ) = EngineRequest(
        requestId = requestId,
        gameId = gameId,
        positionId = "$gameId:review:${prefix.size}:${RepetitionKey.of(position).value}",
        initialFen = initialFen,
        moves = prefix.toList(),
        rules = rules,
        strength = EngineStrength.SkillLevel(20),
        limits = EngineLimits(moveTimeMillis, GAME_REVIEW_MULTI_PV),
        purpose = EnginePurpose.REVIEW,
    )

    private fun applyReviewMove(
        position: ChessPosition,
        move: UciMove,
        zeroBasedPly: Int,
    ): ChessPosition = try {
        ChessRules.apply(position, move)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException(
            "Illegal replay move at ply ${zeroBasedPly + 1}: ${move.value}",
            error,
        )
    }
}
