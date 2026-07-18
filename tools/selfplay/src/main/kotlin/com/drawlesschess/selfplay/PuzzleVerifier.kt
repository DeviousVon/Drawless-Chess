package com.drawlesschess.selfplay

import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.GameOutcome
import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.chess.SanNotation
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class PuzzleVerificationOptions(
    val candidateInput: Path,
    val output: Path,
    val enginePath: Path,
    val variantsPath: Path,
    val primaryNodes: Long = 250_000L,
    val confirmNodes: Long = 1_000_000L,
    val multiPv: Int = 5,
    val parallelism: Int = 4,
    val hashMb: Int = 64,
    val minAdvantageCp: Int = 150,
    val minGapCp: Int = 120,
    val maxCandidates: Int? = null,
    val replace: Boolean = false,
) {
    init {
        require(primaryNodes in 1_000L..10_000_000_000L)
        require(confirmNodes >= primaryNodes && confirmNodes <= 10_000_000_000L)
        require(multiPv in 2..10)
        require(parallelism in 1..16)
        require(hashMb in 1..1024)
        require(minAdvantageCp in 0..10_000)
        require(minGapCp in 1..10_000)
        require(maxCandidates == null || maxCandidates > 0)
    }

    fun fingerprintFields(candidateSha256: String, identity: RunIdentity): Map<String, String> =
        linkedMapOf(
            "schemaVersion" to PUZZLE_VERIFICATION_SCHEMA_VERSION.toString(),
            "candidateSha256" to candidateSha256,
            "engineSha256" to identity.engineSha256,
            "variantsSha256" to identity.variantsSha256,
            "runtimeSha256" to identity.runtimeSha256,
            "primaryNodes" to primaryNodes.toString(),
            "confirmNodes" to confirmNodes.toString(),
            "multiPv" to multiPv.toString(),
            "hashMb" to hashMb.toString(),
            "minAdvantageCp" to minAdvantageCp.toString(),
            "minGapCp" to minGapCp.toString(),
        )
}

enum class PuzzleVerificationStatus {
    VERIFIED,
    REJECTED,
    ERROR,
}

data class PuzzleVerificationResult(
    val candidateId: String,
    val status: PuzzleVerificationStatus,
    val reason: String,
    val rootSide: Side?,
    val solutionMove: String,
    val solutionSan: String?,
    val solutionPvUci: List<String>,
    val solutionPvSan: List<String>,
    val terminalOutcome: GameOutcome?,
    val primary: UciSearchResult?,
    val confirmation: UciSearchResult?,
    val immediateWinningMoves: List<String>,
    val errorType: String? = null,
    val errorMessage: String? = null,
) {
    fun toMap(fingerprint: String): Map<String, Any?> = linkedMapOf(
        "event" to "puzzle_verification",
        "verification_fingerprint" to fingerprint,
        "candidate_id" to candidateId,
        "status" to status.name.lowercase(Locale.ROOT),
        "reason" to reason,
        "root_side" to rootSide?.name,
        "solution_move" to solutionMove,
        "solution_san" to solutionSan,
        "solution_pv_uci" to solutionPvUci,
        "solution_pv_san" to solutionPvSan,
        "terminal_outcome" to terminalOutcome?.let(::outcomeMap),
        "immediate_winning_moves" to immediateWinningMoves,
        "primary" to primary?.toVerificationMap(),
        "confirmation" to confirmation?.toVerificationMap(),
        "error_type" to errorType,
        "error_message" to errorMessage,
        "finished_at" to Instant.now().toString(),
    )
}

data class PuzzleVerificationSummary(
    val scheduled: Int,
    val resumed: Int,
    val verified: Int,
    val rejected: Int,
    val errors: Int,
    val output: Path,
)

object PuzzleVerifier {
    private val supportedRules = PuzzleRules(
        RulesContractV1.Preset.DRAWLESS,
        DeadPositionPolicy.MATERIAL_VICTORY,
        FiftyMovePolicy.DISABLED,
    )

    fun verify(options: PuzzleVerificationOptions): PuzzleVerificationSummary {
        val candidateFile = PuzzleCandidateReader.read(options.candidateInput)
        val runtimeConfig = runtimeConfig(options)
        val identity = RunIdentityFactory.create(runtimeConfig)
        val fields = options.fingerprintFields(candidateFile.sha256, identity)
        val fingerprint = verificationFingerprint(fields)
        if (options.replace) Files.deleteIfExists(options.output.toAbsolutePath().normalize())

        PuzzleVerificationReport.open(options.output, fingerprint, fields).use { report ->
            val allPending = candidateFile.candidates.filterNot { it.candidateId in report.completedIds }
            val pending = options.maxCandidates?.let(allPending::take) ?: allPending
            if (pending.isEmpty()) {
                return PuzzleVerificationSummary(
                    scheduled = 0,
                    resumed = report.completedIds.size,
                    verified = 0,
                    rejected = 0,
                    errors = 0,
                    output = report.path,
                )
            }

            val queue = ConcurrentLinkedQueue(pending)
            val verified = AtomicInteger()
            val rejected = AtomicInteger()
            val errors = AtomicInteger()
            val workerIds = AtomicInteger()
            val executor = Executors.newFixedThreadPool(options.parallelism) { action ->
                Thread(action, "drawless-puzzle-verifier-${workerIds.incrementAndGet()}")
            }
            repeat(options.parallelism.coerceAtMost(pending.size)) { workerIndex ->
                executor.submit {
                    UciEngineProcess.start(
                        config = runtimeConfig,
                        role = "puzzle-worker-${workerIndex + 1}",
                        strength = UciStrength.Skill(20),
                        multiPv = options.multiPv,
                        analyseMode = true,
                    ).use { engine ->
                        while (true) {
                            val candidate = queue.poll() ?: break
                            val result = try {
                                verifyCandidate(candidate, options, engine)
                            } catch (error: Throwable) {
                                PuzzleVerificationResult(
                                    candidateId = candidate.candidateId,
                                    status = PuzzleVerificationStatus.ERROR,
                                    reason = "verification_exception",
                                    rootSide = runCatching {
                                        ChessPosition.fromFen(candidate.candidateFen).sideToMove
                                    }.getOrNull(),
                                    solutionMove = candidate.solutionMove,
                                    solutionSan = null,
                                    solutionPvUci = emptyList(),
                                    solutionPvSan = emptyList(),
                                    terminalOutcome = null,
                                    primary = null,
                                    confirmation = null,
                                    immediateWinningMoves = emptyList(),
                                    errorType = error::class.java.name,
                                    errorMessage = error.message ?: "no error message",
                                )
                            }
                            report.write(result)
                            when (result.status) {
                                PuzzleVerificationStatus.VERIFIED -> verified.incrementAndGet()
                                PuzzleVerificationStatus.REJECTED -> rejected.incrementAndGet()
                                PuzzleVerificationStatus.ERROR -> errors.incrementAndGet()
                            }
                        }
                    }
                }
            }
            executor.shutdown()
            check(executor.awaitTermination(7, TimeUnit.DAYS)) {
                "Puzzle verifier workers did not terminate"
            }
            val summary = PuzzleVerificationSummary(
                scheduled = pending.size,
                resumed = report.completedIds.size,
                verified = verified.get(),
                rejected = rejected.get(),
                errors = errors.get(),
                output = report.path,
            )
            check(summary.errors == 0) {
                "${summary.errors} puzzle verification error(s); see ${summary.output}"
            }
            return summary
        }
    }

    internal fun verifyCandidate(
        candidate: PuzzleCandidate,
        options: PuzzleVerificationOptions,
        engine: UciEngineProcess,
    ): PuzzleVerificationResult {
        fun rejected(
            reason: String,
            state: ReplayedCandidate? = null,
            primary: UciSearchResult? = null,
            confirmation: UciSearchResult? = null,
            immediate: List<String> = emptyList(),
            pv: ReplayedPv? = null,
        ) = PuzzleVerificationResult(
            candidateId = candidate.candidateId,
            status = PuzzleVerificationStatus.REJECTED,
            reason = reason,
            rootSide = state?.position?.sideToMove,
            solutionMove = candidate.solutionMove,
            solutionSan = state?.let { runCatching { SanNotation.format(it.position, UciMove(candidate.solutionMove)) }.getOrNull() },
            solutionPvUci = pv?.uciMoves.orEmpty(),
            solutionPvSan = pv?.sanMoves.orEmpty(),
            terminalOutcome = pv?.outcome,
            primary = primary,
            confirmation = confirmation,
            immediateWinningMoves = immediate,
        )

        if (candidate.rules != supportedRules) return rejected("unsupported_rules")
        val state = replayCandidate(candidate)
        val legalMoves = ChessRules.legalUciMoves(state.position)
        if (legalMoves.size < 2) return rejected("only_one_legal_move", state)
        val selected = UciMove(candidate.solutionMove)
        if (selected !in legalMoves) return rejected("recorded_solution_is_illegal", state)
        val recomputedSan = SanNotation.format(state.position, selected)
        if (recomputedSan != candidate.solutionSan) return rejected("source_san_mismatch", state)

        val immediateWinning = legalMoves.mapNotNull { move ->
            val outcome = state.session.apply(ChessAdapter.transition(state.position, move)).outcome
            move.value.takeIf { outcome?.winner == state.position.sideToMove }
        }.sorted()

        if (candidate.kind == PuzzleCandidateKind.TERMINAL_MOVE) {
            val selectedOutcome = state.session.apply(ChessAdapter.transition(state.position, selected)).outcome
                ?: return rejected("terminal_candidate_does_not_end", state, immediate = immediateWinning)
            if (
                selectedOutcome.reason != candidate.expectedEndReason ||
                selectedOutcome.winner != candidate.expectedWinner ||
                selectedOutcome.loser != candidate.expectedLoser
            ) {
                return rejected("terminal_outcome_mismatch", state, immediate = immediateWinning)
            }
            if (selectedOutcome.winner != state.position.sideToMove) {
                return rejected("terminal_move_loses_for_solver", state, immediate = immediateWinning)
            }
            if (immediateWinning != listOf(candidate.solutionMove)) {
                return rejected("multiple_immediate_wins", state, immediate = immediateWinning)
            }
        }

        engine.resetGame()
        val primary = engine.search(
            candidate.initialFen,
            candidate.movesBefore.map(::UciMove),
            SearchLimit.Nodes(options.primaryNodes),
        )
        if (!analysisHasSolution(primary, candidate.solutionMove)) {
            return rejected("primary_best_move_disagrees", state, primary, immediate = immediateWinning)
        }
        engine.resetGame()
        val confirmation = engine.search(
            candidate.initialFen,
            candidate.movesBefore.map(::UciMove),
            SearchLimit.Nodes(options.confirmNodes),
        )
        if (!analysisHasSolution(confirmation, candidate.solutionMove)) {
            return rejected(
                "confirmation_best_move_disagrees",
                state,
                primary,
                confirmation,
                immediateWinning,
            )
        }
        val top = confirmation.variations.firstOrNull { it.rank == 1 }
            ?: return rejected("confirmation_missing_primary_variation", state, primary, confirmation, immediateWinning)
        val second = confirmation.variations.firstOrNull { it.rank == 2 }
            ?: return rejected("confirmation_missing_second_variation", state, primary, confirmation, immediateWinning)
        if (!isUnique(top, second, candidate.kind, options)) {
            return rejected("ambiguous_engine_alternatives", state, primary, confirmation, immediateWinning)
        }
        val primaryTop = primary.variations.firstOrNull { it.rank == 1 }
            ?: return rejected("primary_missing_primary_variation", state, primary, confirmation, immediateWinning)
        if (candidate.kind == PuzzleCandidateKind.FORCED_MATE) {
            if (!primaryTop.isWinningMate() || !top.isWinningMate()) {
                return rejected("forced_mate_not_stable", state, primary, confirmation, immediateWinning)
            }
        }

        val pv = replayPv(state, top.moves)
        if (pv.outcome?.winner != state.position.sideToMove) {
            return rejected("principal_variation_not_terminal_win", state, primary, confirmation, immediateWinning, pv)
        }
        if (
            candidate.kind == PuzzleCandidateKind.TERMINAL_MOVE &&
            (pv.outcome.reason != candidate.expectedEndReason || pv.uciMoves.size != 1)
        ) {
            return rejected("terminal_principal_variation_mismatch", state, primary, confirmation, immediateWinning, pv)
        }

        return PuzzleVerificationResult(
            candidateId = candidate.candidateId,
            status = PuzzleVerificationStatus.VERIFIED,
            reason = "verified_unique_terminal_win",
            rootSide = state.position.sideToMove,
            solutionMove = candidate.solutionMove,
            solutionSan = recomputedSan,
            solutionPvUci = pv.uciMoves,
            solutionPvSan = pv.sanMoves,
            terminalOutcome = pv.outcome,
            primary = primary,
            confirmation = confirmation,
            immediateWinningMoves = immediateWinning,
        )
    }

    private fun runtimeConfig(options: PuzzleVerificationOptions): SelfPlayConfig {
        val engine = options.enginePath.toAbsolutePath().normalize()
        val variants = options.variantsPath.toAbsolutePath().normalize()
        require(Files.isRegularFile(engine) && Files.isExecutable(engine)) {
            "Verifier engine is not an executable file: $engine"
        }
        require(Files.isRegularFile(variants)) { "Verifier variants file is missing: $variants" }
        return SelfPlayConfig(
            sourcePath = options.candidateInput.toAbsolutePath().normalize(),
            runLabel = "puzzle-verifier",
            enginePath = engine,
            variantsPath = variants,
            outputPath = options.output.toAbsolutePath().normalize(),
            jobSource = JobSource.SINGLE,
            openingsPath = null,
            ladderLevelsPath = null,
            adjacentMatchupsPath = null,
            games = 1,
            parallelGames = 1,
            initialFen = ChessPosition.START_FEN,
            openingMoves = emptyList(),
            variant = RulesContractV1.Preset.DRAWLESS,
            deadPosition = DeadPositionPolicy.MATERIAL_VICTORY,
            fiftyMove = FiftyMovePolicy.DISABLED,
            whiteStrength = UciStrength.Skill(20),
            blackStrength = UciStrength.Skill(20),
            searchLimit = SearchLimit.Nodes(options.confirmNodes),
            maxPlies = 300,
            pairColors = false,
            failFast = true,
            markCappedForContinuation = false,
            hashMb = options.hashMb,
            handshakeTimeoutMillis = 30_000L,
            readyTimeoutMillis = 30_000L,
            searchTimeoutMillis = 600_000L,
            stopGraceMillis = 2_000L,
            quitTimeoutMillis = 3_000L,
        )
    }
}

private data class ReplayedCandidate(
    val position: ChessPosition,
    val session: GameSession,
)

private data class ReplayedPv(
    val uciMoves: List<String>,
    val sanMoves: List<String>,
    val outcome: GameOutcome?,
)

private fun replayCandidate(candidate: PuzzleCandidate): ReplayedCandidate {
    var position = ChessPosition.fromFen(candidate.initialFen)
    var session = GameSession.newGame(
        gameId = "puzzle-${candidate.candidateId}",
        rules = candidate.rules.contract,
        initialPositionKey = RepetitionKey.of(position),
        sideToMove = position.sideToMove,
    )
    candidate.movesBefore.forEachIndexed { index, encoded ->
        check(session.outcome == null) { "Candidate history ended before root at ply ${index + 1}" }
        val move = UciMove(encoded)
        require(move in ChessRules.legalUciMoves(position)) {
            "Candidate history contains illegal move $encoded at ply ${index + 1}"
        }
        session = session.apply(ChessAdapter.transition(position, move))
        position = ChessRules.apply(position, move)
    }
    check(session.outcome == null) { "Candidate root is already terminal" }
    require(position.fen() == candidate.candidateFen) {
        "Candidate FEN does not match replayed history"
    }
    return ReplayedCandidate(position, session)
}

private fun replayPv(root: ReplayedCandidate, moves: List<UciMove>): ReplayedPv {
    var position = root.position
    var session = root.session
    val uci = mutableListOf<String>()
    val san = mutableListOf<String>()
    moves.forEach { move ->
        if (session.outcome != null) return@forEach
        require(move in ChessRules.legalUciMoves(position)) { "Engine PV contains illegal move ${move.value}" }
        san += SanNotation.format(position, move)
        session = session.apply(ChessAdapter.transition(position, move))
        position = ChessRules.apply(position, move)
        uci += move.value
    }
    return ReplayedPv(uci, san, session.outcome)
}

private fun analysisHasSolution(analysis: UciSearchResult, solution: String): Boolean =
    analysis.move.value == solution &&
        analysis.variations.firstOrNull { it.rank == 1 }?.moves?.firstOrNull()?.value == solution

private fun isUnique(
    top: UciVariation,
    second: UciVariation,
    kind: PuzzleCandidateKind,
    options: PuzzleVerificationOptions,
): Boolean {
    if (top.moves.firstOrNull() == second.moves.firstOrNull()) return false
    if (kind == PuzzleCandidateKind.FORCED_MATE) {
        return top.isWinningMate() && !second.isWinningMate()
    }
    if (top.isWinningMate()) return !second.isWinningMate()
    if (top.scoreType != "cp") return false
    val secondValue = when (second.scoreType) {
        "cp" -> second.scoreValue
        "mate" -> if (second.scoreValue > 0) Int.MAX_VALUE / 2 else Int.MIN_VALUE / 2
        else -> return false
    }
    return top.scoreValue >= options.minAdvantageCp &&
        top.scoreValue.toLong() - secondValue.toLong() >= options.minGapCp
}

private fun UciVariation.isWinningMate(): Boolean = scoreType == "mate" && scoreValue > 0

private fun outcomeMap(outcome: GameOutcome): Map<String, Any?> = linkedMapOf(
    "winner" to outcome.winner.name,
    "loser" to outcome.loser.name,
    "reason" to outcome.reason.name,
)

private fun UciVariation.toVerificationMap(): Map<String, Any?> = linkedMapOf(
    "rank" to rank,
    "depth" to depth,
    "nodes" to nodes,
    "score_type" to scoreType,
    "score_value" to scoreValue,
    "pv" to moves.map { it.value },
)

private fun UciSearchResult.toVerificationMap(): Map<String, Any?> = linkedMapOf(
    "best_move" to move.value,
    "ponder" to ponder?.value,
    "elapsed_ms" to elapsedMillis,
    "depth" to depth,
    "nodes" to nodes,
    "score_type" to scoreType,
    "score_value" to scoreValue,
    "variations" to variations.map(UciVariation::toVerificationMap),
)

private fun verificationFingerprint(fields: Map<String, String>): String = sha256Text(
    fields.toSortedMap().entries.joinToString("\u0000", postfix = "\u0000") { (key, value) -> "$key\u0000$value" },
)

private class PuzzleVerificationReport private constructor(
    val path: Path,
    private val completedIdsMutable: MutableSet<String>,
    private val fingerprint: String,
    private val writer: java.io.BufferedWriter,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    val completedIds: Set<String>
        get() = completedIdsMutable

    @Synchronized
    fun write(result: PuzzleVerificationResult) {
        require(result.candidateId !in completedIdsMutable) {
            "Candidate was already verified: ${result.candidateId}"
        }
        writer.appendLine(Json.encode(result.toMap(fingerprint)))
        writer.flush()
        completedIdsMutable.add(result.candidateId)
    }

    override fun close() {
        var first: Throwable? = null
        try {
            writer.close()
        } catch (error: Throwable) {
            first = error
        }
        try {
            lock.release()
        } catch (error: Throwable) {
            if (first == null) first = error else first.addSuppressed(error)
        }
        try {
            channel.close()
        } catch (error: Throwable) {
            if (first == null) first = error else first.addSuppressed(error)
        }
        first?.let { throw it }
    }

    companion object {
        fun open(
            output: Path,
            fingerprint: String,
            fields: Map<String, String>,
        ): PuzzleVerificationReport {
            val path = output.toAbsolutePath().normalize()
            path.parent?.let(Files::createDirectories)
            require(!Files.exists(path) || Files.isRegularFile(path)) {
                "Verification output is not a regular file: $path"
            }
            val lockPath = path.resolveSibling("${path.fileName}.lock")
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } ?: error("Another verifier is already using $path")
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
            try {
                val completed = linkedSetOf<String>()
                if (Files.isRegularFile(path) && Files.size(path) > 0L) {
                    Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEachIndexed { index, encoded ->
                            require(encoded.isNotBlank()) { "Blank verification record at line ${index + 1}" }
                            val record = Json.decodeObject(encoded)
                            if (index == 0) {
                                require(record.requiredString("event") == "puzzle_verification_header")
                                require(record.requiredInt("schema_version") == PUZZLE_VERIFICATION_SCHEMA_VERSION)
                                require(record.requiredSha256("verification_fingerprint") == fingerprint)
                                val actualFields = record.requiredObject("config").mapValues { (_, value) ->
                                    value as? String ?: error("Verification config values must be strings")
                                }
                                require(actualFields == fields) { "Existing verification config does not match" }
                            } else {
                                require(record.requiredString("event") == "puzzle_verification")
                                require(record.requiredSha256("verification_fingerprint") == fingerprint)
                                require(completed.add(record.requiredSha256("candidate_id"))) {
                                    "Duplicate verification candidate at line ${index + 1}"
                                }
                            }
                        }
                    }
                }
                val writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                if (Files.size(path) == 0L) {
                    writer.appendLine(
                        Json.encode(
                            linkedMapOf(
                                "event" to "puzzle_verification_header",
                                "schema_version" to PUZZLE_VERIFICATION_SCHEMA_VERSION,
                                "verification_fingerprint" to fingerprint,
                                "created_at" to Instant.now().toString(),
                                "config" to fields,
                            ),
                        ),
                    )
                    writer.flush()
                }
                return PuzzleVerificationReport(path, completed, fingerprint, writer, channel, lock)
            } catch (error: Throwable) {
                lock.release()
                channel.close()
                throw error
            }
        }
    }
}
