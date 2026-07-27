package com.drawlesschess.core.engine

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.EngineStrength
import com.drawlesschess.core.EngineWdl
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

const val REVIEW_EVIDENCE_SCHEMA_VERSION = 1
const val REVIEW_ANALYSIS_VERSION = 1

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
 * Search models the configured Drawless/Escape base variant, while the core authoritatively
 * overlays immediate app-adjudicated outcomes. Selectable dead-position, bare-king, and
 * fifty-move policies are not yet modeled throughout the native search tree.
 */
enum class ReviewRuleFidelity {
    PARTIAL_ENGINE_RULES_WITH_AUTHORITATIVE_TERMINAL_OVERLAY,
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
        ReviewRuleFidelity.PARTIAL_ENGINE_RULES_WITH_AUTHORITATIVE_TERMINAL_OVERLAY,
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
    val evidenceSchemaVersion: Int = REVIEW_EVIDENCE_SCHEMA_VERSION,
    val analysisVersion: Int = REVIEW_ANALYSIS_VERSION,
    val gradingPolicyVersion: Int = ReviewGradingPolicy.CURRENT.version,
    val summary: GameReviewSummary = GameReviewSummary.from(moves),
    val ruleFidelity: ReviewRuleFidelity =
        ReviewRuleFidelity.PARTIAL_ENGINE_RULES_WITH_AUTHORITATIVE_TERMINAL_OVERLAY,
) {
    init {
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(evidenceSchemaVersion > 0 && analysisVersion > 0 && gradingPolicyVersion > 0)
        require(moves.map { it.ply } == (1..moves.size).toList())
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
    }
}

data class GameReviewProgress(
    val completedPositions: Int,
    val totalPositions: Int,
) {
    init {
        require(totalPositions >= 0)
        require(completedPositions in 0..totalPositions)
    }

    val fraction: Float
        get() = if (totalPositions == 0) 1f else completedPositions.toFloat() / totalPositions
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
        val runId = REVIEW_RUN_SEQUENCE.incrementAndGet()
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

        private val lock = Any()
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
            val cancellation = synchronized(lock) {
                if (cancelled || finished) return
                cancelled = true
                finished = true
                active?.cancellation.also { active = null }
            }
            cancellation?.cancel()
        }

        private fun submit(index: Int) {
            val submission = synchronized(lock) {
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
            synchronized(lock) {
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
            synchronized(lock) {
                if (submission.completed || cancelled || finished || active !== submission) return
                submission.completed = true
                active = null
                val response = result.getOrElse { error ->
                    finished = true
                    completion = Result.failure(error)
                    return@synchronized
                }
                val request = requests[submission.index]
                if (!response.matches(request)) {
                    finished = true
                    completion = Result.failure(
                        IllegalStateException("Review response identity does not match request ${request.requestId}"),
                    )
                    return@synchronized
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
            val deliver = synchronized(lock) {
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
        val REVIEW_RUN_SEQUENCE = AtomicLong()

        fun replay(
            gameId: String,
            initialFen: String,
            moves: List<UciMove>,
            rules: RulesContractV1,
            expectedOutcome: GameOutcome,
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
            if (session.outcome != null) {
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
        ): ReviewedMove {
            val response = responses[index]
            val lines = response.reviewLines(
                position = decision.positionBefore,
                session = decision.sessionBefore,
                firstGamePly = decision.ply,
            )
            val primary = lines.first()
            val engineBest = response.bestMove
            val terminal = decision.outcomeAfter
            val legalMoveCount = ChessRules.legalUciMoves(decision.positionBefore).size
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
                val alternatives = ChessRules.legalUciMoves(decision.positionBefore).map { move ->
                    val resulting = decision.sessionBefore.apply(ChessAdapter.transition(decision.positionBefore, move))
                    move to resulting.outcome
                }
                val immediateWins = alternatives.filter { (_, result) -> result?.winner == decision.mover }
                val avoidsImmediateLoss = alternatives.filter { (_, result) -> result == null || result.winner == decision.mover }
                when {
                    terminal.winner == decision.mover -> {
                        bestLine = playedTerminalLine
                        playedLine = playedTerminalLine
                        quality = ReviewMoveQuality.BEST
                        expectedLoss = 0.0
                    }
                    avoidsImmediateLoss.isEmpty() -> {
                        bestLine = playedTerminalLine.copy(origin = ReviewLineOrigin.FORCED_LOSS_EQUIVALENCE)
                        playedLine = playedTerminalLine
                        quality = ReviewMoveQuality.BEST
                        expectedLoss = 0.0
                    }
                    else -> {
                        val preferred = immediateWins.firstOrNull()?.first
                            ?: engineBest.takeIf { candidate -> avoidsImmediateLoss.any { it.first == candidate } }
                            ?: avoidsImmediateLoss.first().first
                        val candidateBestLine = if (preferred == engineBest) {
                            primary
                        } else {
                            immediateWins.firstOrNull { it.first == preferred }?.second?.let { winningOutcome ->
                                terminalLine(
                                    move = preferred,
                                    outcome = winningOutcome,
                                    mover = decision.mover,
                                    origin = ReviewLineOrigin.AUTHORITATIVE_TERMINAL,
                                )
                            } ?: safeAlternativeLine(preferred)
                        }
                        val classified = GameReviewClassifier.classify(candidateBestLine, playedTerminalLine)
                        val provenForcedLoss = preferred == engineBest &&
                            primary.bound == EngineScoreBound.EXACT &&
                            primary.expectedPoints != null &&
                            (primary.evaluation as? ReviewEvaluation.Mate)?.mateIn?.let { it < 0 } == true
                        if (provenForcedLoss) {
                            // A concrete forced mate is sufficient evidence that delaying the
                            // authoritative terminal loss would not change the game result.
                            bestLine = playedTerminalLine.copy(origin = ReviewLineOrigin.FORCED_LOSS_EQUIVALENCE)
                            playedLine = playedTerminalLine
                            quality = ReviewMoveQuality.BEST
                            expectedLoss = 0.0
                        } else {
                            // A large negative centipawn score is not proof that every line loses.
                            // Never call an avoidable immediate terminal loss Best on that basis.
                            quality = classified?.first
                                ?.takeUnless { it == ReviewMoveQuality.BEST }
                                ?: ReviewMoveQuality.BLUNDER
                            expectedLoss = classified?.second
                            bestLine = candidateBestLine
                            playedLine = playedTerminalLine
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
                playedLine = playedRootLine ?: requireNotNull(responses.getOrNull(index + 1)) {
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
