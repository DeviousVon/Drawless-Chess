package com.drawlesschess.core.engine

import com.drawlesschess.core.EngineIdentity
import com.drawlesschess.core.EngineScoreBound
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import kotlin.math.roundToInt

const val DRAWLESS_ACCURACY_VERSION = 1
const val REVIEW_CLASSIFIER_VERSION = 1
const val REVIEW_SUMMARY_VERSION = 1
const val REVIEW_EXPLANATION_VERSION = 1
const val REVIEW_CONSTRAINED_ROOT_POLICY_VERSION = 1
const val REVIEW_RETAINED_PV_LENGTH = 12

/** Every setting that can change expensive engine evidence. */
data class ReviewAnalysisProfile(
    val moveTimeMillis: Long,
    val multiPv: Int,
    val constrainedRootPolicyVersion: Int = REVIEW_CONSTRAINED_ROOT_POLICY_VERSION,
    val retainedPvLength: Int = REVIEW_RETAINED_PV_LENGTH,
) {
    init {
        require(moveTimeMillis > 0)
        require(multiPv >= 1)
        require(constrainedRootPolicyVersion > 0)
        require(retainedPvLength >= 1)
    }
}

data class ReviewExplanationFacts(
    val capture: Boolean = false,
    val gaveCheck: Boolean = false,
    val forcedMove: Boolean = false,
    val materialSwingForMover: Int = 0,
    val terminalReason: EndReason? = null,
    val terminalWinner: Side? = null,
) {
    init {
        require((terminalReason == null) == (terminalWinner == null))
    }
}

data class CandidateEvidence(
    val rank: Int,
    val rootMove: UciMove,
    val evaluation: ReviewEvaluation,
    val expectedPoints: Double,
    val source: ReviewScoreSource,
    val bound: EngineScoreBound,
    val depth: Int?,
    val nodes: Long?,
    val principalVariation: List<UciMove>,
    val origin: ReviewLineOrigin,
) {
    init {
        require(rank >= 1)
        require(expectedPoints in 0.0..1.0)
        require(bound == EngineScoreBound.EXACT)
        require(depth == null || depth >= 0)
        require(nodes == null || nodes >= 0)
        require(principalVariation.isNotEmpty() && principalVariation.first() == rootMove)
    }

    companion object {
        fun exact(line: ReviewLine): CandidateEvidence = CandidateEvidence(
            rank = line.rank,
            rootMove = line.move,
            evaluation = requireNotNull(line.evaluation) { "Review candidate has no evaluation" },
            expectedPoints = requireNotNull(line.expectedPoints) {
                "Review candidate has no exact expected-points evidence"
            },
            source = requireNotNull(line.source) { "Review candidate has no score source" },
            bound = line.bound,
            depth = line.depth,
            nodes = line.nodes,
            principalVariation = line.moves.take(REVIEW_RETAINED_PV_LENGTH),
            origin = line.origin,
        )
    }
}

data class MoveEvidenceV2(
    val ply: Int,
    val mover: Side,
    val playedMove: UciMove,
    val positionKey: String,
    val authoritativeOutcomeAfter: GameOutcome?,
    val best: CandidateEvidence,
    val played: CandidateEvidence,
    val candidates: List<CandidateEvidence>,
    val legalMoveCount: Int,
    val facts: ReviewExplanationFacts,
) {
    init {
        require(ply >= 1 && positionKey.isNotBlank())
        require(legalMoveCount >= 1)
        require(played.rootMove == playedMove)
        require(candidates.isNotEmpty())
        require(candidates.map(CandidateEvidence::rank) == candidates.map(CandidateEvidence::rank).sorted())
        require(candidates.first().rank == 1)
        require(candidates.all { it.origin == ReviewLineOrigin.ROOT_MULTIPV })
        require(candidates.map(CandidateEvidence::rank).distinct().size == candidates.size)
        require(candidates.map(CandidateEvidence::rootMove).distinct().size == candidates.size)
        require(facts.forcedMove == (legalMoveCount == 1))
        require(facts.terminalReason == authoritativeOutcomeAfter?.reason)
        require(facts.terminalWinner == authoritativeOutcomeAfter?.winner)
    }
}

/** Complete, exact, locale-independent evidence linked to one canonical completed game. */
data class ReviewEvidenceV2(
    val schemaVersion: Int = REVIEW_EVIDENCE_SCHEMA_VERSION,
    val gameFingerprint: String,
    val rulesFingerprint: String,
    val scopeSide: Side,
    val engine: EngineIdentity,
    val profile: ReviewAnalysisProfile,
    val moves: List<MoveEvidenceV2>,
) {
    init {
        require(schemaVersion == 2)
        require(gameFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(rulesFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(engine.drawlessPatch == REVIEW_REQUIRED_DRAWLESS_PATCH_VERSION)
        require(moves.map(MoveEvidenceV2::ply) == moves.map(MoveEvidenceV2::ply).sorted())
        require(moves.map(MoveEvidenceV2::ply).distinct().size == moves.size)
        require(moves.all { it.mover == scopeSide })
    }

    companion object {
        fun from(
            result: GameReviewResult,
            moveTimeMillis: Long = DEFAULT_GAME_REVIEW_MOVE_TIME_MILLIS,
        ): ReviewEvidenceV2 {
            val side = (result.scope as? GameReviewScope.PlayerMoves)?.side
                ?: error("Complete product evidence must use player-only review scope")
            val engine = requireNotNull(result.engine) { "Review evidence has no engine identity" }
            val evidenceMoves = result.moves.map { move ->
                val evidence = requireNotNull(move.evidence)
                val candidates = evidence.lines.mapNotNull { line ->
                    runCatching { CandidateEvidence.exact(line) }.getOrNull()
                }
                val best = CandidateEvidence.exact(evidence.bestLine)
                val played = CandidateEvidence.exact(evidence.playedLine)
                MoveEvidenceV2(
                    ply = move.ply,
                    mover = move.mover,
                    playedMove = move.playedMove,
                    positionKey = RepetitionKey.of(ChessPosition.fromFen(move.fenBefore)).value,
                    authoritativeOutcomeAfter = move.explanationFacts.terminalReason?.let { reason ->
                        GameOutcome(requireNotNull(move.explanationFacts.terminalWinner), reason = reason)
                    },
                    best = best,
                    played = played,
                    candidates = candidates,
                    legalMoveCount = evidence.legalMoveCount,
                    facts = move.explanationFacts,
                )
            }
            return ReviewEvidenceV2(
                gameFingerprint = ReviewFingerprints.game(
                    initialFen = result.initialFen,
                    moves = result.gameMoves,
                    rules = result.rules,
                    outcome = result.outcome,
                ),
                rulesFingerprint = ReviewFingerprints.rules(result.rules),
                scopeSide = side,
                engine = engine,
                profile = ReviewAnalysisProfile(moveTimeMillis, GAME_REVIEW_MULTI_PV),
                moves = evidenceMoves,
            )
        }
    }
}

/** Drawless Accuracy V1 uses only exact expected-point loss and is intentionally simple. */
object DrawlessAccuracyV1 {
    fun calculate(moves: List<ReviewedMove>): Int? {
        val losses = moves.mapNotNull { move ->
            move.expectedPointLoss?.takeIf {
                move.evidence?.bestLine?.bound == EngineScoreBound.EXACT &&
                    move.evidence.playedLine.bound == EngineScoreBound.EXACT
            }
        }
        if (losses.isEmpty()) return null
        val meanLoss = losses.average().coerceIn(0.0, 1.0)
        return (100.0 * (1.0 - meanLoss)).roundToInt().coerceIn(0, 100)
    }
}

object ReviewFingerprints {
    fun rules(rules: RulesContractV1): String = PortableSha256.hex(
        canonical(
            "rules-v1",
            rules.schemaVersion.toString(),
            rules.preset.name,
            rules.stalemate.name,
            rules.deadPosition.name,
            rules.fiftyMove.name,
            rules.repetitionThreshold.toString(),
            rules.completingPlayerLosesRepetition.toString(),
            rules.forcedRepetitionException.toString(),
            rules.materialValues.pawn.toString(),
            rules.materialValues.knight.toString(),
            rules.materialValues.bishop.toString(),
            rules.materialValues.rook.toString(),
            rules.materialValues.queen.toString(),
            rules.bareKing.name,
        ),
    )

    fun game(
        initialFen: String,
        moves: List<UciMove>,
        rules: RulesContractV1,
        outcome: GameOutcome,
        recordFormatVersion: Int = 1,
    ): String = PortableSha256.hex(
        canonical(
            "completed-game-v1",
            recordFormatVersion.toString(),
            ChessPosition.fromFen(initialFen).fen(),
            moves.joinToString("\n", transform = UciMove::value),
            rules(rules),
            outcome.winner.name,
            outcome.loser.name,
            outcome.reason.name,
        ),
    )

    fun evidenceCacheKey(evidence: ReviewEvidenceV2): String = PortableSha256.hex(
        canonical(
            "review-evidence-cache-v2",
            evidence.gameFingerprint,
            evidence.rulesFingerprint,
            evidence.schemaVersion.toString(),
            evidence.engine.id,
            evidence.engine.build,
            evidence.engine.drawlessPatch.toString(),
            evidence.profile.moveTimeMillis.toString(),
            evidence.profile.multiPv.toString(),
            evidence.profile.constrainedRootPolicyVersion.toString(),
            evidence.profile.retainedPvLength.toString(),
        ),
    )

    fun derivedReviewKey(evidencePayloadDigest: String): String = PortableSha256.hex(
        canonical(
            "review-derived-v1",
            evidencePayloadDigest,
            REVIEW_CLASSIFIER_VERSION.toString(),
            ReviewGradingPolicy.CURRENT.version.toString(),
            DRAWLESS_ACCURACY_VERSION.toString(),
            REVIEW_SUMMARY_VERSION.toString(),
            REVIEW_EXPLANATION_VERSION.toString(),
        ),
    )

    fun payloadDigest(payload: ByteArray): String = PortableSha256.hex(payload)

    private fun canonical(vararg parts: String): ByteArray = buildString {
        parts.forEach { part ->
            append(part.encodeToByteArray().size)
            append(':')
            append(part)
        }
    }.encodeToByteArray()
}

/** Rebuilds current derived review output from locale-independent persisted evidence. */
fun ReviewEvidenceV2.toGameReviewResult(
    gameId: String,
    initialFen: String,
    gameMoves: List<UciMove>,
    rules: RulesContractV1,
    outcome: GameOutcome,
    recordFormatVersion: Int = 1,
): GameReviewResult {
    require(rulesFingerprint == ReviewFingerprints.rules(rules)) {
        "Persisted Review rules do not match the completed game"
    }
    require(
        gameFingerprint == ReviewFingerprints.game(
            initialFen = initialFen,
            moves = gameMoves,
            rules = rules,
            outcome = outcome,
            recordFormatVersion = recordFormatVersion,
        ),
    ) { "Persisted Review evidence does not match the completed game" }

    val evidenceByPly = moves.associateBy(MoveEvidenceV2::ply)
    var position = ChessPosition.fromFen(initialFen)
    val reviewed = mutableListOf<ReviewedMove>()
    gameMoves.forEachIndexed { index, playedMove ->
        val ply = index + 1
        val mover = position.sideToMove
        val fenBefore = position.fen()
        val persisted = evidenceByPly[ply]
        position = ChessRules.apply(position, playedMove)
        if (mover != scopeSide) {
            require(persisted == null) { "Review evidence grades a move outside its player scope" }
            return@forEachIndexed
        }
        val scopedEvidence = requireNotNull(persisted) { "Review evidence is missing player ply $ply" }
        require(
            scopedEvidence.mover == mover && scopedEvidence.playedMove == playedMove &&
                scopedEvidence.positionKey == RepetitionKey.of(ChessPosition.fromFen(fenBefore)).value,
        ) { "Review evidence does not match canonical player ply $ply" }

        val rootPosition = ChessPosition.fromFen(fenBefore)
        require(scopedEvidence.legalMoveCount == ChessRules.legalMoves(rootPosition).size) {
            "Persisted Review legal-move count does not match its position"
        }
        (scopedEvidence.candidates + scopedEvidence.best + scopedEvidence.played).distinct().forEach { line ->
            var replay = rootPosition
            line.principalVariation.forEach { move -> replay = ChessRules.apply(replay, move) }
        }
        val rootLines = scopedEvidence.candidates.map(CandidateEvidence::toReviewLine)
        val bestLine = rootLines.firstOrNull { line -> line.matches(scopedEvidence.best) }
            ?: scopedEvidence.best.toReviewLine()
        val playedLine = rootLines.firstOrNull { line -> line.matches(scopedEvidence.played) }
            ?: scopedEvidence.played.toReviewLine()
        val classified = GameReviewClassifier.classify(bestLine, playedLine)
            ?: error("Persisted exact Review evidence could not be classified")
        reviewed += ReviewedMove(
            ply = ply,
            mover = mover,
            playedMove = playedMove,
            bestMove = bestLine.move,
            quality = classified.first,
            bestEvaluation = bestLine.evaluation,
            playedEvaluation = playedLine.evaluation,
            expectedPointLoss = classified.second,
            suggestedLine = bestLine.moves,
            fenBefore = fenBefore,
            fenAfter = position.fen(),
            evidence = ReviewMoveEvidence(
                lines = rootLines,
                bestLine = bestLine,
                playedLine = playedLine,
                playedLineRank = playedLine.rank.takeIf {
                    playedLine.origin == ReviewLineOrigin.ROOT_MULTIPV
                },
                legalMoveCount = scopedEvidence.legalMoveCount,
                forced = scopedEvidence.legalMoveCount == 1,
                usedAdjacentFallback = playedLine.origin == ReviewLineOrigin.CONSTRAINED_ROOT,
            ),
            explanationFacts = scopedEvidence.facts,
        )
    }
    require(evidenceByPly.keys == reviewed.mapTo(linkedSetOf(), ReviewedMove::ply)) {
        "Review evidence contains a ply outside the canonical game"
    }
    return GameReviewResult(
        gameId = gameId,
        initialFen = initialFen,
        rules = rules,
        outcome = outcome,
        moves = reviewed,
        engine = engine,
        scope = GameReviewScope.PlayerMoves(scopeSide),
        gameMoves = gameMoves,
    )
}

private fun CandidateEvidence.toReviewLine(): ReviewLine = ReviewLine(
    rank = rank,
    move = rootMove,
    evaluation = evaluation,
    expectedPoints = expectedPoints,
    source = source,
    bound = bound,
    depth = depth,
    nodes = nodes,
    moves = principalVariation,
    origin = origin,
)

private fun ReviewLine.matches(candidate: CandidateEvidence): Boolean =
    rank == candidate.rank && move == candidate.rootMove && evaluation == candidate.evaluation &&
        expectedPoints == candidate.expectedPoints && source == candidate.source &&
        bound == candidate.bound && depth == candidate.depth && nodes == candidate.nodes &&
        moves == candidate.principalVariation && origin == candidate.origin

/** Small common-code SHA-256 implementation so Android and iOS calculate identical cache keys. */
private object PortableSha256 {
    private val roundConstants = intArrayOf(
        0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(),
        0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(),
        0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
        0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(),
        0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
        0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(),
        0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
        0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )

    fun hex(input: ByteArray): String {
        val bitLength = input.size.toLong() * 8L
        val paddedSize = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedSize)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (index in 0 until 8) {
            padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }

        val hash = intArrayOf(
            0x6a09e667u.toInt(), 0xbb67ae85u.toInt(), 0x3c6ef372u.toInt(),
            0xa54ff53au.toInt(), 0x510e527fu.toInt(), 0x9b05688cu.toInt(),
            0x1f83d9abu.toInt(), 0x5be0cd19u.toInt(),
        )
        val words = IntArray(64)
        for (offset in padded.indices step 64) {
            for (index in 0 until 16) {
                val at = offset + index * 4
                words[index] =
                    ((padded[at].toInt() and 0xff) shl 24) or
                    ((padded[at + 1].toInt() and 0xff) shl 16) or
                    ((padded[at + 2].toInt() and 0xff) shl 8) or
                    (padded[at + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = rotateRight(words[index - 15], 7) xor
                    rotateRight(words[index - 15], 18) xor (words[index - 15] ushr 3)
                val s1 = rotateRight(words[index - 2], 17) xor
                    rotateRight(words[index - 2], 19) xor (words[index - 2] ushr 10)
                words[index] = words[index - 16] + s0 + words[index - 7] + s1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]
            for (index in 0 until 64) {
                val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val choose = (e and f) xor (e.inv() and g)
                val temporary1 = h + sum1 + choose + roundConstants[index] + words[index]
                val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temporary2 = sum0 + majority
                h = g
                g = f
                f = e
                e = d + temporary1
                d = c
                c = b
                b = a
                a = temporary1 + temporary2
            }
            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
        }
        return hash.joinToString("") { value ->
            value.toUInt().toString(16).padStart(8, '0')
        }
    }

    private fun rotateRight(value: Int, bits: Int): Int =
        (value ushr bits) or (value shl (32 - bits))
}
