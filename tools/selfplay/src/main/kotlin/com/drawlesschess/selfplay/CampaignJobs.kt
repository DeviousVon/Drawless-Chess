package com.drawlesschess.selfplay

import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.engine.BotDifficultyCatalog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class OpeningFixture(
    val id: String,
    val name: String,
    val initialFen: String,
    val moves: List<UciMove>,
)

internal data class LadderFixture(
    val ordinal: Int,
    val id: String,
    val elo: Int,
)

internal data class AdjacentFixture(
    val id: String,
    val lower: LadderFixture,
    val higher: LadderFixture,
    val alternateLegOrder: Boolean,
)

internal object CampaignJobFactory {
    fun sameLevel(config: SelfPlayConfig): List<SelfPlayJob> {
        val openings = readOpenings(checkNotNull(config.openingsPath))
        val levels = readLadder(checkNotNull(config.ladderLevelsPath))
        val jobs = buildList {
            levels.forEach { level ->
                openings.forEach { opening ->
                    val matchupId = "same-${level.id}"
                    add(
                        SelfPlayJob(
                            jobId = "${config.runLabel}-$matchupId-${opening.id}",
                            pairId = null,
                            pairLeg = null,
                            openingId = opening.id,
                            openingName = opening.name,
                            matchupId = matchupId,
                            whiteLevelId = level.id,
                            blackLevelId = level.id,
                            initialFen = opening.initialFen,
                            openingMoves = opening.moves,
                            whiteCompetitor = level.id,
                            blackCompetitor = level.id,
                            whiteStrength = UciStrength.Elo(level.elo),
                            blackStrength = UciStrength.Elo(level.elo),
                        ),
                    )
                }
            }
        }
        require(jobs.size == SAME_LEVEL_GAMES && config.games == jobs.size) {
            "same-level campaign derives $SAME_LEVEL_GAMES games; config games=${config.games}"
        }
        return jobs
    }

    fun adjacent(config: SelfPlayConfig): List<SelfPlayJob> {
        val openings = readOpenings(checkNotNull(config.openingsPath))
        val levels = readLadder(checkNotNull(config.ladderLevelsPath))
        val matchups = readAdjacent(checkNotNull(config.adjacentMatchupsPath), levels)
        val jobs = buildList {
            matchups.forEachIndexed { matchupIndex, matchup ->
                openings.forEachIndexed { openingIndex, opening ->
                    val pairId = "${config.runLabel}-${matchup.id}-${opening.id}"
                    val lowerWhite = adjacentJob(
                        config = config,
                        opening = opening,
                        matchup = matchup,
                        pairId = pairId,
                        lowerIsWhite = true,
                    )
                    val higherWhite = adjacentJob(
                        config = config,
                        opening = opening,
                        matchup = matchup,
                        pairId = pairId,
                        lowerIsWhite = false,
                    )
                    val reverse = matchup.alternateLegOrder &&
                        (matchupIndex * openings.size + openingIndex) % 2 == 1
                    if (reverse) {
                        add(higherWhite)
                        add(lowerWhite)
                    } else {
                        add(lowerWhite)
                        add(higherWhite)
                    }
                }
            }
        }
        require(jobs.size == ADJACENT_GAMES && config.games == jobs.size) {
            "adjacent campaign derives $ADJACENT_GAMES games; config games=${config.games}"
        }
        return jobs
    }

    /**
     * Release stability portfolio. Each pass covers every shipped level symmetrically and every
     * adjacent level in both color directions for all curated opening, endgame, and rule-edge
     * positions. Repeated passes are stability samples, not independent strength samples.
     */
    fun releaseCampaign(config: SelfPlayConfig): List<SelfPlayJob> {
        val positions = readReleasePositions(config, checkNotNull(config.releasePositionsPath))
        val levels = readLadder(checkNotNull(config.ladderLevelsPath))
        val matchups = readAdjacent(checkNotNull(config.adjacentMatchupsPath), levels)
        val gamesPerPosition = levels.size + matchups.size * 2
        val gamesPerPass = positions.size * gamesPerPosition
        require(config.games >= gamesPerPass && config.games % gamesPerPass == 0) {
            "release campaign requires one or more complete $gamesPerPass-game portfolio passes; " +
                "config games=${config.games}"
        }
        val passes = config.games / gamesPerPass
        val jobs = buildList(config.games) {
            repeat(passes) { passIndex ->
                positions.forEachIndexed { positionIndex, position ->
                    levels.forEach { level ->
                        val matchupId = "same-${level.id}"
                        add(
                            SelfPlayJob(
                                jobId = releaseJobId(config, passIndex, position, matchupId),
                                pairId = null,
                                pairLeg = null,
                                openingId = position.id,
                                openingName = position.name,
                                matchupId = matchupId,
                                whiteLevelId = level.id,
                                blackLevelId = level.id,
                                initialFen = position.initialFen,
                                openingMoves = position.moves,
                                whiteCompetitor = level.id,
                                blackCompetitor = level.id,
                                whiteStrength = UciStrength.Elo(level.elo),
                                blackStrength = UciStrength.Elo(level.elo),
                            ),
                        )
                    }
                    matchups.forEachIndexed { matchupIndex, matchup ->
                        val pairId = releaseJobId(config, passIndex, position, matchup.id)
                        val lowerWhite = adjacentJob(
                            config = config,
                            opening = position,
                            matchup = matchup,
                            pairId = pairId,
                            lowerIsWhite = true,
                        )
                        val higherWhite = adjacentJob(
                            config = config,
                            opening = position,
                            matchup = matchup,
                            pairId = pairId,
                            lowerIsWhite = false,
                        )
                        val reverse = matchup.alternateLegOrder &&
                            (passIndex * positions.size * matchups.size +
                                positionIndex * matchups.size + matchupIndex) % 2 == 1
                        if (reverse) {
                            add(higherWhite)
                            add(lowerWhite)
                        } else {
                            add(lowerWhite)
                            add(higherWhite)
                        }
                    }
                }
            }
        }
        require(jobs.size == config.games) {
            "release campaign derived ${jobs.size} games; config games=${config.games}"
        }
        return jobs
    }

    private fun releaseJobId(
        config: SelfPlayConfig,
        passIndex: Int,
        position: OpeningFixture,
        matchupId: String,
    ): String = "${config.runLabel}-pass-${(passIndex + 1).toString().padStart(2, '0')}-" +
        "${position.id}-$matchupId"

    private fun adjacentJob(
        config: SelfPlayConfig,
        opening: OpeningFixture,
        matchup: AdjacentFixture,
        pairId: String,
        lowerIsWhite: Boolean,
    ): SelfPlayJob {
        val white = if (lowerIsWhite) matchup.lower else matchup.higher
        val black = if (lowerIsWhite) matchup.higher else matchup.lower
        val leg = if (lowerIsWhite) "lower-white" else "higher-white"
        return SelfPlayJob(
            jobId = "$pairId-$leg",
            pairId = pairId,
            pairLeg = leg,
            openingId = opening.id,
            openingName = opening.name,
            matchupId = matchup.id,
            whiteLevelId = white.id,
            blackLevelId = black.id,
            initialFen = opening.initialFen,
            openingMoves = opening.moves,
            whiteCompetitor = white.id,
            blackCompetitor = black.id,
            whiteStrength = UciStrength.Elo(white.elo),
            blackStrength = UciStrength.Elo(black.elo),
        )
    }

    private fun readOpenings(path: Path): List<OpeningFixture> {
        val rows = readTsv(path, OPENING_HEADER)
        require(rows.size == OPENING_COUNT) {
            "$path must contain exactly $OPENING_COUNT opening rows"
        }
        val ids = linkedSetOf<String>()
        return rows.mapIndexed { index, fields ->
            require(fields[0] == TSV_SCHEMA) { "$path row ${index + 2} has unsupported schema" }
            val id = identifier(fields[1], "$path row ${index + 2} opening_id")
            require(ids.add(id)) { "$path contains duplicate opening_id '$id'" }
            val name = fields[2].trim().also {
                require(it.isNotEmpty()) { "$path row ${index + 2} has an empty name" }
            }
            var position = ChessPosition.fromFen(fields[3])
            val initialFen = position.fen()
            val moves = if (fields[4] == "-") {
                emptyList()
            } else {
                fields[4].trim().split(Regex("\\s+")).map(::UciMove)
            }
            moves.forEachIndexed { moveIndex, move ->
                try {
                    ChessAdapter.transition(position, move)
                    position = ChessRules.apply(position, move)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "$path opening '$id' has illegal move ${move.value} at prefix ply ${moveIndex + 1}",
                        error,
                    )
                }
            }
            OpeningFixture(id, name, initialFen, moves)
        }
    }

    private fun readReleasePositions(config: SelfPlayConfig, path: Path): List<OpeningFixture> {
        val rows = readTsv(path, RELEASE_POSITION_HEADER)
        require(rows.size == RELEASE_POSITION_COUNT) {
            "$path must contain exactly $RELEASE_POSITION_COUNT release position rows"
        }
        val ids = linkedSetOf<String>()
        val categories = linkedMapOf("opening" to 0, "endgame" to 0, "edge" to 0)
        val positions = rows.mapIndexed { index, fields ->
            require(fields[0] == TSV_SCHEMA) { "$path row ${index + 2} has unsupported schema" }
            val id = identifier(fields[1], "$path row ${index + 2} position_id")
            require(ids.add(id)) { "$path contains duplicate position_id '$id'" }
            val category = fields[2]
            require(category in categories) {
                "$path row ${index + 2} category must be opening, endgame, or edge"
            }
            require(id.startsWith("$category-")) {
                "$path position_id '$id' must begin with '$category-'"
            }
            categories[category] = checkNotNull(categories[category]) + 1
            val name = fields[3].trim().also {
                require(it.isNotEmpty()) { "$path row ${index + 2} has an empty name" }
            }
            var position = ChessPosition.fromFen(fields[4])
            val initialFen = position.fen()
            val moves = if (fields[5] == "-") {
                emptyList()
            } else {
                fields[5].trim().split(Regex("\\s+")).map(::UciMove)
            }
            moves.forEachIndexed { moveIndex, move ->
                try {
                    ChessAdapter.transition(position, move)
                    position = ChessRules.apply(position, move)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "$path position '$id' has illegal move ${move.value} at prefix ply ${moveIndex + 1}",
                        error,
                    )
                }
            }
            val fixture = OpeningFixture(id, name, initialFen, moves)
            val witnessText = fields[6]
            val expectedReason = fields[7]
            if (category == "edge") {
                require(witnessText != "-" && expectedReason in RELEASE_EDGE_REASONS) {
                    "$path edge position '$id' needs a witness move and supported expected reason"
                }
                val witness = UciMove(witnessText)
                val job = SelfPlayJob(
                    jobId = "release-witness-$id",
                    pairId = null,
                    pairLeg = null,
                    openingId = id,
                    openingName = name,
                    matchupId = "witness",
                    whiteLevelId = null,
                    blackLevelId = null,
                    initialFen = initialFen,
                    openingMoves = moves,
                    whiteCompetitor = "witness",
                    blackCompetitor = "witness",
                    whiteStrength = UciStrength.Skill(0),
                    blackStrength = UciStrength.Skill(0),
                )
                val replayed = replayOpening(config, job)
                val witnessed = replayed.session.apply(
                    ChessAdapter.transition(replayed.position, witness),
                )
                require(witnessed.outcome?.reason?.name == expectedReason) {
                    "$path edge position '$id' witness $witnessText produced " +
                        "${witnessed.outcome?.reason?.name ?: "no outcome"}; expected $expectedReason"
                }
            } else {
                require(witnessText == "-" && expectedReason == "-") {
                    "$path non-edge position '$id' must not declare an edge witness"
                }
            }
            fixture
        }
        require(categories == RELEASE_CATEGORY_COUNTS) {
            "$path category counts $categories do not match $RELEASE_CATEGORY_COUNTS"
        }
        return positions
    }

    private fun readLadder(path: Path): List<LadderFixture> {
        val rows = readTsv(path, LADDER_HEADER)
        val expected = BotDifficultyCatalog.namedLevels
        require(rows.size == expected.size && rows.size == LADDER_COUNT) {
            "$path must contain exactly $LADDER_COUNT ladder rows"
        }
        val ids = linkedSetOf<String>()
        return rows.mapIndexed { index, fields ->
            require(fields[0] == TSV_SCHEMA) { "$path row ${index + 2} has unsupported schema" }
            val ordinal = fields[1].toIntOrNull()
                ?: error("$path row ${index + 2} ordinal is not an integer")
            require(ordinal == index) { "$path ladder ordinals must be contiguous from zero" }
            val id = identifier(fields[2], "$path row ${index + 2} level_id")
            require(ids.add(id)) { "$path contains duplicate level_id '$id'" }
            val elo = fields[3].toIntOrNull()
                ?: error("$path row ${index + 2} elo is not an integer")
            require(elo in 500..2850) { "$path row ${index + 2} elo is outside 500..2850" }
            require(id == expected[index].id && elo == expected[index].approximateElo) {
                "$path row ${index + 2} does not match the app difficulty catalog"
            }
            LadderFixture(ordinal, id, elo)
        }
    }

    private fun readAdjacent(path: Path, levels: List<LadderFixture>): List<AdjacentFixture> {
        val rows = readTsv(path, ADJACENT_HEADER)
        require(rows.size == ADJACENT_COUNT) {
            "$path must contain exactly $ADJACENT_COUNT adjacent matchup rows"
        }
        val ids = linkedSetOf<String>()
        return rows.mapIndexed { index, fields ->
            require(fields[0] == TSV_SCHEMA) { "$path row ${index + 2} has unsupported schema" }
            val id = identifier(fields[1], "$path row ${index + 2} matchup_id")
            require(ids.add(id)) { "$path contains duplicate matchup_id '$id'" }
            val lower = levels[index]
            val higher = levels[index + 1]
            require(fields[2] == lower.id && fields[3].toIntOrNull() == lower.elo) {
                "$path matchup '$id' lower level does not match adjacent ladder row $index"
            }
            require(fields[4] == higher.id && fields[5].toIntOrNull() == higher.elo) {
                "$path matchup '$id' higher level does not match adjacent ladder row ${index + 1}"
            }
            require(fields[6] == "2") { "$path matchup '$id' must use exactly two legs" }
            require(fields[7] == "true") { "$path matchup '$id' must pair colors" }
            val alternate = fields[8].toBooleanStrictOrNull()
                ?: error("$path matchup '$id' alternate_leg_order must be true or false")
            require(alternate) { "$path matchup '$id' must alternate leg order" }
            AdjacentFixture(id, lower, higher, alternate)
        }
    }

    private fun readTsv(path: Path, expectedHeader: List<String>): List<List<String>> {
        require(Files.isRegularFile(path)) { "TSV fixture is not a file: $path" }
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        require(lines.isNotEmpty()) { "TSV fixture is empty: $path" }
        require(lines.first().split('\t') == expectedHeader) {
            "$path header must be exactly: ${expectedHeader.joinToString("\\t")}"
        }
        require(lines.drop(1).none(String::isBlank)) { "$path contains a blank data row" }
        return lines.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == expectedHeader.size) {
                "$path row ${index + 2} has ${fields.size} fields; expected ${expectedHeader.size}"
            }
            require(fields.none { it != it.trim() }) {
                "$path row ${index + 2} has leading or trailing whitespace"
            }
            fields
        }
    }

    private fun identifier(value: String, description: String): String = value.also {
        require(it.matches(ID_PATTERN)) { "$description is not a stable identifier: '$it'" }
    }

    private const val TSV_SCHEMA = "1"
    private const val OPENING_COUNT = 8
    private const val LADDER_COUNT = 7
    private const val ADJACENT_COUNT = 6
    private const val RELEASE_POSITION_COUNT = 48
    private const val SAME_LEVEL_GAMES = LADDER_COUNT * OPENING_COUNT
    private const val ADJACENT_GAMES = ADJACENT_COUNT * OPENING_COUNT * 2
    private val ID_PATTERN = Regex("[a-z][a-z0-9-]{0,63}")
    private val OPENING_HEADER = listOf(
        "schema_version", "opening_id", "name", "initial_fen", "moves",
    )
    private val LADDER_HEADER = listOf("schema_version", "ordinal", "level_id", "elo")
    private val ADJACENT_HEADER = listOf(
        "schema_version",
        "matchup_id",
        "lower_level",
        "lower_elo",
        "higher_level",
        "higher_elo",
        "legs_per_opening",
        "pair_colors",
        "alternate_leg_order",
    )
    private val RELEASE_POSITION_HEADER = listOf(
        "schema_version",
        "position_id",
        "category",
        "name",
        "initial_fen",
        "moves",
        "witness_move",
        "expected_reason",
    )
    private val RELEASE_CATEGORY_COUNTS = linkedMapOf(
        "opening" to 24,
        "endgame" to 12,
        "edge" to 12,
    )
    private val RELEASE_EDGE_REASONS = setOf(
        "CHECKMATE",
        "STALEMATE",
        "REPETITION",
        "DEAD_POSITION_MATERIAL",
        "FIFTY_MOVE_LIMIT",
        "BARE_KING",
    )
}
