package com.drawlesschess.selfplay

import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.EndReason
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.Side
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

internal const val PUZZLE_CANDIDATE_SCHEMA_VERSION = 1
internal const val PUZZLE_VERIFICATION_SCHEMA_VERSION = 1

enum class PuzzleCandidateKind {
    TERMINAL_MOVE,
    FORCED_MATE,
}

data class PuzzleRules(
    val preset: RulesContractV1.Preset,
    val deadPosition: DeadPositionPolicy,
    val fiftyMove: FiftyMovePolicy,
) {
    val contract: RulesContractV1 = when (preset) {
        RulesContractV1.Preset.DRAWLESS -> RulesContractV1.drawless(deadPosition, fiftyMove)
        RulesContractV1.Preset.ESCAPE -> RulesContractV1.escape(deadPosition, fiftyMove)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "preset" to preset.name,
        "dead_position" to deadPosition.name,
        "fifty_move" to fiftyMove.name,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PuzzleRules = PuzzleRules(
            preset = enumValueOf(map.requiredString("preset")),
            deadPosition = enumValueOf(map.requiredString("dead_position")),
            fiftyMove = enumValueOf(map.requiredString("fifty_move")),
        )

        fun fromReportConfig(config: Map<String, String>): PuzzleRules = PuzzleRules(
            preset = when (config.getValue("variant")) {
                "drawless" -> RulesContractV1.Preset.DRAWLESS
                "escape" -> RulesContractV1.Preset.ESCAPE
                else -> error("Unsupported report variant '${config.getValue("variant")}'")
            },
            deadPosition = when (config.getValue("deadPosition")) {
                "material_victory" -> DeadPositionPolicy.MATERIAL_VICTORY
                "final_capture_victory" -> DeadPositionPolicy.FINAL_CAPTURE_VICTORY
                else -> error("Unsupported report deadPosition '${config.getValue("deadPosition")}'")
            },
            fiftyMove = when (config.getValue("fiftyMove")) {
                "disabled" -> FiftyMovePolicy.DISABLED
                "completing_player_loses" -> FiftyMovePolicy.COMPLETING_PLAYER_LOSES
                "forced_move_exception" -> FiftyMovePolicy.FORCED_MOVE_EXCEPTION
                "material_victory" -> FiftyMovePolicy.MATERIAL_VICTORY
                else -> error("Unsupported report fiftyMove '${config.getValue("fiftyMove")}'")
            },
        )
    }
}

data class PuzzleCandidate(
    val candidateId: String,
    val kind: PuzzleCandidateKind,
    val rules: PuzzleRules,
    val initialFen: String,
    val movesBefore: List<String>,
    val candidateFen: String,
    val solutionMove: String,
    val solutionSan: String,
    val expectedEndReason: EndReason?,
    val expectedWinner: Side?,
    val expectedLoser: Side?,
    val sourceEndReason: EndReason,
    val sourceWinner: Side,
    val sourceLoser: Side,
    val sourceReport: String,
    val sourceReportSha256: String,
    val sourceRunFingerprint: String,
    val sourceJobId: String,
    val sourceEngineSha256: String,
    val sourceVariantsSha256: String,
    val sourceRuntimeSha256: String,
    val sourcePly: Int,
    val sourceOpeningPlies: Int,
    val sourceScoreMate: Int?,
    val sourceOccurrences: Int = 1,
) {
    init {
        require(candidateId.matches(Regex("[0-9a-f]{64}")))
        require(sourceReportSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceRunFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceEngineSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceVariantsSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceRuntimeSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourcePly == movesBefore.size + 1)
        require(sourceOpeningPlies in 0 until sourcePly)
        require(sourceOccurrences > 0)
        require((expectedEndReason == null) == (kind != PuzzleCandidateKind.TERMINAL_MOVE))
        require((expectedWinner == null) == (expectedEndReason == null))
        require((expectedLoser == null) == (expectedEndReason == null))
        require(sourceScoreMate == null || sourceScoreMate > 0)
    }

    fun withAdditionalOccurrence(): PuzzleCandidate = copy(sourceOccurrences = sourceOccurrences + 1)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "event" to "puzzle_candidate",
        "candidate_id" to candidateId,
        "kind" to kind.name.lowercase(Locale.ROOT),
        "rules" to rules.toMap(),
        "initial_fen" to initialFen,
        "moves_before" to movesBefore,
        "candidate_fen" to candidateFen,
        "solution_move" to solutionMove,
        "solution_san" to solutionSan,
        "expected_end_reason" to expectedEndReason?.name,
        "expected_winner" to expectedWinner?.name,
        "expected_loser" to expectedLoser?.name,
        "source_end_reason" to sourceEndReason.name,
        "source_winner" to sourceWinner.name,
        "source_loser" to sourceLoser.name,
        "source_report" to sourceReport,
        "source_report_sha256" to sourceReportSha256,
        "source_run_fingerprint" to sourceRunFingerprint,
        "source_job_id" to sourceJobId,
        "source_engine_sha256" to sourceEngineSha256,
        "source_variants_sha256" to sourceVariantsSha256,
        "source_runtime_sha256" to sourceRuntimeSha256,
        "source_ply" to sourcePly,
        "source_opening_plies" to sourceOpeningPlies,
        "source_score_mate" to sourceScoreMate,
        "source_occurrences" to sourceOccurrences,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PuzzleCandidate {
            require(map.requiredString("event") == "puzzle_candidate")
            val kind = enumValueOf<PuzzleCandidateKind>(
                map.requiredString("kind").uppercase(Locale.ROOT),
            )
            return PuzzleCandidate(
                candidateId = map.requiredSha256("candidate_id"),
                kind = kind,
                rules = PuzzleRules.fromMap(map.requiredObject("rules")),
                initialFen = map.requiredString("initial_fen"),
                movesBefore = map.requiredStringList("moves_before"),
                candidateFen = map.requiredString("candidate_fen"),
                solutionMove = map.requiredString("solution_move"),
                solutionSan = map.requiredString("solution_san"),
                expectedEndReason = map.optionalEnum("expected_end_reason"),
                expectedWinner = map.optionalEnum("expected_winner"),
                expectedLoser = map.optionalEnum("expected_loser"),
                sourceEndReason = enumValueOf(map.requiredString("source_end_reason")),
                sourceWinner = enumValueOf(map.requiredString("source_winner")),
                sourceLoser = enumValueOf(map.requiredString("source_loser")),
                sourceReport = map.requiredString("source_report"),
                sourceReportSha256 = map.requiredSha256("source_report_sha256"),
                sourceRunFingerprint = map.requiredSha256("source_run_fingerprint"),
                sourceJobId = map.requiredString("source_job_id"),
                sourceEngineSha256 = map.requiredSha256("source_engine_sha256"),
                sourceVariantsSha256 = map.requiredSha256("source_variants_sha256"),
                sourceRuntimeSha256 = map.requiredSha256("source_runtime_sha256"),
                sourcePly = map.requiredInt("source_ply"),
                sourceOpeningPlies = map.requiredInt("source_opening_plies"),
                sourceScoreMate = map.optionalInt("source_score_mate"),
                sourceOccurrences = map.requiredInt("source_occurrences"),
            )
        }
    }
}

data class PuzzleMiningSummary(
    val reports: Int,
    val games: Int,
    val censoredGames: Int,
    val terminalCandidates: Int,
    val forcedMateCandidates: Int,
    val uniqueCandidates: Int,
    val duplicateCandidates: Int,
    val output: Path,
)

object PuzzleMiner {
    fun mine(inputs: List<Path>, output: Path, replace: Boolean = false): PuzzleMiningSummary {
        require(inputs.isNotEmpty()) { "At least one puzzle mining input is required" }
        val normalizedOutput = output.toAbsolutePath().normalize()
        require(replace || !Files.exists(normalizedOutput)) {
            "Puzzle candidate output already exists; pass --replace to overwrite: $normalizedOutput"
        }
        val reports = discoverReports(inputs)
        require(reports.isNotEmpty()) { "No self-play JSONL reports were found" }
        require(reports.none { it == normalizedOutput }) { "Candidate output cannot also be an input" }

        val candidates = linkedMapOf<String, PuzzleCandidate>()
        val inputManifest = mutableListOf<Map<String, Any?>>()
        var games = 0
        var censoredGames = 0
        var terminalCandidates = 0
        var forcedMateCandidates = 0
        var duplicates = 0

        reports.forEach { report ->
            val reportHash = sha256(report)
            inputManifest += linkedMapOf(
                "path" to report.toString(),
                "sha256" to reportHash,
            )
            var header: ReportHeader? = null
            Files.newBufferedReader(report, StandardCharsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, encoded ->
                    require(encoded.isNotBlank()) { "Blank record in $report at line ${index + 1}" }
                    val record = Json.decodeObject(encoded)
                    when (record.requiredString("event")) {
                        "run_header" -> {
                            require(index == 0 && header == null) {
                                "run_header must be the first and only header in $report"
                            }
                            header = ReportHeader.fromRecord(record)
                        }
                        "game" -> {
                            val sourceHeader = checkNotNull(header) {
                                "Game precedes run_header in $report at line ${index + 1}"
                            }
                            games++
                            if (record.requiredBoolean("censored")) {
                                censoredGames++
                            } else {
                                val mined = mineGame(report, reportHash, sourceHeader, record)
                                mined.forEach { candidate ->
                                    if (candidate.kind == PuzzleCandidateKind.TERMINAL_MOVE) {
                                        terminalCandidates++
                                    } else {
                                        forcedMateCandidates++
                                    }
                                    val previous = candidates[candidate.candidateId]
                                    if (previous == null) {
                                        candidates[candidate.candidateId] = candidate
                                    } else {
                                        candidates[candidate.candidateId] = previous.withAdditionalOccurrence()
                                        duplicates++
                                    }
                                }
                            }
                        }
                        "invocation_started", "game_started", "game_failure", "invocation_summary" -> Unit
                        else -> error("Unknown self-play event in $report at line ${index + 1}")
                    }
                }
            }
            require(header != null) { "Self-play report has no run_header: $report" }
        }

        normalizedOutput.parent?.let(Files::createDirectories)
        val temporary = normalizedOutput.resolveSibling(".${normalizedOutput.fileName}.tmp.${ProcessHandle.current().pid()}")
        try {
            Files.newBufferedWriter(
                temporary,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { writer ->
                writer.appendLine(
                    Json.encode(
                        linkedMapOf(
                            "event" to "puzzle_candidate_header",
                            "schema_version" to PUZZLE_CANDIDATE_SCHEMA_VERSION,
                            "created_at" to Instant.now().toString(),
                            "input_reports" to inputManifest,
                            "candidate_count" to candidates.size,
                        ),
                    ),
                )
                candidates.values.sortedBy(PuzzleCandidate::candidateId).forEach { candidate ->
                    writer.appendLine(Json.encode(candidate.toMap()))
                }
            }
            moveAtomically(temporary, normalizedOutput, replace)
        } finally {
            Files.deleteIfExists(temporary)
        }

        return PuzzleMiningSummary(
            reports = reports.size,
            games = games,
            censoredGames = censoredGames,
            terminalCandidates = terminalCandidates,
            forcedMateCandidates = forcedMateCandidates,
            uniqueCandidates = candidates.size,
            duplicateCandidates = duplicates,
            output = normalizedOutput,
        )
    }

    private fun mineGame(
        report: Path,
        reportHash: String,
        header: ReportHeader,
        record: Map<String, Any?>,
    ): List<PuzzleCandidate> {
        require(record.requiredSha256("run_fingerprint") == header.runFingerprint)
        val moves = record.requiredStringList("uci_moves")
        val sanMoves = record.requiredStringList("san_moves")
        val fenTimeline = record.requiredStringList("fen_timeline")
        require(moves.isNotEmpty()) { "Completed game has no moves in $report" }
        require(sanMoves.size == moves.size) { "SAN/UCI length mismatch in $report" }
        require(fenTimeline.size == moves.size + 1) { "FEN timeline length mismatch in $report" }
        require(record.requiredString("final_fen") == fenTimeline.last())
        val openingPlies = record.requiredInt("opening_plies")
        require(openingPlies in 0 until moves.size)
        val endReason = enumValueOf<EndReason>(record.requiredString("end_reason"))
        val winner = enumValueOf<Side>(record.requiredString("winner"))
        val loser = enumValueOf<Side>(record.requiredString("loser"))
        require(loser == winner.opposite())
        val initialFen = record.requiredString("initial_fen")
        require(initialFen == fenTimeline.first())

        fun candidate(
            kind: PuzzleCandidateKind,
            ply: Int,
            scoreMate: Int?,
        ): PuzzleCandidate {
            require(ply in 1..moves.size)
            val movesBefore = moves.take(ply - 1)
            val candidateFen = fenTimeline[ply - 1]
            val solutionMove = moves[ply - 1]
            val candidateId = candidateId(
                kind = kind,
                rules = header.rules,
                initialFen = initialFen,
                movesBefore = movesBefore,
                candidateFen = candidateFen,
                solutionMove = solutionMove,
            )
            return PuzzleCandidate(
                candidateId = candidateId,
                kind = kind,
                rules = header.rules,
                initialFen = initialFen,
                movesBefore = movesBefore,
                candidateFen = candidateFen,
                solutionMove = solutionMove,
                solutionSan = sanMoves[ply - 1],
                expectedEndReason = endReason.takeIf { kind == PuzzleCandidateKind.TERMINAL_MOVE },
                expectedWinner = winner.takeIf { kind == PuzzleCandidateKind.TERMINAL_MOVE },
                expectedLoser = loser.takeIf { kind == PuzzleCandidateKind.TERMINAL_MOVE },
                sourceEndReason = endReason,
                sourceWinner = winner,
                sourceLoser = loser,
                sourceReport = report.toString(),
                sourceReportSha256 = reportHash,
                sourceRunFingerprint = header.runFingerprint,
                sourceJobId = record.requiredString("job_id"),
                sourceEngineSha256 = header.engineSha256,
                sourceVariantsSha256 = header.variantsSha256,
                sourceRuntimeSha256 = header.runtimeSha256,
                sourcePly = ply,
                sourceOpeningPlies = openingPlies,
                sourceScoreMate = scoreMate,
            )
        }

        val result = mutableListOf(candidate(PuzzleCandidateKind.TERMINAL_MOVE, moves.size, null))
        val firstPositiveMate = record.requiredList("searches")
            .map { it.requiredObjectValue("searches") }
            .firstOrNull { search ->
                search.optionalString("score_type") == "mate" &&
                    (search.optionalInt("score_value") ?: Int.MIN_VALUE) > 0
            }
        if (firstPositiveMate != null) {
            val ply = firstPositiveMate.requiredInt("ply")
            if (ply > openingPlies && ply < moves.size) {
                result += candidate(
                    PuzzleCandidateKind.FORCED_MATE,
                    ply,
                    firstPositiveMate.requiredInt("score_value"),
                )
            }
        }
        return result
    }

    private fun discoverReports(inputs: List<Path>): List<Path> = inputs.flatMap { input ->
        val normalized = input.toAbsolutePath().normalize()
        when {
            Files.isRegularFile(normalized) -> listOf(normalized)
            Files.isDirectory(normalized) -> Files.walk(normalized).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().startsWith("round-") &&
                        path.fileName.toString().endsWith(".jsonl")
                }.toList()
            }
            else -> error("Puzzle mining input does not exist: $normalized")
        }
    }.distinct().sortedBy(Path::toString)
}

data class PuzzleCandidateFile(
    val path: Path,
    val sha256: String,
    val candidates: List<PuzzleCandidate>,
)

object PuzzleCandidateReader {
    fun read(path: Path): PuzzleCandidateFile {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(normalized)) { "Candidate input is not a file: $normalized" }
        val candidates = mutableListOf<PuzzleCandidate>()
        var expectedCount: Int? = null
        Files.newBufferedReader(normalized, StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, encoded ->
                require(encoded.isNotBlank()) { "Blank candidate record at line ${index + 1}" }
                val record = Json.decodeObject(encoded)
                if (index == 0) {
                    require(record.requiredString("event") == "puzzle_candidate_header")
                    require(record.requiredInt("schema_version") == PUZZLE_CANDIDATE_SCHEMA_VERSION)
                    Instant.parse(record.requiredString("created_at"))
                    expectedCount = record.requiredInt("candidate_count")
                } else {
                    candidates += PuzzleCandidate.fromMap(record)
                }
            }
        }
        require(expectedCount != null) { "Candidate file is empty: $normalized" }
        require(candidates.size == expectedCount) {
            "Candidate header declares $expectedCount records but found ${candidates.size}"
        }
        require(candidates.map(PuzzleCandidate::candidateId).toSet().size == candidates.size) {
            "Candidate file contains duplicate candidate IDs"
        }
        require(candidates == candidates.sortedBy(PuzzleCandidate::candidateId)) {
            "Candidate records are not in canonical candidate-ID order"
        }
        return PuzzleCandidateFile(normalized, sha256(normalized), candidates)
    }
}

private data class ReportHeader(
    val runFingerprint: String,
    val engineSha256: String,
    val variantsSha256: String,
    val runtimeSha256: String,
    val rules: PuzzleRules,
) {
    companion object {
        fun fromRecord(record: Map<String, Any?>): ReportHeader {
            require(record.requiredInt("report_schema_version") == REPORT_SCHEMA_VERSION)
            val config = record.requiredObject("config").mapValues { (_, value) ->
                value as? String ?: error("Report config values must be strings")
            }
            return ReportHeader(
                runFingerprint = record.requiredSha256("run_fingerprint"),
                engineSha256 = record.requiredSha256("engine_sha256"),
                variantsSha256 = record.requiredSha256("variants_sha256"),
                runtimeSha256 = record.requiredSha256("runtime_sha256"),
                rules = PuzzleRules.fromReportConfig(config),
            )
        }
    }
}

internal fun candidateId(
    kind: PuzzleCandidateKind,
    rules: PuzzleRules,
    initialFen: String,
    movesBefore: List<String>,
    candidateFen: String,
    solutionMove: String,
): String = sha256Text(
    buildString {
        append(PUZZLE_CANDIDATE_SCHEMA_VERSION).append('\u0000')
        append(kind.name).append('\u0000')
        append(rules.preset.name).append('\u0000')
        append(rules.deadPosition.name).append('\u0000')
        append(rules.fiftyMove.name).append('\u0000')
        append(initialFen).append('\u0000')
        movesBefore.forEach { append(it).append('\u0000') }
        append(candidateFen).append('\u0000')
        append(solutionMove).append('\u0000')
    },
)

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.hex()
}

internal fun sha256Text(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}

private fun moveAtomically(source: Path, destination: Path, replace: Boolean) {
    val options = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replace) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(source, destination, *options)
    } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
        val fallback = if (replace) {
            arrayOf(StandardCopyOption.REPLACE_EXISTING)
        } else {
            emptyArray()
        }
        Files.move(source, destination, *fallback)
    }
}

internal fun Map<String, Any?>.requiredString(name: String): String =
    (this[name] as? String)?.takeIf(String::isNotBlank)
        ?: error("Field '$name' must be a non-empty string")

internal fun Map<String, Any?>.optionalString(name: String): String? = when (val value = this[name]) {
    null -> null
    is String -> value
    else -> error("Field '$name' must be a string or null")
}

internal fun Map<String, Any?>.requiredBoolean(name: String): Boolean =
    this[name] as? Boolean ?: error("Field '$name' must be a boolean")

internal fun Map<String, Any?>.requiredInt(name: String): Int {
    val value = this[name] as? Number ?: error("Field '$name' must be an integer")
    val long = value.toLong()
    require(value.toDouble() == long.toDouble() && long in Int.MIN_VALUE..Int.MAX_VALUE) {
        "Field '$name' must be a 32-bit integer"
    }
    return long.toInt()
}

internal fun Map<String, Any?>.optionalInt(name: String): Int? = when (this[name]) {
    null -> null
    else -> requiredInt(name)
}

internal fun Map<String, Any?>.requiredSha256(name: String): String = requiredString(name).also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { "Field '$name' must be a lowercase SHA-256" }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.requiredObject(name: String): Map<String, Any?> =
    this[name] as? Map<String, Any?> ?: error("Field '$name' must be an object")

internal fun Any?.requiredObjectValue(context: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?> ?: error("$context entry must be an object")
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.requiredList(name: String): List<Any?> =
    this[name] as? List<Any?> ?: error("Field '$name' must be an array")

internal fun Map<String, Any?>.requiredStringList(name: String): List<String> =
    requiredList(name).mapIndexed { index, value ->
        value as? String ?: error("Field '$name' entry $index must be a string")
    }

internal inline fun <reified T : Enum<T>> Map<String, Any?>.optionalEnum(name: String): T? =
    optionalString(name)?.let(::enumValueOf)
