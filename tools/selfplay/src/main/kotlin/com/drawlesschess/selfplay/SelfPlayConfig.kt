package com.drawlesschess.selfplay

import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessPosition
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

sealed interface SearchLimit {
    val uciCommand: String
    val label: String

    data class MoveTime(val millis: Long) : SearchLimit {
        init { require(millis in 1L..60_000L) }
        override val uciCommand: String = "go movetime $millis"
        override val label: String = "movetime:$millis"
    }

    data class Nodes(val count: Long) : SearchLimit {
        init { require(count in 1L..10_000_000_000L) }
        override val uciCommand: String = "go nodes $count"
        override val label: String = "nodes:$count"
    }
}

sealed interface UciStrength {
    val label: String

    data class Elo(val value: Int) : UciStrength {
        init { require(value in 500..2850) }
        override val label: String = "elo:$value"
    }

    data class Skill(val value: Int) : UciStrength {
        init { require(value in -20..20) }
        override val label: String = "skill:$value"
    }
}

enum class JobSource {
    SINGLE,
    SAME_LEVEL,
    ADJACENT,
}

data class SelfPlayConfig(
    val sourcePath: Path,
    val runLabel: String,
    val enginePath: Path,
    val variantsPath: Path,
    val outputPath: Path,
    val jobSource: JobSource,
    val openingsPath: Path?,
    val ladderLevelsPath: Path?,
    val adjacentMatchupsPath: Path?,
    val games: Int,
    val parallelGames: Int,
    val initialFen: String,
    val openingMoves: List<UciMove>,
    val variant: RulesContractV1.Preset,
    val deadPosition: DeadPositionPolicy,
    val fiftyMove: FiftyMovePolicy,
    val whiteStrength: UciStrength?,
    val blackStrength: UciStrength?,
    val searchLimit: SearchLimit,
    val maxPlies: Int,
    val pairColors: Boolean,
    val failFast: Boolean,
    val markCappedForContinuation: Boolean,
    val hashMb: Int,
    val handshakeTimeoutMillis: Long,
    val readyTimeoutMillis: Long,
    val searchTimeoutMillis: Long,
    val stopGraceMillis: Long,
    val quitTimeoutMillis: Long,
) {
    val rules: RulesContractV1 = when (variant) {
        RulesContractV1.Preset.DRAWLESS -> RulesContractV1.drawless(deadPosition, fiftyMove)
        RulesContractV1.Preset.ESCAPE -> RulesContractV1.escape(deadPosition, fiftyMove)
    }

    fun fingerprintFields(): Map<String, String> = linkedMapOf(
        "schemaVersion" to SCHEMA_VERSION.toString(),
        "runLabel" to runLabel,
        "enginePath" to enginePath.toString(),
        "variantsPath" to variantsPath.toString(),
        "jobSource" to jobSource.name.lowercase().replace('_', '-'),
        "openingsPath" to (openingsPath?.toString() ?: "-"),
        "ladderLevelsPath" to (ladderLevelsPath?.toString() ?: "-"),
        "adjacentMatchupsPath" to (adjacentMatchupsPath?.toString() ?: "-"),
        "games" to games.toString(),
        "parallelGames" to parallelGames.toString(),
        "initialFen" to initialFen,
        "openingMoves" to openingMoves.joinToString(" ") { it.value },
        "variant" to variant.name.lowercase(),
        "deadPosition" to deadPosition.name.lowercase(),
        "fiftyMove" to fiftyMove.name.lowercase(),
        "whiteStrength" to (whiteStrength?.label ?: "matrix"),
        "blackStrength" to (blackStrength?.label ?: "matrix"),
        "searchLimit" to searchLimit.label,
        "maxPlies" to maxPlies.toString(),
        "pairColors" to pairColors.toString(),
        "markCappedForContinuation" to markCappedForContinuation.toString(),
        "hashMb" to hashMb.toString(),
        "handshakeTimeoutMillis" to handshakeTimeoutMillis.toString(),
        "readyTimeoutMillis" to readyTimeoutMillis.toString(),
        "searchTimeoutMillis" to searchTimeoutMillis.toString(),
        "stopGraceMillis" to stopGraceMillis.toString(),
        "quitTimeoutMillis" to quitTimeoutMillis.toString(),
    )

    companion object {
        const val SCHEMA_VERSION = 1

        private val knownKeys = setOf(
            "schemaVersion",
            "runLabel",
            "enginePath",
            "variantsPath",
            "outputPath",
            "jobSource",
            "openingsPath",
            "ladderLevelsPath",
            "adjacentMatchupsPath",
            "games",
            "parallelGames",
            "initialFen",
            "openingMoves",
            "variant",
            "deadPosition",
            "fiftyMove",
            "whiteStrength",
            "blackStrength",
            "searchMode",
            "moveTimeMillis",
            "nodes",
            "maxPlies",
            "pairColors",
            "failFast",
            "markCappedForContinuation",
            "hashMb",
            "handshakeTimeoutMillis",
            "readyTimeoutMillis",
            "searchTimeoutMillis",
            "stopGraceMillis",
            "quitTimeoutMillis",
        )

        fun load(path: Path): SelfPlayConfig {
            val normalizedPath = path.toAbsolutePath().normalize()
            require(Files.isRegularFile(normalizedPath)) { "Config is not a file: $normalizedPath" }
            val properties = Properties().apply {
                Files.newBufferedReader(normalizedPath, StandardCharsets.UTF_8).use(::load)
            }
            val unknown = properties.stringPropertyNames() - knownKeys
            require(unknown.isEmpty()) { "Unknown config properties: ${unknown.sorted().joinToString()}" }

            fun required(name: String): String = properties.getProperty(name)?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: error("Missing required config property '$name'")

            fun optional(name: String, default: String): String =
                properties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty) ?: default

            fun strictBoolean(name: String, default: Boolean? = null): Boolean {
                val text = properties.getProperty(name)?.trim()
                    ?: default?.toString()
                    ?: error("Missing required config property '$name'")
                return text.toBooleanStrictOrNull()
                    ?: error("Config property '$name' must be true or false")
            }

            fun strictInt(name: String, range: IntRange, default: Int? = null): Int {
                val text = properties.getProperty(name)?.trim()
                    ?: default?.toString()
                    ?: error("Missing required config property '$name'")
                return text.toIntOrNull()?.also { value ->
                    require(value in range) { "Config property '$name' must be in $range" }
                } ?: error("Config property '$name' must be an integer")
            }

            fun strictLong(name: String, range: LongRange, default: Long? = null): Long {
                val text = properties.getProperty(name)?.trim()
                    ?: default?.toString()
                    ?: error("Missing required config property '$name'")
                return text.toLongOrNull()?.also { value ->
                    require(value in range) { "Config property '$name' must be in $range" }
                } ?: error("Config property '$name' must be an integer")
            }

            val schema = strictInt("schemaVersion", SCHEMA_VERSION..SCHEMA_VERSION)
            check(schema == SCHEMA_VERSION)
            val jobSource = when (optional("jobSource", "single").lowercase()) {
                "single" -> JobSource.SINGLE
                "same-level" -> JobSource.SAME_LEVEL
                "adjacent" -> JobSource.ADJACENT
                else -> error("jobSource must be single, same-level, or adjacent")
            }
            val games = strictInt("games", 1..100_000)
            val pairColors = strictBoolean("pairColors")
            when (jobSource) {
                JobSource.SINGLE -> require(!pairColors || games % 2 == 0) {
                    "games must be even when pairColors=true"
                }
                JobSource.SAME_LEVEL -> require(!pairColors) {
                    "pairColors must be false for jobSource=same-level"
                }
                JobSource.ADJACENT -> require(pairColors) {
                    "pairColors must be true for jobSource=adjacent"
                }
            }
            val parallel = strictInt("parallelGames", 1..64)
            require(parallel <= games) { "parallelGames cannot exceed games" }

            fun forbid(vararg names: String) {
                val present = names.filter(properties::containsKey)
                require(present.isEmpty()) {
                    "Properties ${present.joinToString()} are forbidden for jobSource=" +
                        jobSource.name.lowercase().replace('_', '-')
                }
            }

            val initialPosition: ChessPosition
            val openingMoves: List<UciMove>
            val whiteStrength: UciStrength?
            val blackStrength: UciStrength?
            val openingsPath: Path?
            val ladderLevelsPath: Path?
            val adjacentMatchupsPath: Path?
            when (jobSource) {
                JobSource.SINGLE -> {
                    forbid("openingsPath", "ladderLevelsPath", "adjacentMatchupsPath")
                    initialPosition = ChessPosition.fromFen(
                        optional("initialFen", ChessPosition.START_FEN),
                    )
                    openingMoves = optional("openingMoves", "")
                        .split(Regex("\\s+"))
                        .filter(String::isNotEmpty)
                        .map(::UciMove)
                    whiteStrength = parseStrength(required("whiteStrength"), "whiteStrength")
                    blackStrength = parseStrength(required("blackStrength"), "blackStrength")
                    openingsPath = null
                    ladderLevelsPath = null
                    adjacentMatchupsPath = null
                }
                JobSource.SAME_LEVEL -> {
                    forbid("initialFen", "openingMoves", "whiteStrength", "blackStrength", "adjacentMatchupsPath")
                    initialPosition = ChessPosition.starting()
                    openingMoves = emptyList()
                    whiteStrength = null
                    blackStrength = null
                    openingsPath = absoluteFile(required("openingsPath"), "openingsPath")
                    ladderLevelsPath = absoluteFile(required("ladderLevelsPath"), "ladderLevelsPath")
                    adjacentMatchupsPath = null
                }
                JobSource.ADJACENT -> {
                    forbid("initialFen", "openingMoves", "whiteStrength", "blackStrength")
                    initialPosition = ChessPosition.starting()
                    openingMoves = emptyList()
                    whiteStrength = null
                    blackStrength = null
                    openingsPath = absoluteFile(required("openingsPath"), "openingsPath")
                    ladderLevelsPath = absoluteFile(required("ladderLevelsPath"), "ladderLevelsPath")
                    adjacentMatchupsPath = absoluteFile(
                        required("adjacentMatchupsPath"),
                        "adjacentMatchupsPath",
                    )
                }
            }

            val variant = when (required("variant").lowercase()) {
                "drawless" -> RulesContractV1.Preset.DRAWLESS
                "escape" -> RulesContractV1.Preset.ESCAPE
                else -> error("variant must be drawless or escape")
            }
            val deadPosition = when (required("deadPosition").lowercase()) {
                "material" -> DeadPositionPolicy.MATERIAL_VICTORY
                "final" -> DeadPositionPolicy.FINAL_CAPTURE_VICTORY
                else -> error("deadPosition must be material or final")
            }
            val fiftyMove = when (required("fiftyMove").lowercase()) {
                "off" -> FiftyMovePolicy.DISABLED
                "completing" -> FiftyMovePolicy.COMPLETING_PLAYER_LOSES
                "forced" -> FiftyMovePolicy.FORCED_MOVE_EXCEPTION
                "material" -> FiftyMovePolicy.MATERIAL_VICTORY
                else -> error("fiftyMove must be off, completing, forced, or material")
            }

            val searchMode = required("searchMode").lowercase()
            val searchLimit = when (searchMode) {
                "movetime" -> {
                    require(!properties.containsKey("nodes")) {
                        "nodes is forbidden when searchMode=movetime"
                    }
                    SearchLimit.MoveTime(strictLong("moveTimeMillis", 1L..60_000L))
                }
                "nodes" -> {
                    require(!properties.containsKey("moveTimeMillis")) {
                        "moveTimeMillis is forbidden when searchMode=nodes"
                    }
                    SearchLimit.Nodes(strictLong("nodes", 1L..10_000_000_000L))
                }
                else -> error("searchMode must be movetime or nodes")
            }
            val searchTimeout = strictLong("searchTimeoutMillis", 100L..600_000L)
            if (searchLimit is SearchLimit.MoveTime) {
                require(searchTimeout > searchLimit.millis) {
                    "searchTimeoutMillis must exceed moveTimeMillis"
                }
            }

            val enginePath = absoluteFile(required("enginePath"), "enginePath", executable = true)
            val variantsPath = absoluteFile(required("variantsPath"), "variantsPath")
            val outputPath = Path.of(required("outputPath")).toAbsolutePath().normalize()

            return SelfPlayConfig(
                sourcePath = normalizedPath,
                runLabel = validateRunLabel(optional("runLabel", "selfplay")),
                enginePath = enginePath,
                variantsPath = variantsPath,
                outputPath = outputPath,
                jobSource = jobSource,
                openingsPath = openingsPath,
                ladderLevelsPath = ladderLevelsPath,
                adjacentMatchupsPath = adjacentMatchupsPath,
                games = games,
                parallelGames = parallel,
                initialFen = initialPosition.fen(),
                openingMoves = openingMoves,
                variant = variant,
                deadPosition = deadPosition,
                fiftyMove = fiftyMove,
                whiteStrength = whiteStrength,
                blackStrength = blackStrength,
                searchLimit = searchLimit,
                maxPlies = strictInt("maxPlies", 1..10_000),
                pairColors = pairColors,
                failFast = strictBoolean("failFast"),
                markCappedForContinuation = strictBoolean(
                    "markCappedForContinuation",
                    true,
                ),
                hashMb = strictInt("hashMb", 1..1024, 16),
                handshakeTimeoutMillis = strictLong(
                    "handshakeTimeoutMillis",
                    100L..300_000L,
                    30_000L,
                ),
                readyTimeoutMillis = strictLong(
                    "readyTimeoutMillis",
                    100L..300_000L,
                    30_000L,
                ),
                searchTimeoutMillis = searchTimeout,
                stopGraceMillis = strictLong("stopGraceMillis", 10L..30_000L, 2_000L),
                quitTimeoutMillis = strictLong("quitTimeoutMillis", 10L..30_000L, 3_000L),
            )
        }

        private fun absoluteFile(text: String, name: String, executable: Boolean = false): Path {
            val path = Path.of(text).toAbsolutePath().normalize()
            require(Files.isRegularFile(path)) { "$name is not a file: $path" }
            if (executable) require(Files.isExecutable(path)) { "$name is not executable: $path" }
            return path
        }

        private fun parseStrength(text: String, name: String): UciStrength {
            val parts = text.lowercase().split(':')
            require(parts.size == 2) { "$name must be elo:500..2850 or skill:-20..20" }
            val value = parts[1].toIntOrNull() ?: error("$name has a non-integer value")
            return when (parts[0]) {
                "elo" -> UciStrength.Elo(value)
                "skill" -> UciStrength.Skill(value)
                else -> error("$name must use elo or skill")
            }
        }

        private fun validateRunLabel(text: String): String = text.also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
                "runLabel must use 1-64 ASCII letters, digits, dot, underscore, or dash"
            }
        }
    }
}
