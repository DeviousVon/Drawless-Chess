package com.drawlesschess.selfplay

import com.drawlesschess.core.GameSession
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.PositionFacts
import com.drawlesschess.core.Side
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.DeadPositionDetector
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.chess.SanNotation
import java.util.concurrent.TimeUnit

data class SelfPlayJob(
    val jobId: String,
    val pairId: String?,
    val pairLeg: String?,
    val openingId: String,
    val openingName: String,
    val matchupId: String?,
    val whiteLevelId: String?,
    val blackLevelId: String?,
    val initialFen: String,
    val openingMoves: List<UciMove>,
    val whiteCompetitor: String,
    val blackCompetitor: String,
    val whiteStrength: UciStrength,
    val blackStrength: UciStrength,
)

data class SearchTrace(
    val ply: Int,
    val side: Side,
    val competitor: String,
    val strength: String,
    val elapsedMillis: Long,
    val depth: Int?,
    val nodes: Long?,
    val scoreType: String?,
    val scoreValue: Int?,
    val ponder: String?,
)

data class SelfPlayGameResult(
    val job: SelfPlayJob,
    val startedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val engineWhiteName: String?,
    val engineBlackName: String?,
    val openingPlies: Int,
    val uciMoves: List<String>,
    val sanMoves: List<String>,
    /** Includes the initial full six-field FEN at index zero and every resulting FEN. */
    val fenTimeline: List<String>,
    val searches: List<SearchTrace>,
    val session: GameSession,
    val censored: Boolean,
    val continuationRecommended: Boolean,
)

internal data class ReplayedOpening(
    val position: ChessPosition,
    val session: GameSession,
    val uciMoves: List<String>,
    val sanMoves: List<String>,
    val fenTimeline: List<String>,
)

/** Replays an opening through the same rules path used immediately before engine search. */
internal fun replayOpening(config: SelfPlayConfig, job: SelfPlayJob): ReplayedOpening {
    require(job.openingMoves.size < config.maxPlies) {
        "Opening for ${job.jobId} has ${job.openingMoves.size} plies; " +
            "maxPlies=${config.maxPlies} must leave room for an engine move"
    }
    var position = ChessPosition.fromFen(job.initialFen)
    require(
        ChessRules.legalMoves(position).isNotEmpty() &&
            !DeadPositionDetector.isKnownDead(position) &&
            (config.fiftyMove == FiftyMovePolicy.DISABLED || position.halfmoveClock < 100),
    ) { "Opening for ${job.jobId} starts from a terminal position" }
    var session = GameSession.newGame(
        gameId = job.jobId,
        rules = config.rules,
        initialPositionKey = RepetitionKey.of(position),
        sideToMove = position.sideToMove,
    )
    val uciMoves = mutableListOf<String>()
    val sanMoves = mutableListOf<String>()
    val fenTimeline = mutableListOf(position.fen())

    job.openingMoves.forEachIndexed { index, move ->
        check(session.outcome == null) {
            "Opening for ${job.jobId} ended at ply $index before the configured prefix completed"
        }
        try {
            val san = SanNotation.format(position, move)
            val transition = ChessAdapter.transition(position, move)
            session = session.apply(transition)
            position = ChessRules.apply(position, move)
            uciMoves += move.value
            sanMoves += san
            fenTimeline += position.fen()
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Opening for ${job.jobId} has illegal move ${move.value} at prefix ply ${index + 1}",
                error,
            )
        }
    }
    check(session.outcome == null) { "Opening for ${job.jobId} is already terminal" }
    return ReplayedOpening(
        position = position,
        session = session,
        uciMoves = uciMoves,
        sanMoves = sanMoves,
        fenTimeline = fenTimeline,
    )
}

object SelfPlayJobs {
    fun create(config: SelfPlayConfig): List<SelfPlayJob> = when (config.jobSource) {
        JobSource.SINGLE -> single(config)
        JobSource.SAME_LEVEL -> CampaignJobFactory.sameLevel(config)
        JobSource.ADJACENT -> CampaignJobFactory.adjacent(config)
    }

    private fun single(config: SelfPlayConfig): List<SelfPlayJob> = if (config.pairColors) {
        val whiteStrength = checkNotNull(config.whiteStrength)
        val blackStrength = checkNotNull(config.blackStrength)
        buildList(config.games) {
            repeat(config.games / 2) { pairIndex ->
                val pairId = "${config.runLabel}-pair-${pairIndex.toString().padStart(6, '0')}"
                add(
                    SelfPlayJob(
                        jobId = "$pairId-a",
                        pairId = pairId,
                        pairLeg = "a",
                        openingId = "single",
                        openingName = "Configured single position",
                        matchupId = "single",
                        whiteLevelId = null,
                        blackLevelId = null,
                        initialFen = config.initialFen,
                        openingMoves = config.openingMoves,
                        whiteCompetitor = "A",
                        blackCompetitor = "B",
                        whiteStrength = whiteStrength,
                        blackStrength = blackStrength,
                    ),
                )
                add(
                    SelfPlayJob(
                        jobId = "$pairId-b",
                        pairId = pairId,
                        pairLeg = "b",
                        openingId = "single",
                        openingName = "Configured single position",
                        matchupId = "single",
                        whiteLevelId = null,
                        blackLevelId = null,
                        initialFen = config.initialFen,
                        openingMoves = config.openingMoves,
                        whiteCompetitor = "B",
                        blackCompetitor = "A",
                        whiteStrength = blackStrength,
                        blackStrength = whiteStrength,
                    ),
                )
            }
        }
    } else {
        val whiteStrength = checkNotNull(config.whiteStrength)
        val blackStrength = checkNotNull(config.blackStrength)
        List(config.games) { index ->
            SelfPlayJob(
                jobId = "${config.runLabel}-game-${index.toString().padStart(6, '0')}",
                pairId = null,
                pairLeg = null,
                openingId = "single",
                openingName = "Configured single position",
                matchupId = "single",
                whiteLevelId = null,
                blackLevelId = null,
                initialFen = config.initialFen,
                openingMoves = config.openingMoves,
                whiteCompetitor = "A",
                blackCompetitor = "B",
                whiteStrength = whiteStrength,
                blackStrength = blackStrength,
            )
        }
    }
}

class SelfPlayGameRunner(private val config: SelfPlayConfig) {
    fun run(job: SelfPlayJob): SelfPlayGameResult {
        val startedAtEpochMillis = System.currentTimeMillis()
        val started = System.nanoTime()
        val opening = replayOpening(config, job)
        var position = opening.position
        var session = opening.session
        val uciMoves = opening.uciMoves.toMutableList()
        val sanMoves = opening.sanMoves.toMutableList()
        val fenTimeline = opening.fenTimeline.toMutableList()

        val searches = mutableListOf<SearchTrace>()
        var whiteEngineName: String? = null
        var blackEngineName: String? = null
        UciEngineProcess.start(config, "${job.jobId}-white", job.whiteStrength).use { white ->
            whiteEngineName = white.engineName
            UciEngineProcess.start(config, "${job.jobId}-black", job.blackStrength).use { black ->
                blackEngineName = black.engineName
                while (session.outcome == null && session.moves.size < config.maxPlies) {
                    check(!Thread.currentThread().isInterrupted) { "Self-play worker interrupted" }
                    val side = session.sideToMove
                    val engine = if (side == Side.WHITE) white else black
                    val strength = if (side == Side.WHITE) job.whiteStrength else job.blackStrength
                    val competitor = if (side == Side.WHITE) {
                        job.whiteCompetitor
                    } else {
                        job.blackCompetitor
                    }
                    val searched = engine.search(
                        initialFen = job.initialFen,
                        moves = session.moves.map { it.move },
                        limit = config.searchLimit,
                    )
                    val san = SanNotation.format(position, searched.move)
                    val transition = ChessAdapter.transition(position, searched.move)
                    session = session.apply(transition)
                    position = ChessRules.apply(position, searched.move)
                    uciMoves += searched.move.value
                    sanMoves += san
                    fenTimeline += position.fen()
                    searches += SearchTrace(
                        ply = session.moves.size,
                        side = side,
                        competitor = competitor,
                        strength = strength.label,
                        elapsedMillis = searched.elapsedMillis,
                        depth = searched.depth,
                        nodes = searched.nodes,
                        scoreType = searched.scoreType,
                        scoreValue = searched.scoreValue,
                        ponder = searched.ponder?.value,
                    )
                }
            }
        }

        val censored = isCensored(session)
        return SelfPlayGameResult(
            job = job,
            startedAtEpochMillis = startedAtEpochMillis,
            elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            engineWhiteName = whiteEngineName,
            engineBlackName = blackEngineName,
            openingPlies = job.openingMoves.size,
            uciMoves = uciMoves.toList(),
            sanMoves = sanMoves.toList(),
            fenTimeline = fenTimeline.toList(),
            searches = searches.toList(),
            session = session,
            censored = censored,
            continuationRecommended = censored && config.markCappedForContinuation,
        )
    }
}

private fun isCensored(session: GameSession): Boolean = session.outcome == null

internal fun PositionFacts.toReportMap(): Map<String, Any?> = linkedMapOf(
    "mover" to mover.name,
    "legal_moves_after" to legalMovesAfter,
    "side_to_move_in_check" to sideToMoveInCheck,
    "position_occurrence_count" to positionOccurrenceCount,
    "repetition_avoiding_alternatives_before_move" to repetitionAvoidingAlternativesBeforeMove,
    "halfmove_clock_after" to halfmoveClockAfter,
    "fifty_move_avoiding_alternatives_before_move" to fiftyMoveAvoidingAlternativesBeforeMove,
    "dead_position_after" to deadPositionAfter,
    "move_was_capture" to moveWasCapture,
    "material_after" to linkedMapOf(
        "white" to materialAfter.white,
        "black" to materialAfter.black,
    ),
)
