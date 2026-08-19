package com.drawlesschess.core.engine

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.ConcurrentLock
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.EngineWdl
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import kotlin.math.pow

const val REVIEW_EVIDENCE_SCHEMA_VERSION = 1
const val REVIEW_ANALYSIS_VERSION = 2
const val REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION = 2

/**
 * Player coverage is an explicit aggregate scope, so it does not change the evidence schema.
 * Analysis version 2 records the semantic boundary introduced by full native RulesContractV1
 * patch-v2 search. Replacing the root/adjacent evidence contract with constrained-root played
 * searches will require another analysis version.
 */
sealed interface GameReviewScope {
    fun includes(side: Side): Boolean

    data object AllMoves : GameReviewScope {
        override fun includes(side: Side): Boolean = true
    }

    data class PlayerMoves(val side: Side) : GameReviewScope {
        override fun includes(side: Side): Boolean = this.side == side
    }
}

enum class ReviewMoveQuality {
    BEST,
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER,
}

/** An evaluation relative to the mover in [ReviewedMove]. */
sealed interface ReviewEvaluation {
    data class Centipawns(val value: Int) : ReviewEvaluation
    data class Mate(val mateIn: Int) : ReviewEvaluation
    data class Terminal(val winner: Side) : ReviewEvaluation
}

enum class ReviewScoreSource {
    WDL,
    MATE,
    CENTIPAWNS,
    TERMINAL,
}

enum class ReviewLineOrigin {
    ROOT_MULTIPV,
    ADJACENT_POSITION,
    AUTHORITATIVE_TERMINAL,
    AUTHORITATIVE_SAFE_ALTERNATIVE,
    FORCED_LEGAL_MOVE,
    FORCED_LOSS_EQUIVALENCE,
}

/**
 * Native patch v2 models every selectable RulesContractV1 policy throughout search. The core
 * continues to validate principal variations and recognize immediate authoritative terminal
 * transitions when assembling review evidence.
 */
enum class ReviewRuleFidelity {
    FULL_NATIVE_RULES_CONTRACT_V1_PATCH_V2,
}

/** One engine candidate, with expected points already normalized to the decision maker. */
data class ReviewLine(
    val rank: Int,
    val move: UciMove,
    val evaluation: ReviewEvaluation?,
    val expectedPoints: Double?,
    val source: ReviewScoreSource?,
    val bound: EngineScoreBound,
    val depth: Int?,
    val moves: List<UciMove>,
    val origin: ReviewLineOrigin = ReviewLineOrigin.ROOT_MULTIPV,
) {
    init {
        require(rank >= 1)
        require(moves.isNotEmpty() && moves.first() == move)
        require(expectedPoints == null || expectedPoints in 0.0..1.0)
        require((evaluation == null) == (source == null))
        require(expectedPoints == null || (evaluation != null && bound == EngineScoreBound.EXACT))
        when (source) {
            ReviewScoreSource.WDL, ReviewScoreSource.CENTIPAWNS ->
                require(evaluation is ReviewEvaluation.Centipawns)
            ReviewScoreSource.MATE -> require(evaluation is ReviewEvaluation.Mate)
            ReviewScoreSource.TERMINAL -> require(evaluation is ReviewEvaluation.Terminal)
            null -> Unit
        }
    }
}

data class ReviewMoveEvidence(
    val evidenceSchemaVersion: Int = REVIEW_EVIDENCE_SCHEMA_VERSION,
    val analysisVersion: Int = REVIEW_ANALYSIS_VERSION,
    val gradingPolicyVersion: Int = ReviewGradingPolicy.CURRENT.version,
    val lines: List<ReviewLine>,
    val bestLine: ReviewLine,
    val playedLine: ReviewLine,
    val playedLineRank: Int?,
    val legalMoveCount: Int,
    val forced: Boolean,
    val usedAdjacentFallback: Boolean,
    val ruleFidelity: ReviewRuleFidelity =
        ReviewRuleFidelity.FULL_NATIVE_RULES_CONTRACT_V1_PATCH_V2,
) {
    init {
        require(evidenceSchemaVersion > 0 && analysisVersion > 0 && gradingPolicyVersion > 0)
        require(legalMoveCount >= 1)
        require(forced == (legalMoveCount == 1))
        require(lines.isNotEmpty() && lines.first().rank == 1)
        require(lines == lines.sortedBy { it.rank })
        require(lines.map { it.rank }.distinct().size == lines.size)
        require(lines.all { it.origin == ReviewLineOrigin.ROOT_MULTIPV })
        require(bestLine in lines || bestLine.origin != ReviewLineOrigin.ROOT_MULTIPV)
        require(playedLine in lines || playedLine.origin != ReviewLineOrigin.ROOT_MULTIPV)
        require(
            playedLineRank == null ||
                lines.any { it.rank == playedLineRank && it == playedLine },
        )
        require(
            (playedLine.origin == ReviewLineOrigin.ROOT_MULTIPV) == (playedLineRank != null),
        )
        require(playedLineRank == null || playedLineRank == playedLine.rank)
        require(usedAdjacentFallback == (playedLine.origin == ReviewLineOrigin.ADJACENT_POSITION))
    }
}

data class ReviewGradingPolicy(
    val version: Int,
    val bestMaximumLoss: Double,
    val goodMaximumLoss: Double,
    val inaccuracyMaximumLoss: Double,
    val mistakeMaximumLoss: Double,
) {
    init {
        require(version > 0)
        require(
            0.0 <= bestMaximumLoss && bestMaximumLoss <= goodMaximumLoss &&
                goodMaximumLoss <= inaccuracyMaximumLoss &&
                inaccuracyMaximumLoss <= mistakeMaximumLoss && mistakeMaximumLoss <= 1.0,
        )
    }

    fun classify(loss: Double): ReviewMoveQuality = when {
        loss <= bestMaximumLoss -> ReviewMoveQuality.BEST
        loss <= goodMaximumLoss -> ReviewMoveQuality.GOOD
        loss <= inaccuracyMaximumLoss -> ReviewMoveQuality.INACCURACY
        loss <= mistakeMaximumLoss -> ReviewMoveQuality.MISTAKE
        else -> ReviewMoveQuality.BLUNDER
    }

    companion object {
        val CURRENT = ReviewGradingPolicy(
            version = 1,
            bestMaximumLoss = 0.02,
            goodMaximumLoss = 0.06,
            inaccuracyMaximumLoss = 0.12,
            mistakeMaximumLoss = 0.22,
        )
    }
}

data class ReviewSideSummary(
    val side: Side,
    val gradedMoves: Int,
    val movesWithExpectedPointLoss: Int,
    val meanExpectedPointLoss: Double?,
    val qualityCounts: Map<ReviewMoveQuality, Int>,
) {
    init {
        require(gradedMoves >= 0 && movesWithExpectedPointLoss in 0..gradedMoves)
        require(meanExpectedPointLoss == null || meanExpectedPointLoss in 0.0..1.0)
        require(qualityCounts.values.all { it >= 0 })
        require(qualityCounts.values.sum() == gradedMoves)
    }
}

data class GameReviewSummary(
    val white: ReviewSideSummary,
    val black: ReviewSideSummary,
) {
    init {
        require(white.side == Side.WHITE && black.side == Side.BLACK)
    }

    companion object {
        fun from(moves: List<ReviewedMove>): GameReviewSummary = GameReviewSummary(
            white = sideSummary(Side.WHITE, moves),
            black = sideSummary(Side.BLACK, moves),
        )

        private fun sideSummary(side: Side, moves: List<ReviewedMove>): ReviewSideSummary {
            val sideMoves = moves.filter { it.mover == side }
            val graded = sideMoves.mapNotNull { it.quality }
            val losses = sideMoves.mapNotNull { it.expectedPointLoss }
            return ReviewSideSummary(
                side = side,
                gradedMoves = graded.size,
                movesWithExpectedPointLoss = losses.size,
                meanExpectedPointLoss = losses.takeIf { it.isNotEmpty() }?.average(),
                qualityCounts = ReviewMoveQuality.entries.associateWith { quality -> graded.count { it == quality } },
            )
        }
    }
}

data class ReviewedMove(
    val ply: Int,
    val mover: Side,
    val playedMove: UciMove,
    val bestMove: UciMove,
    val quality: ReviewMoveQuality?,
    val bestEvaluation: ReviewEvaluation?,
    val playedEvaluation: ReviewEvaluation?,
    val expectedPointLoss: Double?,
    val suggestedLine: List<UciMove>,
    val fenBefore: String,
    val fenAfter: String,
    val evidence: ReviewMoveEvidence? = null,
) {
    init {
        require(ply >= 1)
        require(expectedPointLoss == null || expectedPointLoss in 0.0..1.0)
        evidence?.let {
            require(it.bestLine.move == bestMove)
            require(it.playedLine.move == playedMove)
            require(it.bestLine.evaluation == bestEvaluation)
            require(it.playedLine.evaluation == playedEvaluation)
            require(it.bestLine.moves == suggestedLine)
        }
    }
}

data class GameReviewResult(
    val gameId: String,
    val initialFen: String,
    val rules: RulesContractV1,
    val outcome: GameOutcome,
    val moves: List<ReviewedMove>,
    val engine: EngineIdentity?,
    val scope: GameReviewScope = GameReviewScope.AllMoves,
    /** Canonical game history; [moves] contains only plies selected by [scope]. */
    val gameMoves: List<UciMove> = moves.map { it.playedMove },
    val evidenceSchemaVersion: Int = REVIEW_EVIDENCE_SCHEMA_VERSION,
    val analysisVersion: Int = REVIEW_ANALYSIS_VERSION,
    val gradingPolicyVersion: Int = ReviewGradingPolicy.CURRENT.version,
    val summary: GameReviewSummary = GameReviewSummary.from(moves),
    val ruleFidelity: ReviewRuleFidelity =
        ReviewRuleFidelity.FULL_NATIVE_RULES_CONTRACT_V1_PATCH_V2,
) {
    init {
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(evidenceSchemaVersion > 0 && analysisVersion > 0 && gradingPolicyVersion > 0)
        require(moves.map { it.ply } == moves.map { it.ply }.sorted())
        require(moves.map { it.ply }.distinct().size == moves.size)
        val reviewedByPly = moves.associateBy { it.ply }
        var position = ChessPosition.fromFen(initialFen)
        var session = GameSession.newGame(gameId, rules, RepetitionKey.of(position), position.sideToMove)
        val expectedPlies = mutableListOf<Int>()
        gameMoves.forEachIndexed { index, playedMove ->
            val ply = index + 1
            require(session.outcome == null) { "Canonical review history continues after ply ${ply - 1}" }
            val mover = position.sideToMove
            val beforeFen = position.fen()
            val reviewed = reviewedByPly[ply]
            if (scope.includes(mover)) expectedPlies += ply
            reviewed?.let {
                require(it.mover == mover && it.playedMove == playedMove)
                require(it.fenBefore == beforeFen)
            }
            val transition = ChessAdapter.transition(position, playedMove)
            position = ChessRules.apply(position, playedMove)
            session = session.apply(transition)
            reviewed?.let { require(it.fenAfter == position.fen()) }
        }
        if (session.outcome != null) {
            require(session.outcome == outcome) { "Review outcome does not match canonical app replay" }
        } else {
            require(outcome.reason in setOf(EndReason.RESIGNATION, EndReason.TIMEOUT)) {
                "Nonterminal canonical review history requires an external outcome"
            }
        }
        require(moves.map { it.ply } == expectedPlies) {
            "Review evidence coverage does not match its declared scope"
        }
        require(moves.all { it.evidence != null })
        require(moves.all { move ->
            move.evidence?.let {
                it.evidenceSchemaVersion == evidenceSchemaVersion &&
                    it.analysisVersion == analysisVersion &&
                    it.gradingPolicyVersion == gradingPolicyVersion &&
                    it.ruleFidelity == ruleFidelity
            } == true
        })
        require(summary == GameReviewSummary.from(moves))
        require((moves.isEmpty() && engine == null) || (moves.isNotEmpty() && engine != null))
        require(engine == null || engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
            "Game Review evidence requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
        }
    }
}

/** One complete, immutable player-ply result suitable for incremental presentation or caching. */
data class GameReviewMoveResult(
    val gameId: String,
    val scope: GameReviewScope.PlayerMoves,
    val rootKey: GameReviewRootKey,
    val move: ReviewedMove,
    val engine: EngineIdentity,
) {
    init {
        require(gameId.isNotBlank() && rootKey.gameId == gameId)
        require(rootKey.ply == move.ply && scope.includes(move.mover))
        require(engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
            "Game Review evidence requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
        }
        val evidence = requireNotNull(move.evidence)
        require(evidence.evidenceSchemaVersion == rootKey.evidenceSchemaVersion)
        require(evidence.analysisVersion == rootKey.analysisVersion)
        val positionBefore = ChessPosition.fromFen(rootKey.positionFen)
        require(positionBefore.sideToMove == move.mover)
        require(positionBefore.fen() == move.fenBefore)
        require(ChessRules.apply(positionBefore, move.playedMove).fen() == move.fenAfter)
    }
}

data class GameReviewProgress(
    val completedWorkUnits: Int,
    val totalWorkUnits: Int,
    val completedMoves: Int = completedWorkUnits,
    val totalMoves: Int = totalWorkUnits,
) {
    init {
        require(totalWorkUnits >= 0 && totalMoves >= 0)
        require(completedWorkUnits in 0..totalWorkUnits)
        require(completedMoves in 0..totalMoves)
    }

    /** Compatibility aliases for the current app route; new integrations should use work units. */
    val completedPositions: Int get() = completedWorkUnits
    val totalPositions: Int get() = totalWorkUnits

    val fraction: Float
        get() = if (totalWorkUnits == 0) 1f else completedWorkUnits.toFloat() / totalWorkUnits
}

object GameReviewClassifier {
    fun classify(best: ReviewEvaluation, played: ReviewEvaluation, mover: Side): Pair<ReviewMoveQuality, Double> {
        val loss = (expectedPoints(best, mover) - expectedPoints(played, mover)).coerceIn(0.0, 1.0)
        return ReviewGradingPolicy.CURRENT.classify(loss) to loss
    }

    fun classify(best: ReviewLine, played: ReviewLine): Pair<ReviewMoveQuality, Double>? {
        val bestPoints = best.expectedPoints ?: return null
        val playedPoints = played.expectedPoints ?: return null
        val loss = (bestPoints - playedPoints).coerceIn(0.0, 1.0)
        return ReviewGradingPolicy.CURRENT.classify(loss) to loss
    }

    private fun expectedPoints(evaluation: ReviewEvaluation, mover: Side): Double = when (evaluation) {
        is ReviewEvaluation.Centipawns -> 1.0 / (1.0 + 10.0.pow(-evaluation.value / 400.0))
        is ReviewEvaluation.Mate -> if (evaluation.mateIn > 0) 1.0 else 0.0
        is ReviewEvaluation.Terminal -> if (evaluation.winner == mover) 1.0 else 0.0
    }
}

/** Exact foreground-evidence coverage calculated before final review submits any engine work. */
data class PlayerGameReviewSeedCoverage(
    val expectedPlies: List<Int>,
    val exactSeededPlies: List<Int>,
    val adjacentRequiredPlies: List<Int>,
    val adjacentSeededPlies: List<Int>,
    val materializablePlies: List<Int>,
    val missingExactPlies: List<Int>,
    val missingAdjacentPlies: List<Int>,
) {
    init {
        require(expectedPlies == expectedPlies.sorted() && expectedPlies.distinct().size == expectedPlies.size)
        require(exactSeededPlies.all { it in expectedPlies })
        require(adjacentRequiredPlies.all { it in exactSeededPlies })
        require(adjacentSeededPlies.all { it in adjacentRequiredPlies })
        require(materializablePlies.all { it in exactSeededPlies })
        require(missingExactPlies.all { it in expectedPlies })
        require(missingAdjacentPlies.all { it in adjacentRequiredPlies })
    }
}

enum class GameReviewSearchKind { EXACT_ROOT, ADJACENT_HELPER }

/** One real final-review engine submission. Seeded responses never emit this callback. */
data class GameReviewSearchSubmission(
    val ply: Int,
    val kind: GameReviewSearchKind,
    val rootPositionId: String,
    val playedMove: UciMove?,
    val positionId: String,
    val requestId: String,
)

/** Runs one engine request at a time through the caller-owned engine session. */
class GameReviewRunner(private val engine: ChessEngine) {
    fun review(
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        outcome: GameOutcome,
        moveTimeMillis: Long = DEFAULT_MOVE_TIME_MILLIS,
        onProgress: (GameReviewProgress) -> Unit = {},
        onResult: (Result<GameReviewResult>) -> Unit,
    ): EngineCancellation {
        val decisions = replay(gameId, initialFen, moves, rules, outcome)
        val plan = GameReviewPlanner.plan(gameId, initialFen, moves, rules, moveTimeMillis)
        check(plan.requests.size == decisions.size)
        val runId = nextReviewRunId()
        val requests = buildList {
            addAll(plan.requests)
            val finalDecision = decisions.lastOrNull()
            if (finalDecision != null && finalDecision.outcomeAfter == null) {
                add(
                    EngineRequest(
                        requestId = "$gameId-review-final",
                        gameId = gameId,
                        positionId = "$gameId:review:${moves.size}:${RepetitionKey.of(finalDecision.positionAfter).value}",
                        initialFen = initialFen,
                        moves = moves,
                        rules = rules,
                        strength = EngineStrength.SkillLevel(20),
                        limits = EngineLimits(moveTimeMillis, plan.requests.first().limits.multiPv),
                        purpose = EnginePurpose.REVIEW,
                    ),
                )
            }
        }.map { request -> request.copy(requestId = "${request.requestId}-run-$runId") }
        return Operation(
            engine = engine,
            requests = requests,
            decisions = decisions,
            gameId = gameId,
            initialFen = initialFen,
            rules = rules,
            outcome = outcome,
            onProgress = onProgress,
            onResult = onResult,
        ).also(Operation::start)
    }

    /**
     * Materializes only player decisions whose exact foreground evidence is complete.
     *
     * This is the gameplay-time companion to [reviewPlayerMoves]. Callers may pass an ongoing
     * nonterminal prefix by leaving [outcome] null, then retain the returned immutable values and
     * pass them back through [alreadyPrepared] on the next prefix. Previously prepared moves are
     * identity-checked against the new canonical plan but are never reclassified or PV-validated.
     * A root which still needs an adjacent played-position response is simply left pending.
     */
    fun materializeReadyPlayerMoves(
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        playerSide: Side,
        seededRoots: Collection<SeededGameReviewRoot>,
        seededAdjacentRoots: Collection<SeededGameReviewAdjacentRoot> = emptyList(),
        alreadyPrepared: Collection<GameReviewMoveResult> = emptyList(),
        outcome: GameOutcome? = null,
        preparedPlan: PlayerGameReviewPlan? = null,
        moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
    ): List<GameReviewMoveResult> {
        val decisions = replay(gameId, initialFen, moves, rules, outcome)
        val plan = preparedPlan ?: GameReviewPlanner.playerPlan(
            gameId = gameId,
            initialFen = initialFen,
            moves = moves,
            rules = rules,
            playerSide = playerSide,
            moveTimeMillis = moveTimeMillis,
        )
        require(plan.gameId == gameId && plan.playerSide == playerSide && plan.gameMoves == moves) {
            "Prepared player review plan does not match the requested game"
        }
        require(plan.roots.all { root ->
            root.request.initialFen == initialFen &&
                root.request.rules == rules &&
                root.request.limits.moveTimeMillis == moveTimeMillis
        }) { "Prepared player review plan does not match the requested analysis profile" }
        require(plan.roots.all { root -> decisions[root.ply - 1].mover == playerSide })

        val plannedByPly = plan.roots.associateBy { it.ply }
        val seedsByPly = validateSeededRoots(seededRoots, plannedByPly)
        val adjacentSeedsByPly = validateSeededAdjacentRoots(
            seededAdjacentRoots = seededAdjacentRoots,
            plannedByPly = plannedByPly,
            moves = moves,
        )
        val preparedByPly = validatePreparedPlayerMoves(
            preparedMoves = alreadyPrepared,
            gameId = gameId,
            playerSide = playerSide,
            plannedByPly = plannedByPly,
            decisions = decisions,
        )
        requireSinglePlayerReviewEngine(seededRoots, seededAdjacentRoots, alreadyPrepared)

        return plan.roots.mapNotNull { root ->
            if (root.ply in preparedByPly) return@mapNotNull null
            val seed = seedsByPly[root.ply] ?: return@mapNotNull null
            materializeSeededPlayerMove(
                root = root,
                decision = decisions[root.ply - 1],
                seededRoot = seed,
                seededAdjacent = adjacentSeedsByPly[root.ply],
                playerSide = playerSide,
            )
        }
    }

    /**
     * Reviews only decisions made by [playerSide]. Opponent plies remain in
     * [GameReviewResult.gameMoves] as canonical context and are never graded. A dynamic adjacent
     * helper is submitted only when the played move is absent from the player's root MultiPV.
     */
    fun reviewPlayerMoves(
        gameId: String,
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        outcome: GameOutcome,
        playerSide: Side,
        preparedPlan: PlayerGameReviewPlan? = null,
        seededRoots: Collection<SeededGameReviewRoot> = emptyList(),
        seededAdjacentRoots: Collection<SeededGameReviewAdjacentRoot> = emptyList(),
        preparedMoves: Collection<GameReviewMoveResult> = emptyList(),
        materializeSeededMovesUpFront: Boolean = false,
        moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
        onSeedCoverage: (PlayerGameReviewSeedCoverage) -> Unit = {},
        onSearchSubmitted: (GameReviewSearchSubmission) -> Unit = {},
        onMoveReviewed: (GameReviewMoveResult) -> Unit = {},
        onProgress: (GameReviewProgress) -> Unit = {},
        onResult: (Result<GameReviewResult>) -> Unit,
    ): EngineCancellation {
        val decisions = replay(gameId, initialFen, moves, rules, outcome)
        val plan = preparedPlan ?: GameReviewPlanner.playerPlan(
                gameId = gameId,
                initialFen = initialFen,
                moves = moves,
                rules = rules,
                playerSide = playerSide,
                moveTimeMillis = moveTimeMillis,
            )
        require(plan.gameId == gameId && plan.playerSide == playerSide && plan.gameMoves == moves) {
            "Prepared player review plan does not match the requested game"
        }
        require(plan.roots.all { root ->
            root.request.initialFen == initialFen &&
                root.request.rules == rules &&
                root.request.limits.moveTimeMillis == moveTimeMillis
        }) { "Prepared player review plan does not match the requested analysis profile" }
        require(plan.roots.all { root -> decisions[root.ply - 1].mover == playerSide })
        val plannedByPly = plan.roots.associateBy { it.ply }
        val seedsByPly = validateSeededRoots(seededRoots, plannedByPly)
        val adjacentSeedsByPly = validateSeededAdjacentRoots(
            seededAdjacentRoots = seededAdjacentRoots,
            plannedByPly = plannedByPly,
            moves = moves,
        )
        val preparedByPly = validatePreparedPlayerMoves(
            preparedMoves = preparedMoves,
            gameId = gameId,
            playerSide = playerSide,
            plannedByPly = plannedByPly,
            decisions = decisions,
        )
        requireSinglePlayerReviewEngine(seededRoots, seededAdjacentRoots, preparedMoves)

        val adjacentRequiredPlies = plan.roots.mapNotNull { root ->
            preparedByPly[root.ply]?.let { prepared ->
                return@mapNotNull root.ply.takeIf {
                    requireNotNull(prepared.move.evidence).usedAdjacentFallback
                }
            }
            val seed = seedsByPly[root.ply] ?: return@mapNotNull null
            val decision = decisions[root.ply - 1]
            root.ply.takeIf { requiresAdjacentCandidate(decision, seed.response) }
        }
        val exactSeededPlies = plan.roots.mapNotNull { root ->
            root.ply.takeIf {
                root.ply in preparedByPly || seedsByPly[root.ply]?.key == root.key
            }
        }
        val adjacentSeededPlies = adjacentRequiredPlies.filter { ply ->
            if (ply in preparedByPly) return@filter true
            val root = plannedByPly.getValue(ply)
            adjacentSeedsByPly[ply]?.key?.let { key ->
                key.rootKey == root.key && key.playedMove == moves[ply - 1]
            } == true
        }
        val materializablePlies = exactSeededPlies.filter { ply ->
            ply !in adjacentRequiredPlies || ply in adjacentSeededPlies
        }
        val coverage = PlayerGameReviewSeedCoverage(
            expectedPlies = plan.roots.map { it.ply },
            exactSeededPlies = exactSeededPlies,
            adjacentRequiredPlies = adjacentRequiredPlies,
            adjacentSeededPlies = adjacentSeededPlies,
            materializablePlies = materializablePlies,
            missingExactPlies = plan.roots.map { it.ply }.filterNot { it in exactSeededPlies },
            missingAdjacentPlies = adjacentRequiredPlies.filterNot { it in adjacentSeededPlies },
        )
        runCatching { onSeedCoverage(coverage) }

        val runId = nextReviewRunId()
        val roots = plan.roots.map { root ->
            root.copy(request = root.request.copy(requestId = "${root.request.requestId}-run-$runId"))
        }
        return PlayerOperation(
            engine = engine,
            roots = roots,
            decisions = decisions,
            gameMoves = plan.gameMoves,
            seedsByPly = seedsByPly,
            adjacentSeedsByPly = adjacentSeedsByPly,
            preparedMovesByPly = preparedByPly,
            materializeSeededMovesUpFront = materializeSeededMovesUpFront,
            gameId = gameId,
            initialFen = initialFen,
            rules = rules,
            outcome = outcome,
            playerSide = playerSide,
            onMoveReviewed = onMoveReviewed,
            onSearchSubmitted = onSearchSubmitted,
            onProgress = onProgress,
            onResult = onResult,
        ).also(PlayerOperation::start)
    }

    private class PlayerOperation(
        private val engine: ChessEngine,
        private val roots: List<GameReviewRoot>,
        private val decisions: List<Decision>,
        private val gameMoves: List<UciMove>,
        private val seedsByPly: Map<Int, SeededGameReviewRoot>,
        private val adjacentSeedsByPly: Map<Int, SeededGameReviewAdjacentRoot>,
        private val preparedMovesByPly: Map<Int, GameReviewMoveResult>,
        private val materializeSeededMovesUpFront: Boolean,
        private val gameId: String,
        private val initialFen: String,
        private val rules: RulesContractV1,
        private val outcome: GameOutcome,
        private val playerSide: Side,
        private val onMoveReviewed: (GameReviewMoveResult) -> Unit,
        private val onSearchSubmitted: (GameReviewSearchSubmission) -> Unit,
        private val onProgress: (GameReviewProgress) -> Unit,
        private val onResult: (Result<GameReviewResult>) -> Unit,
    ) : EngineCancellation {
        private enum class WorkKind { ROOT, ADJACENT_HELPER }

        private class Submission(
            val rootIndex: Int,
            val kind: WorkKind,
            val request: EngineRequest,
            val adjacentKey: GameReviewAdjacentKey? = null,
        ) {
            var cancellation: EngineCancellation? = null
            var completed = false
        }

        private val lock = ConcurrentLock()
        private val reviewed = MutableList<ReviewedMove?>(roots.size) { index ->
            preparedMovesByPly[roots[index].ply]?.move
        }
        private val identities = linkedSetOf<EngineIdentity>().apply {
            preparedMovesByPly.values.forEach { prepared -> add(prepared.engine) }
        }
        private var active: Submission? = null
        private var queuedSubmission: Submission? = null
        private var dispatching = false
        private var cursor = reviewed.indexOfFirst { move -> move == null }.let { index ->
            if (index < 0) roots.size else index
        }
        private var pendingRootResponse: EngineResponse? = null
        private var completedWorkUnits = preparedMovesByPly.values.sumOf { prepared ->
            if (requireNotNull(prepared.move.evidence).usedAdjacentFallback) 2 else 1
        }
        private var totalWorkUnits = roots.size + preparedMovesByPly.values.count { prepared ->
            requireNotNull(prepared.move.evidence).usedAdjacentFallback
        }
        private var cancelled = false
        private var finished = false

        fun start() {
            val preparedStreams = roots.mapNotNull { root -> preparedMovesByPly[root.ply] }
            val seededStreams = if (materializeSeededMovesUpFront) {
                try {
                    materializeCompleteSeededMoves()
                } catch (error: Throwable) {
                    finish(Result.failure(error))
                    return
                }
            } else {
                emptyList()
            }
            runCatching { onProgress(progress()) }
            (preparedStreams + seededStreams).forEach { value ->
                runCatching { onMoveReviewed(value) }
            }
            if (cursor !in roots.indices) {
                finish(Result.success(buildResult()))
            } else {
                queueRoot()
            }
        }

        /**
         * Converts every already-complete foreground seed before publishing initial progress.
         * Missing work may occur anywhere in the game, so this is intentionally not limited to a
         * chronological prefix. The final result remains ordered by root index.
         */
        private fun materializeCompleteSeededMoves(): List<GameReviewMoveResult> {
            val streams = mutableListOf<GameReviewMoveResult>()
            roots.forEachIndexed { index, root ->
                if (reviewed[index] != null) return@forEachIndexed
                val seededRoot = seedsByPly[root.ply] ?: return@forEachIndexed
                val decision = decisions[root.ply - 1]
                val completed = materializeSeededPlayerMove(
                    root = root,
                    decision = decision,
                    seededRoot = seededRoot,
                    seededAdjacent = adjacentSeedsByPly[root.ply],
                    playerSide = playerSide,
                ) ?: return@forEachIndexed

                identities += completed.engine
                require(identities.size <= 1) {
                    "Player review responses came from different engine builds"
                }
                reviewed[index] = completed.move
                val usedAdjacent = requireNotNull(completed.move.evidence).usedAdjacentFallback
                completedWorkUnits += if (usedAdjacent) 2 else 1
                if (usedAdjacent) totalWorkUnits++
                streams += completed
            }
            cursor = reviewed.indexOfFirst { move -> move == null }.let { index ->
                if (index < 0) roots.size else index
            }
            return streams
        }

        override fun cancel() {
            val cancellation = lock.withLock {
                if (cancelled || finished) return
                cancelled = true
                finished = true
                active?.cancellation.also { active = null }
            }
            cancellation?.cancel()
        }

        private fun queueRoot() {
            val index = lock.withLock {
                if (cancelled || finished || active != null || cursor !in roots.indices) return
                cursor
            }
            val root = roots[index]
            enqueue(Submission(index, WorkKind.ROOT, root.request))
        }

        private fun queueHelper(rootIndex: Int, adjacent: GameReviewAdjacentRoot) {
            enqueue(
                Submission(
                    rootIndex = rootIndex,
                    kind = WorkKind.ADJACENT_HELPER,
                    request = adjacent.request,
                    adjacentKey = adjacent.key,
                ),
            )
        }

        private fun enqueue(submission: Submission) {
            val shouldDrain = lock.withLock {
                if (cancelled || finished || active != null || submission.rootIndex != cursor) return
                check(queuedSubmission == null) { "Player review attempted to queue concurrent work" }
                queuedSubmission = submission
                if (dispatching) false else {
                    dispatching = true
                    true
                }
            }
            if (shouldDrain) drainSubmissions()
        }

        /** Trampolines seeded and synchronously-completing engines without recursive submission. */
        private fun drainSubmissions() {
            while (true) {
                val submission = lock.withLock {
                    if (cancelled || finished) {
                        queuedSubmission = null
                        dispatching = false
                        return
                    }
                    queuedSubmission?.also { queuedSubmission = null } ?: run {
                        dispatching = false
                        return
                    }
                }
                submitNow(submission)
            }
        }

        private fun submitNow(submission: Submission) {
            lock.withLock {
                if (cancelled || finished || active != null || submission.rootIndex != cursor) return
                active = submission
            }
            val ply = roots[submission.rootIndex].ply
            val seededResponse = when (submission.kind) {
                WorkKind.ROOT -> seedsByPly[ply]?.response
                WorkKind.ADJACENT_HELPER -> adjacentSeedsByPly[ply]
                    ?.takeIf { it.key == submission.adjacentKey }
                    ?.response
            }
            if (seededResponse != null) {
                // Exact evidence keys were validated before the attempt started. Only now may the
                // old request identity be normalized to this fresh run identity.
                complete(
                    submission,
                    Result.success(seededResponse.copy(requestId = submission.request.requestId)),
                )
                return
            }
            try {
                onSearchSubmitted(
                    GameReviewSearchSubmission(
                        ply = ply,
                        kind = when (submission.kind) {
                            WorkKind.ROOT -> GameReviewSearchKind.EXACT_ROOT
                            WorkKind.ADJACENT_HELPER -> GameReviewSearchKind.ADJACENT_HELPER
                        },
                        rootPositionId = roots[submission.rootIndex].key.positionId,
                        playedMove = decisions[roots[submission.rootIndex].ply - 1].playedMove
                            .takeIf { submission.kind == WorkKind.ADJACENT_HELPER },
                        positionId = submission.request.positionId,
                        requestId = submission.request.requestId,
                    ),
                )
            } catch (error: Throwable) {
                complete(submission, Result.failure(error))
                return
            }
            val cancellation = try {
                engine.analyze(submission.request) { result -> complete(submission, result) }
            } catch (error: Throwable) {
                complete(submission, Result.failure(error))
                return
            }
            var cancelImmediately = false
            lock.withLock {
                if (active === submission && !submission.completed && !cancelled && !finished) {
                    submission.cancellation = cancellation
                } else if (cancelled && !submission.completed) {
                    cancelImmediately = true
                }
            }
            if (cancelImmediately) cancellation.cancel()
        }

        private fun complete(submission: Submission, result: Result<EngineResponse>) {
            var stream: GameReviewMoveResult? = null
            var progress: GameReviewProgress? = null
            var completion: Result<GameReviewResult>? = null
            var helper: Pair<Int, GameReviewAdjacentRoot>? = null
            var submitNextRoot = false
            lock.withLock {
                if (submission.completed || cancelled || finished || active !== submission) return
                submission.completed = true
                active = null
                val response = result.getOrElse { error ->
                    finished = true
                    completion = Result.failure(error)
                    return@withLock
                }
                if (!response.matches(submission.request)) {
                    finished = true
                    completion = Result.failure(
                        IllegalStateException(
                            "Player review response identity does not match request ${submission.request.requestId}",
                        ),
                    )
                    return@withLock
                }
                try {
                    require(response.engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
                        "Player review requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
                    }
                    identities += response.engine
                    require(identities.size <= 1) { "Player review responses came from different engine builds" }
                    completedWorkUnits++
                    val root = roots[submission.rootIndex]
                    val decision = decisions[root.ply - 1]
                    when (submission.kind) {
                        WorkKind.ROOT -> {
                            if (requiresAdjacentCandidate(decision, response)) {
                                pendingRootResponse = response
                                totalWorkUnits++
                                helper = submission.rootIndex to GameReviewPlanner.adjacentRoot(
                                    requestId = "${root.request.requestId}-adjacent-${decision.ply}",
                                    root = root,
                                    playedMove = decision.playedMove,
                                )
                            } else {
                                val completedMove = reviewedMove(decision, response, adjacentResponse = null)
                                reviewed[submission.rootIndex] = completedMove
                                advanceCursor(submission.rootIndex + 1)
                                stream = streamed(root, completedMove)
                                submitNextRoot = cursor in roots.indices
                            }
                        }
                        WorkKind.ADJACENT_HELPER -> {
                            val rootResponse = requireNotNull(pendingRootResponse) {
                                "Adjacent player evidence has no matching root response"
                            }
                            val completedMove = reviewedMove(decision, rootResponse, response)
                            pendingRootResponse = null
                            reviewed[submission.rootIndex] = completedMove
                            advanceCursor(submission.rootIndex + 1)
                            stream = streamed(root, completedMove)
                            submitNextRoot = cursor in roots.indices
                        }
                    }
                    progress = progress()
                    if (cursor == roots.size && helper == null) {
                        finished = true
                        completion = Result.success(buildResult())
                        submitNextRoot = false
                    }
                } catch (error: Throwable) {
                    finished = true
                    completion = Result.failure(error)
                    helper = null
                    submitNextRoot = false
                }
            }
            stream?.let { value -> runCatching { onMoveReviewed(value) } }
            progress?.let { value -> runCatching { onProgress(value) } }
            completion?.let { value -> runCatching { onResult(value) } }
            helper?.let { (index, adjacent) -> queueHelper(index, adjacent) }
            if (submitNextRoot) queueRoot()
        }

        private fun streamed(root: GameReviewRoot, move: ReviewedMove): GameReviewMoveResult =
            GameReviewMoveResult(
                gameId = gameId,
                scope = GameReviewScope.PlayerMoves(playerSide),
                rootKey = root.key,
                move = move,
                engine = identities.single(),
            )

        private fun progress(): GameReviewProgress = GameReviewProgress(
            completedWorkUnits = completedWorkUnits,
            totalWorkUnits = totalWorkUnits,
            completedMoves = reviewed.count { move -> move != null },
            totalMoves = roots.size,
        )

        private fun advanceCursor(startIndex: Int) {
            cursor = (startIndex until roots.size).firstOrNull { index -> reviewed[index] == null }
                ?: roots.size
        }

        private fun buildResult(): GameReviewResult = GameReviewResult(
            gameId = gameId,
            initialFen = initialFen,
            rules = rules,
            outcome = outcome,
            moves = reviewed.mapIndexed { index, move ->
                requireNotNull(move) { "Player review root ${roots[index].ply} was not completed" }
            },
            engine = identities.singleOrNull(),
            scope = GameReviewScope.PlayerMoves(playerSide),
            gameMoves = gameMoves,
        )

        private fun finish(result: Result<GameReviewResult>) {
            val deliver = lock.withLock {
                if (cancelled || finished) false else {
                    finished = true
                    true
                }
            }
            if (deliver) runCatching { onResult(result) }
        }
    }

    private class Operation(
        private val engine: ChessEngine,
        private val requests: List<com.drawlesschess.core.EngineRequest>,
        private val decisions: List<Decision>,
        private val gameId: String,
        private val initialFen: String,
        private val rules: RulesContractV1,
        private val outcome: GameOutcome,
        private val onProgress: (GameReviewProgress) -> Unit,
        private val onResult: (Result<GameReviewResult>) -> Unit,
    ) : EngineCancellation {
        private class Submission(val index: Int) {
            var cancellation: EngineCancellation? = null
            var completed = false
        }

        private val lock = ConcurrentLock()
        private val responses = mutableListOf<EngineResponse>()
        private var active: Submission? = null
        private var cancelled = false
        private var finished = false

        fun start() {
            runCatching { onProgress(GameReviewProgress(0, requests.size)) }
            if (requests.isEmpty()) {
                finish(Result.success(buildResult()))
            } else {
                submit(0)
            }
        }

        override fun cancel() {
            val cancellation = lock.withLock {
                if (cancelled || finished) return
                cancelled = true
                finished = true
                active?.cancellation.also { active = null }
            }
            cancellation?.cancel()
        }

        private fun submit(index: Int) {
            val submission = lock.withLock {
                if (cancelled || finished) return
                Submission(index).also { active = it }
            }
            val cancellation = try {
                engine.analyze(requests[index]) { result -> complete(submission, result) }
            } catch (error: Throwable) {
                complete(submission, Result.failure(error))
                return
            }
            var cancelImmediately = false
            lock.withLock {
                if (active === submission && !submission.completed && !cancelled && !finished) {
                    submission.cancellation = cancellation
                } else if (cancelled && !submission.completed) {
                    cancelImmediately = true
                }
            }
            if (cancelImmediately) cancellation.cancel()
        }

        private fun complete(submission: Submission, result: Result<EngineResponse>) {
            var nextIndex: Int? = null
            var completion: Result<GameReviewResult>? = null
            var progress: GameReviewProgress? = null
            lock.withLock {
                if (submission.completed || cancelled || finished || active !== submission) return
                submission.completed = true
                active = null
                val response = result.getOrElse { error ->
                    finished = true
                    completion = Result.failure(error)
                    return@withLock
                }
                val request = requests[submission.index]
                if (!response.matches(request)) {
                    finished = true
                    completion = Result.failure(
                        IllegalStateException("Review response identity does not match request ${request.requestId}"),
                    )
                    return@withLock
                }
                if (response.engine.drawlessPatch != REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
                    finished = true
                    completion = Result.failure(
                        IllegalStateException(
                            "Game Review requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION",
                        ),
                    )
                    return@withLock
                }
                responses += response
                progress = GameReviewProgress(responses.size, requests.size)
                if (responses.size == requests.size) {
                    finished = true
                    completion = runCatching { buildResult() }
                } else {
                    nextIndex = responses.size
                }
            }
            progress?.let { value -> runCatching { onProgress(value) } }
            completion?.let { value -> runCatching { onResult(value) } }
            nextIndex?.let(::submit)
        }

        private fun finish(result: Result<GameReviewResult>) {
            val deliver = lock.withLock {
                if (cancelled || finished) false else {
                    finished = true
                    true
                }
            }
            if (deliver) runCatching { onResult(result) }
        }

        private fun buildResult(): GameReviewResult {
            responses.forEachIndexed { index, response ->
                val decision = decisions.getOrNull(index)
                if (decision != null) {
                    response.reviewLines(
                        position = decision.positionBefore,
                        session = decision.sessionBefore,
                        firstGamePly = decision.ply,
                    )
                } else {
                    val finalDecision = requireNotNull(decisions.lastOrNull()) {
                        "A review response has no corresponding analysis root"
                    }
                    require(index == decisions.size) { "Unexpected extra review response at index $index" }
                    response.reviewLines(
                        position = finalDecision.positionAfter,
                        session = finalDecision.sessionAfter,
                        firstGamePly = finalDecision.ply + 1,
                    )
                }
            }
            val reviewed = decisions.mapIndexed { index, decision ->
                reviewedMove(index, decision, responses)
            }
            val identities = responses.map { it.engine }.distinct()
            require(identities.size <= 1) { "Review responses came from different engine builds" }
            return GameReviewResult(
                gameId = gameId,
                initialFen = initialFen,
                rules = rules,
                outcome = outcome,
                moves = reviewed,
                engine = identities.singleOrNull(),
            )
        }
    }

    private companion object {
        const val DEFAULT_MOVE_TIME_MILLIS = 350L
        val REVIEW_RUN_SEQUENCE_LOCK = ConcurrentLock()
        var reviewRunSequence = 0L

        fun nextReviewRunId(): Long = REVIEW_RUN_SEQUENCE_LOCK.withLock {
            if (reviewRunSequence == Long.MAX_VALUE) {
                throw ArithmeticException("Review run sequence overflow")
            }
            ++reviewRunSequence
        }

        fun validateSeededRoots(
            seededRoots: Collection<SeededGameReviewRoot>,
            plannedByPly: Map<Int, GameReviewRoot>,
        ): Map<Int, SeededGameReviewRoot> {
            val seedsByPly = seededRoots.associateBy { it.key.ply }
            require(seedsByPly.size == seededRoots.size) {
                "Player review contains duplicate seeded roots"
            }
            seedsByPly.forEach { (ply, seed) ->
                val planned = requireNotNull(plannedByPly[ply]) {
                    "Seeded review root ply $ply is outside player coverage"
                }
                require(seed.key == planned.key) {
                    "Seeded review root ply $ply does not match the exact game, rules, or analysis profile"
                }
            }
            return seedsByPly
        }

        fun validateSeededAdjacentRoots(
            seededAdjacentRoots: Collection<SeededGameReviewAdjacentRoot>,
            plannedByPly: Map<Int, GameReviewRoot>,
            moves: List<UciMove>,
        ): Map<Int, SeededGameReviewAdjacentRoot> {
            val adjacentSeedsByPly = seededAdjacentRoots.associateBy { it.key.rootKey.ply }
            require(adjacentSeedsByPly.size == seededAdjacentRoots.size) {
                "Player review contains duplicate seeded adjacent roots"
            }
            adjacentSeedsByPly.forEach { (ply, seed) ->
                val planned = requireNotNull(plannedByPly[ply]) {
                    "Seeded adjacent root ply $ply is outside player coverage"
                }
                require(seed.key.rootKey == planned.key && seed.key.playedMove == moves[ply - 1]) {
                    "Seeded adjacent root ply $ply does not match the exact game continuation or analysis profile"
                }
            }
            return adjacentSeedsByPly
        }

        fun validatePreparedPlayerMoves(
            preparedMoves: Collection<GameReviewMoveResult>,
            gameId: String,
            playerSide: Side,
            plannedByPly: Map<Int, GameReviewRoot>,
            decisions: List<Decision>,
        ): Map<Int, GameReviewMoveResult> {
            val preparedByPly = preparedMoves.associateBy { it.move.ply }
            require(preparedByPly.size == preparedMoves.size) {
                "Player review contains duplicate prepared moves"
            }
            preparedByPly.forEach { (ply, prepared) ->
                val planned = requireNotNull(plannedByPly[ply]) {
                    "Prepared review move ply $ply is outside player coverage"
                }
                val decision = decisions[ply - 1]
                require(
                    prepared.gameId == gameId &&
                        prepared.scope == GameReviewScope.PlayerMoves(playerSide) &&
                        prepared.rootKey == planned.key,
                ) {
                    "Prepared review move ply $ply does not match the exact game, side, rules, or analysis profile"
                }
                require(
                    prepared.move.ply == ply &&
                        prepared.move.mover == decision.mover &&
                        prepared.move.playedMove == decision.playedMove &&
                        prepared.move.fenBefore == decision.positionBefore.fen() &&
                        prepared.move.fenAfter == decision.positionAfter.fen(),
                ) {
                    "Prepared review move ply $ply does not match the canonical played decision"
                }
            }
            return preparedByPly
        }

        fun requireSinglePlayerReviewEngine(
            seededRoots: Collection<SeededGameReviewRoot>,
            seededAdjacentRoots: Collection<SeededGameReviewAdjacentRoot>,
            preparedMoves: Collection<GameReviewMoveResult>,
        ) {
            require(
                (seededRoots.map { it.response.engine } +
                    seededAdjacentRoots.map { it.response.engine } +
                    preparedMoves.map { it.engine }).distinct().size <= 1,
            ) {
                "Player review evidence came from different engine builds"
            }
        }

        fun materializeSeededPlayerMove(
            root: GameReviewRoot,
            decision: Decision,
            seededRoot: SeededGameReviewRoot,
            seededAdjacent: SeededGameReviewAdjacentRoot?,
            playerSide: Side,
        ): GameReviewMoveResult? {
            val rootResponse = seededRoot.response.copy(requestId = root.request.requestId)
            require(rootResponse.matches(root.request)) {
                "Seeded player review response identity does not match root ${root.request.requestId}"
            }
            require(rootResponse.engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
                "Player review requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
            }

            val needsAdjacent = requiresAdjacentCandidate(decision, rootResponse)
            val adjacentResponse = if (needsAdjacent) {
                val adjacentSeed = seededAdjacent ?: return null
                val adjacent = GameReviewPlanner.adjacentRoot(
                    requestId = "${root.request.requestId}-adjacent-${decision.ply}",
                    root = root,
                    playedMove = decision.playedMove,
                )
                adjacentSeed.response.copy(requestId = adjacent.request.requestId).also { response ->
                    require(response.matches(adjacent.request)) {
                        "Seeded adjacent review response identity does not match root ${root.request.requestId}"
                    }
                    require(response.engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION) {
                        "Player review requires Drawless patch $REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION"
                    }
                    require(response.engine == rootResponse.engine) {
                        "Player review responses came from different engine builds"
                    }
                }
            } else {
                null
            }
            val move = reviewedMove(decision, rootResponse, adjacentResponse)
            return GameReviewMoveResult(
                gameId = root.key.gameId,
                scope = GameReviewScope.PlayerMoves(playerSide),
                rootKey = root.key,
                move = move,
                engine = rootResponse.engine,
            )
        }

        fun replay(
            gameId: String,
            initialFen: String,
            moves: List<UciMove>,
            rules: RulesContractV1,
            expectedOutcome: GameOutcome?,
        ): List<Decision> {
            var position = ChessPosition.fromFen(initialFen)
            var session = GameSession.newGame(gameId, rules, RepetitionKey.of(position), position.sideToMove)
            val decisions = moves.mapIndexed { index, move ->
                require(session.outcome == null) { "Move history continues after a terminal result at ply $index" }
                val before = position
                val beforeSession = session
                val transition = ChessAdapter.transition(before, move)
                position = ChessRules.apply(before, move)
                session = beforeSession.apply(transition)
                Decision(
                    ply = index + 1,
                    positionBefore = before,
                    positionAfter = position,
                    sessionBefore = beforeSession,
                    sessionAfter = session,
                    playedMove = move,
                    outcomeAfter = session.outcome,
                )
            }
            if (expectedOutcome == null) {
                require(session.outcome == null) {
                    "Ongoing review prefix already contains a terminal result"
                }
            } else if (session.outcome != null) {
                require(session.outcome == expectedOutcome) { "Review outcome does not match replayed result" }
            } else {
                require(expectedOutcome.reason in setOf(
                    com.drawlesschess.core.EndReason.RESIGNATION,
                    com.drawlesschess.core.EndReason.TIMEOUT,
                )) { "Completed review has no replayable terminal result" }
            }
            return decisions
        }

        fun reviewedMove(
            index: Int,
            decision: Decision,
            responses: List<EngineResponse>,
        ): ReviewedMove = reviewedMove(
            decision = decision,
            response = responses[index],
            adjacentResponse = responses.getOrNull(index + 1),
        )

        fun reviewedMove(
            decision: Decision,
            response: EngineResponse,
            adjacentResponse: EngineResponse?,
        ): ReviewedMove {
            val lines = response.reviewLines(
                position = decision.positionBefore,
                session = decision.sessionBefore,
                firstGamePly = decision.ply,
            )
            val primary = lines.first()
            val engineBest = response.bestMove
            val terminal = decision.outcomeAfter
            val legalMoves = ChessRules.legalUciMoves(decision.positionBefore)
            val legalMoveCount = legalMoves.size
            val playedRootLine = lines.firstOrNull { it.move == decision.playedMove }
            val bestLine: ReviewLine
            val playedLine: ReviewLine
            val quality: ReviewMoveQuality?
            val expectedLoss: Double?

            if (terminal != null) {
                val playedTerminalLine = terminalLine(
                    move = decision.playedMove,
                    outcome = terminal,
                    mover = decision.mover,
                    origin = ReviewLineOrigin.AUTHORITATIVE_TERMINAL,
                )
                when {
                    terminal.winner == decision.mover -> {
                        // The played move is the authoritative winning terminal result. None of
                        // the alternative-outcome analysis below can change its grade, and eagerly
                        // replaying every alternative is especially expensive in Kotlin/Native on
                        // legacy devices because each transition regenerates the legal move set.
                        bestLine = playedTerminalLine
                        playedLine = playedTerminalLine
                        quality = ReviewMoveQuality.BEST
                        expectedLoss = 0.0
                    }
                    else -> {
                        val alternatives = legalMoves.map { move ->
                            val resulting = decision.sessionBefore.apply(
                                ChessAdapter.transition(decision.positionBefore, move),
                            )
                            move to resulting.outcome
                        }
                        val immediateWins = alternatives.filter { (_, result) ->
                            result?.winner == decision.mover
                        }
                        val avoidsImmediateLoss = alternatives.filter { (_, result) ->
                            result == null || result.winner == decision.mover
                        }
                        if (avoidsImmediateLoss.isEmpty()) {
                            bestLine = playedTerminalLine.copy(
                                origin = ReviewLineOrigin.FORCED_LOSS_EQUIVALENCE,
                            )
                            playedLine = playedTerminalLine
                            quality = ReviewMoveQuality.BEST
                            expectedLoss = 0.0
                        } else {
                            val preferred = immediateWins.firstOrNull()?.first
                                ?: engineBest.takeIf { candidate ->
                                    avoidsImmediateLoss.any { it.first == candidate }
                                }
                                ?: avoidsImmediateLoss.first().first
                            val candidateBestLine = if (preferred == engineBest) {
                                primary
                            } else {
                                immediateWins.firstOrNull { it.first == preferred }
                                    ?.second
                                    ?.let { winningOutcome ->
                                        terminalLine(
                                            move = preferred,
                                            outcome = winningOutcome,
                                            mover = decision.mover,
                                            origin = ReviewLineOrigin.AUTHORITATIVE_TERMINAL,
                                        )
                                    } ?: safeAlternativeLine(preferred)
                            }
                            val classified = GameReviewClassifier.classify(
                                candidateBestLine,
                                playedTerminalLine,
                            )
                            val provenForcedLoss = preferred == engineBest &&
                                primary.bound == EngineScoreBound.EXACT &&
                                primary.expectedPoints != null &&
                                (primary.evaluation as? ReviewEvaluation.Mate)?.mateIn?.let { it < 0 } == true
                            if (provenForcedLoss) {
                                // A concrete forced mate is sufficient evidence that delaying the
                                // authoritative terminal loss would not change the game result.
                                bestLine = playedTerminalLine.copy(
                                    origin = ReviewLineOrigin.FORCED_LOSS_EQUIVALENCE,
                                )
                                playedLine = playedTerminalLine
                                quality = ReviewMoveQuality.BEST
                                expectedLoss = 0.0
                            } else {
                                // A large negative centipawn score is not proof that every line
                                // loses. Never call an avoidable immediate terminal loss Best on
                                // that basis.
                                quality = classified?.first
                                    ?.takeUnless { it == ReviewMoveQuality.BEST }
                                    ?: ReviewMoveQuality.BLUNDER
                                expectedLoss = classified?.second
                                bestLine = candidateBestLine
                                playedLine = playedTerminalLine
                            }
                        }
                    }
                }
            } else if (legalMoveCount == 1) {
                require(primary.move == decision.playedMove) {
                    "The only legal move must match the played move at ply ${decision.ply}"
                }
                val forcedLine = primary.copy(origin = ReviewLineOrigin.FORCED_LEGAL_MOVE)
                bestLine = forcedLine
                playedLine = forcedLine
                quality = ReviewMoveQuality.BEST
                expectedLoss = 0.0
            } else {
                bestLine = primary
                playedLine = playedRootLine ?: requireNotNull(adjacentResponse) {
                    "Review is missing adjacent evidence after ply ${decision.ply}"
                }.reviewLines(
                    position = decision.positionAfter,
                    session = decision.sessionAfter,
                    firstGamePly = decision.ply + 1,
                ).first().asAdjacentPlayedLine(decision.playedMove)
                val classified = GameReviewClassifier.classify(bestLine, playedLine)
                quality = classified?.first
                expectedLoss = classified?.second
            }

            return ReviewedMove(
                ply = decision.ply,
                mover = decision.mover,
                playedMove = decision.playedMove,
                bestMove = bestLine.move,
                quality = quality,
                bestEvaluation = bestLine.evaluation,
                playedEvaluation = playedLine.evaluation,
                expectedPointLoss = expectedLoss,
                suggestedLine = bestLine.moves,
                fenBefore = decision.positionBefore.fen(),
                fenAfter = decision.positionAfter.fen(),
                evidence = ReviewMoveEvidence(
                    lines = lines,
                    bestLine = bestLine,
                    playedLine = playedLine,
                    playedLineRank = playedLine.rank.takeIf {
                        playedLine.origin == ReviewLineOrigin.ROOT_MULTIPV
                    },
                    legalMoveCount = legalMoveCount,
                    forced = legalMoveCount == 1,
                    usedAdjacentFallback = playedLine.origin == ReviewLineOrigin.ADJACENT_POSITION,
                ),
            )
        }

        /**
         * Cheap readiness check for an identity-validated root response.
         *
         * Full PV legality and rules-contract validation happens exactly once in [reviewedMove]
         * when this decision is first materialized. Coverage polling only needs to know whether a
         * candidate starts with the played move; replaying every PV here made long seeded games
         * perform the same expensive validation repeatedly after the terminal move.
         */
        fun requiresAdjacentCandidate(decision: Decision, response: EngineResponse): Boolean {
            if (decision.outcomeAfter != null) return false
            if (ChessRules.legalUciMoves(decision.positionBefore).size == 1) return false
            return response.variations.none { variation ->
                variation.moves.firstOrNull() == decision.playedMove
            }
        }

        fun EngineResponse.reviewLines(
            position: ChessPosition,
            session: GameSession,
            firstGamePly: Int,
        ): List<ReviewLine> {
            require(firstGamePly == session.moves.size + 1) {
                "Review PV root game ply does not match the app session"
            }
            require(session.sideToMove == position.sideToMove) {
                "Review PV root side to move does not match the app session"
            }
            require(session.history.current == RepetitionKey.of(position)) {
                "Review PV root position does not match the app session"
            }
            val sorted = variations.sortedBy { it.rank }
            require(sorted.isNotEmpty() && sorted.first().rank == 1) {
                "Review response is missing its primary variation"
            }
            require(sorted.map { it.rank }.distinct().size == sorted.size) {
                "Review response contains duplicate variation ranks"
            }
            require(sorted.first().moves.first() == bestMove) {
                "Primary review variation does not match best move ${bestMove.value}"
            }
            sorted.forEach { variation ->
                var currentPosition = position
                var currentSession = session
                variation.moves.forEachIndexed { pvIndex, move ->
                    val pvPly = pvIndex + 1
                    val gamePly = firstGamePly + pvIndex
                    require(currentSession.outcome == null) {
                        "Review PV rank ${variation.rank} continues after app terminal at " +
                            "PV ply $pvPly (game ply $gamePly)"
                    }
                    try {
                        val transition = ChessAdapter.transition(currentPosition, move)
                        currentPosition = ChessRules.apply(currentPosition, move)
                        currentSession = currentSession.apply(transition)
                    } catch (error: RuntimeException) {
                        throw IllegalArgumentException(
                            "Illegal review PV rank ${variation.rank} at PV ply $pvPly " +
                                "(game ply $gamePly): ${move.value}",
                            error,
                        )
                    }
                }
            }
            return sorted.map { it.reviewLine() }
        }

        fun com.drawlesschess.core.PrincipalVariation.reviewLine(): ReviewLine {
            val evaluation = if (!evidenceAvailable) null else {
                scoreCentipawns?.let(ReviewEvaluation::Centipawns)
                    ?: ReviewEvaluation.Mate(requireNotNull(mateIn))
            }
            val exact = evidenceAvailable && bound == EngineScoreBound.EXACT
            val source = when {
                !evidenceAvailable -> null
                evaluation is ReviewEvaluation.Mate -> ReviewScoreSource.MATE
                wdl != null -> ReviewScoreSource.WDL
                evaluation is ReviewEvaluation.Centipawns -> ReviewScoreSource.CENTIPAWNS
                else -> null
            }
            val points = if (!exact || evaluation == null) null else when {
                evaluation is ReviewEvaluation.Mate -> if (evaluation.mateIn > 0) 1.0 else 0.0
                wdl != null -> wdl.expectedPoints()
                evaluation is ReviewEvaluation.Centipawns ->
                    1.0 / (1.0 + 10.0.pow(-evaluation.value / 400.0))
                else -> null
            }
            return ReviewLine(
                rank = rank,
                move = moves.first(),
                evaluation = evaluation,
                expectedPoints = points,
                source = source,
                bound = bound,
                depth = depth,
                moves = moves,
            )
        }

        fun EngineWdl.expectedPoints(): Double {
            val total = wins + draws + losses
            return (wins + draws * 0.5) / total
        }

        fun terminalLine(
            move: UciMove,
            outcome: GameOutcome,
            mover: Side,
            origin: ReviewLineOrigin,
        ): ReviewLine = ReviewLine(
            rank = 1,
            move = move,
            evaluation = ReviewEvaluation.Terminal(outcome.winner),
            expectedPoints = if (outcome.winner == mover) 1.0 else 0.0,
            source = ReviewScoreSource.TERMINAL,
            bound = EngineScoreBound.EXACT,
            depth = null,
            moves = listOf(move),
            origin = origin,
        )

        fun safeAlternativeLine(move: UciMove): ReviewLine = ReviewLine(
            rank = 1,
            move = move,
            evaluation = null,
            expectedPoints = null,
            source = null,
            bound = EngineScoreBound.EXACT,
            depth = null,
            moves = listOf(move),
            origin = ReviewLineOrigin.AUTHORITATIVE_SAFE_ALTERNATIVE,
        )

        fun ReviewLine.negated(): ReviewLine = copy(
            evaluation = evaluation?.negated(),
            expectedPoints = expectedPoints?.let { 1.0 - it },
            bound = when (bound) {
                EngineScoreBound.EXACT -> EngineScoreBound.EXACT
                EngineScoreBound.LOWER -> EngineScoreBound.UPPER
                EngineScoreBound.UPPER -> EngineScoreBound.LOWER
            },
            origin = ReviewLineOrigin.ADJACENT_POSITION,
        )

        fun ReviewLine.asAdjacentPlayedLine(playedMove: UciMove): ReviewLine = negated().copy(
            rank = 1,
            move = playedMove,
            moves = listOf(playedMove) + moves,
        )

        fun ReviewEvaluation.negated(): ReviewEvaluation = when (this) {
            is ReviewEvaluation.Centipawns -> ReviewEvaluation.Centipawns(-value)
            is ReviewEvaluation.Mate -> ReviewEvaluation.Mate(-mateIn)
            is ReviewEvaluation.Terminal -> this
        }
    }

    private data class Decision(
        val ply: Int,
        val positionBefore: ChessPosition,
        val positionAfter: ChessPosition,
        val sessionBefore: GameSession,
        val sessionAfter: GameSession,
        val playedMove: UciMove,
        val outcomeAfter: GameOutcome?,
    ) {
        val mover: Side get() = positionBefore.sideToMove
    }
}
