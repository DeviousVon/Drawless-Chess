package com.drawlesschess.selfplay

import com.drawlesschess.core.GameSession
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.UciMove
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.chess.SanNotation
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal fun runPuzzlePipelineTests(): Int {
    val temporary = Files.createTempDirectory("drawless-puzzle-tests-")
    try {
        val report = temporary.resolve("round-0001-synthetic.jsonl")
        val output = temporary.resolve("candidates.jsonl")
        val fingerprint = "1".repeat(64)
        val engineHash = "2".repeat(64)
        val variantsHash = "3".repeat(64)
        val runtimeHash = "4".repeat(64)
        val moves = listOf("f2f3", "e7e5", "g2g4", "d8h4").map(::UciMove)
        var position = ChessPosition.starting()
        var session = GameSession.newGame(
            gameId = "synthetic-fools-mate",
            rules = RulesContractV1.drawless(),
            initialPositionKey = RepetitionKey.of(position),
            sideToMove = position.sideToMove,
        )
        val timeline = mutableListOf(position.fen())
        val san = mutableListOf<String>()
        moves.forEach { move ->
            san += SanNotation.format(position, move)
            session = session.apply(ChessAdapter.transition(position, move))
            position = ChessRules.apply(position, move)
            timeline += position.fen()
        }
        val outcome = checkNotNull(session.outcome)

        val header = linkedMapOf<String, Any?>(
            "event" to "run_header",
            "run_fingerprint" to fingerprint,
            "report_schema_version" to REPORT_SCHEMA_VERSION,
            "engine_sha256" to engineHash,
            "variants_sha256" to variantsHash,
            "runtime_sha256" to runtimeHash,
            "config" to linkedMapOf(
                "variant" to "drawless",
                "deadPosition" to "material_victory",
                "fiftyMove" to "disabled",
            ),
        )
        val game = linkedMapOf<String, Any?>(
            "event" to "game",
            "run_fingerprint" to fingerprint,
            "record_complete" to true,
            "job_id" to "synthetic-fools-mate",
            "opening_plies" to 0,
            "censored" to false,
            "winner" to outcome.winner.name,
            "loser" to outcome.loser.name,
            "end_reason" to outcome.reason.name,
            "initial_fen" to ChessPosition.START_FEN,
            "uci_moves" to moves.map { it.value },
            "san_moves" to san,
            "fen_timeline" to timeline,
            "final_fen" to timeline.last(),
            "searches" to listOf(
                linkedMapOf(
                    "ply" to 2,
                    "score_type" to "mate",
                    "score_value" to 3,
                ),
            ),
        )
        Files.writeString(
            report,
            Json.encode(header) + "\n" + Json.encode(game) + "\n",
            StandardCharsets.UTF_8,
        )

        val summary = PuzzleMiner.mine(listOf(report), output)
        check(summary.reports == 1 && summary.games == 1 && summary.censoredGames == 0)
        check(summary.terminalCandidates == 1 && summary.forcedMateCandidates == 1)
        check(summary.uniqueCandidates == 2 && summary.duplicateCandidates == 0)

        val read = PuzzleCandidateReader.read(output)
        check(read.sha256.matches(Regex("[0-9a-f]{64}")))
        check(read.candidates.size == 2)
        val terminal = read.candidates.single { it.kind == PuzzleCandidateKind.TERMINAL_MOVE }
        check(terminal.movesBefore == moves.dropLast(1).map { it.value })
        check(terminal.candidateFen == timeline[timeline.lastIndex - 1])
        check(terminal.solutionMove == "d8h4")
        check(terminal.expectedEndReason == outcome.reason)
        check(terminal.expectedWinner == outcome.winner)
        check(
            terminal.candidateId == candidateId(
                terminal.kind,
                terminal.rules,
                terminal.initialFen,
                terminal.movesBefore,
                terminal.candidateFen,
                terminal.solutionMove,
            ),
        )
        val forced = read.candidates.single { it.kind == PuzzleCandidateKind.FORCED_MATE }
        check(forced.sourcePly == 2 && forced.sourceScoreMate == 3)
        check(forced.expectedEndReason == null)

        check(runCatching { PuzzleMiner.mine(listOf(report), output) }.isFailure) {
            "Miner overwrote an existing candidate file without --replace"
        }
        val replaced = PuzzleMiner.mine(listOf(report), output, replace = true)
        check(replaced.uniqueCandidates == 2)
        PuzzleCandidateReader.read(output)
        return 14
    } finally {
        if (Files.exists(temporary)) {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
