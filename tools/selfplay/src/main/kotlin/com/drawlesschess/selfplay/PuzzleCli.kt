package com.drawlesschess.selfplay

import java.nio.file.Path

internal fun runPuzzleCli(arguments: Array<String>): Boolean {
    when (arguments.firstOrNull()) {
        "--mine-puzzles" -> {
            val parsed = parseOptions(arguments.drop(1), repeatable = setOf("--input"))
            val inputs = parsed.values("--input").map(Path::of)
            val output = Path.of(parsed.single("--output"))
            parsed.requireOnly("--input", "--output", "--replace")
            val summary = PuzzleMiner.mine(inputs, output, replace = parsed.flag("--replace"))
            println(
                Json.encode(
                    linkedMapOf(
                        "event" to "puzzle_mining_completed",
                        "reports" to summary.reports,
                        "games" to summary.games,
                        "censored_games" to summary.censoredGames,
                        "terminal_candidates" to summary.terminalCandidates,
                        "forced_mate_candidates" to summary.forcedMateCandidates,
                        "unique_candidates" to summary.uniqueCandidates,
                        "duplicate_candidates" to summary.duplicateCandidates,
                        "output" to summary.output.toString(),
                    ),
                ),
            )
            return true
        }
        "--verify-puzzles" -> {
            val parsed = parseOptions(arguments.drop(1))
            parsed.requireOnly(
                "--input",
                "--output",
                "--engine",
                "--variants",
                "--primary-nodes",
                "--confirm-nodes",
                "--multi-pv",
                "--parallel",
                "--hash-mb",
                "--min-advantage-cp",
                "--min-gap-cp",
                "--max-candidates",
                "--replace",
            )
            val options = PuzzleVerificationOptions(
                candidateInput = Path.of(parsed.single("--input")),
                output = Path.of(parsed.single("--output")),
                enginePath = Path.of(parsed.single("--engine")),
                variantsPath = Path.of(parsed.single("--variants")),
                primaryNodes = parsed.optionalLong("--primary-nodes", 250_000L),
                confirmNodes = parsed.optionalLong("--confirm-nodes", 1_000_000L),
                multiPv = parsed.optionalInt("--multi-pv", 5),
                parallelism = parsed.optionalInt("--parallel", 4),
                hashMb = parsed.optionalInt("--hash-mb", 64),
                minAdvantageCp = parsed.optionalInt("--min-advantage-cp", 150),
                minGapCp = parsed.optionalInt("--min-gap-cp", 120),
                maxCandidates = parsed.optional("--max-candidates")?.toIntOrNull()
                    ?: parsed.optional("--max-candidates")?.let { error("--max-candidates must be an integer") },
                replace = parsed.flag("--replace"),
            )
            val summary = PuzzleVerifier.verify(options)
            println(
                Json.encode(
                    linkedMapOf(
                        "event" to "puzzle_verification_completed",
                        "scheduled" to summary.scheduled,
                        "resumed" to summary.resumed,
                        "verified" to summary.verified,
                        "rejected" to summary.rejected,
                        "errors" to summary.errors,
                        "output" to summary.output.toString(),
                    ),
                ),
            )
            return true
        }
        "--puzzle-help" -> {
            println(PUZZLE_USAGE.trimIndent())
            return true
        }
        else -> return false
    }
}

private data class ParsedOptions(
    private val options: Map<String, List<String?>>,
) {
    fun values(name: String): List<String> = options[name].orEmpty().map { value ->
        value ?: error("$name requires a value")
    }

    fun single(name: String): String {
        val values = values(name)
        require(values.size == 1) { "$name must be provided exactly once" }
        return values.single()
    }

    fun optional(name: String): String? {
        val values = values(name)
        require(values.size <= 1) { "$name must not be repeated" }
        return values.singleOrNull()
    }

    fun flag(name: String): Boolean {
        val values = options[name].orEmpty()
        require(values.size <= 1 && values.all { it == null }) { "$name is a flag and must not be repeated" }
        return values.isNotEmpty()
    }

    fun optionalInt(name: String, default: Int): Int = optional(name)?.toIntOrNull()
        ?: optional(name)?.let { error("$name must be an integer") }
        ?: default

    fun optionalLong(name: String, default: Long): Long = optional(name)?.toLongOrNull()
        ?: optional(name)?.let { error("$name must be an integer") }
        ?: default

    fun requireOnly(vararg known: String) {
        val unknown = options.keys - known.toSet()
        require(unknown.isEmpty()) { "Unknown puzzle option(s): ${unknown.sorted().joinToString()}" }
    }
}

private fun parseOptions(arguments: List<String>, repeatable: Set<String> = emptySet()): ParsedOptions {
    val parsed = linkedMapOf<String, MutableList<String?>>()
    var index = 0
    while (index < arguments.size) {
        val name = arguments[index]
        require(name.startsWith("--")) { "Expected an option, found '$name'" }
        val isFlag = name == "--replace"
        val value = if (isFlag) {
            null
        } else {
            require(index + 1 < arguments.size && !arguments[index + 1].startsWith("--")) {
                "$name requires a value"
            }
            arguments[++index]
        }
        val values = parsed.getOrPut(name) { mutableListOf() }
        require(name in repeatable || values.isEmpty()) { "$name must not be repeated" }
        values += value
        index++
    }
    return ParsedOptions(parsed)
}

private const val PUZZLE_USAGE = """
Puzzle candidate mining:
  java -jar drawless-selfplay.jar --mine-puzzles \
    --input REPORT_OR_RUN_DIRECTORY [--input ...] --output CANDIDATES.jsonl [--replace]

Independent puzzle verification:
  java -jar drawless-selfplay.jar --verify-puzzles \
    --input CANDIDATES.jsonl --output VERIFIED.jsonl \
    --engine DRAWLESS_ENGINE --variants variants.ini \
    [--primary-nodes 250000] [--confirm-nodes 1000000] [--multi-pv 5] \
    [--parallel 4] [--hash-mb 64] [--min-advantage-cp 150] [--min-gap-cp 120] \
    [--max-candidates N] [--replace]
"""
