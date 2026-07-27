package com.drawlesschess.core.engine

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineLimits
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.EngineStrength
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
) {
    init {
        require(ply >= 1)
        require(expectedPointLoss == null || expectedPointLoss in 0.0..1.0)
    }
}

data class GameReviewResult(
    val gameId: String,
    val initialFen: String,
    val rules: RulesContractV1,
    val outcome: GameOutcome,
    val moves: List<ReviewedMove>,
    val engine: EngineIdentity?,
) {
    init {
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(moves.map { it.ply } == (1..moves.size).toList())
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
        val quality = when {
            loss <= 0.02 -> ReviewMoveQuality.BEST
            loss <= 0.06 -> ReviewMoveQuality.GOOD
            loss <= 0.12 -> ReviewMoveQuality.INACCURACY
            loss <= 0.22 -> ReviewMoveQuality.MISTAKE
            else -> ReviewMoveQuality.BLUNDER
        }
        return quality to loss
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
                        limits = EngineLimits(moveTimeMillis, 1),
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
            require(runCatching { ChessAdapter.transition(decision.positionBefore, response.bestMove) }.isSuccess) {
                "Engine returned illegal review move ${response.bestMove.value} at ply ${decision.ply}"
            }
            val primary = response.variations.sortedBy { it.rank }.first()
            val engineBest = response.bestMove
            val engineBestEvaluation = primary.evaluation()
            val terminal = decision.outcomeAfter

            var bestMove = engineBest
            var bestEvaluation: ReviewEvaluation? = engineBestEvaluation
            var line = primary.moves
            val playedEvaluation: ReviewEvaluation?
            val quality: ReviewMoveQuality?
            val expectedLoss: Double?

            if (terminal != null) {
                playedEvaluation = ReviewEvaluation.Terminal(terminal.winner)
                val alternatives = ChessRules.legalUciMoves(decision.positionBefore).map { move ->
                    val resulting = decision.sessionBefore.apply(ChessAdapter.transition(decision.positionBefore, move))
                    move to resulting.outcome
                }
                val immediateWins = alternatives.filter { (_, result) -> result?.winner == decision.mover }
                val avoidsImmediateLoss = alternatives.filter { (_, result) -> result == null || result.winner == decision.mover }
                when {
                    terminal.winner == decision.mover -> {
                        bestMove = decision.playedMove
                        bestEvaluation = playedEvaluation
                        line = listOf(decision.playedMove)
                        quality = ReviewMoveQuality.BEST
                        expectedLoss = 0.0
                    }
                    avoidsImmediateLoss.isEmpty() -> {
                        bestMove = decision.playedMove
                        bestEvaluation = playedEvaluation
                        line = listOf(decision.playedMove)
                        quality = ReviewMoveQuality.BEST
                        expectedLoss = 0.0
                    }
                    else -> {
                        val preferred = immediateWins.firstOrNull()?.first
                            ?: engineBest.takeIf { candidate -> avoidsImmediateLoss.any { it.first == candidate } }
                            ?: avoidsImmediateLoss.first().first
                        bestMove = preferred
                        if (preferred != engineBest) {
                            bestEvaluation = immediateWins.firstOrNull { it.first == preferred }
                                ?.second?.let { ReviewEvaluation.Terminal(it.winner) }
                            line = listOf(preferred)
                        }
                        val classified = bestEvaluation?.let {
                            GameReviewClassifier.classify(it, playedEvaluation, decision.mover)
                        }
                        if (classified?.first == ReviewMoveQuality.BEST) {
                            // Ending immediately is not a blunder when the engine confirms
                            // every continuation is already a forced loss.
                            bestMove = decision.playedMove
                            bestEvaluation = playedEvaluation
                            line = listOf(decision.playedMove)
                            quality = ReviewMoveQuality.BEST
                            expectedLoss = 0.0
                        } else {
                            quality = classified?.first ?: ReviewMoveQuality.BLUNDER
                            expectedLoss = classified?.second
                        }
                    }
                }
            } else if (decision.playedMove == engineBest) {
                playedEvaluation = engineBestEvaluation
                quality = ReviewMoveQuality.BEST
                expectedLoss = 0.0
            } else {
                playedEvaluation = responses.getOrNull(index + 1)?.primaryEvaluation()?.negated()
                val classified = playedEvaluation?.let {
                    GameReviewClassifier.classify(engineBestEvaluation, it, decision.mover)
                }
                quality = classified?.first
                expectedLoss = classified?.second
            }

            return ReviewedMove(
                ply = decision.ply,
                mover = decision.mover,
                playedMove = decision.playedMove,
                bestMove = bestMove,
                quality = quality,
                bestEvaluation = bestEvaluation,
                playedEvaluation = playedEvaluation,
                expectedPointLoss = expectedLoss,
                suggestedLine = line,
                fenBefore = decision.positionBefore.fen(),
                fenAfter = decision.positionAfter.fen(),
            )
        }

        fun EngineResponse.primaryEvaluation(): ReviewEvaluation =
            variations.sortedBy { it.rank }.first().evaluation()

        fun com.drawlesschess.core.PrincipalVariation.evaluation(): ReviewEvaluation =
            scoreCentipawns?.let(ReviewEvaluation::Centipawns)
                ?: ReviewEvaluation.Mate(requireNotNull(mateIn))

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
        val playedMove: UciMove,
        val outcomeAfter: GameOutcome?,
    ) {
        val mover: Side get() = positionBefore.sideToMove
    }
}
