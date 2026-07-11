package com.drawlesschess.core

import java.time.Instant

enum class GameMode { CASUAL, RATED }

sealed interface TimeControl {
    data object Untimed : TimeControl

    data class Clock(
        val initialMillis: Long,
        val incrementMillis: Long = 0,
    ) : TimeControl {
        init {
            require(initialMillis > 0) { "Initial clock time must be positive" }
            require(incrementMillis >= 0) { "Increment cannot be negative" }
        }
    }
}

data class AssistanceCounts(
    val hints: Int = 0,
    val undos: Int = 0,
    val pauses: Int = 0,
) {
    init {
        require(hints >= 0 && undos >= 0 && pauses >= 0) { "Assistance counts cannot be negative" }
    }

    val wasUsed: Boolean get() = hints != 0 || undos != 0 || pauses != 0
}

data class EngineIdentity(
    val id: String,
    val build: String,
    val drawlessPatch: Int,
) {
    init {
        require(id.isNotBlank() && build.isNotBlank()) { "Engine identity cannot be blank" }
        require(drawlessPatch >= 0) { "Patch version cannot be negative" }
    }
}

data class SavedMoveV1(
    val move: UciMove,
    val whiteRemainingMillis: Long? = null,
    val blackRemainingMillis: Long? = null,
) {
    init {
        require(whiteRemainingMillis == null || whiteRemainingMillis >= 0)
        require(blackRemainingMillis == null || blackRemainingMillis >= 0)
    }
}

data class SavedResultV1(
    val winner: Side,
    val reason: EndReason,
    val atPly: Int,
) {
    init {
        require(atPly >= 0) { "Result ply cannot be negative" }
    }
}

data class SavedGameV1(
    val gameId: String,
    val createdAt: Instant,
    val mode: GameMode,
    val initialFen: String,
    val rules: RulesContractV1,
    val timeControl: TimeControl,
    val moves: List<SavedMoveV1>,
    val engine: EngineIdentity,
    val assistance: AssistanceCounts = AssistanceCounts(),
    val result: SavedResultV1? = null,
) {
    val schemaVersion: Int = 1

    init {
        require(gameId.isNotBlank()) { "Game ID cannot be blank" }
        require(initialFen.isNotBlank()) { "Initial FEN cannot be blank" }
        require(mode != GameMode.RATED || !assistance.wasUsed) {
            "Rated games cannot contain hints, undos, or pauses"
        }
        require(result == null || result.atPly <= moves.size) {
            "Result cannot occur beyond saved move history"
        }
        if (timeControl == TimeControl.Untimed) {
            require(moves.all { it.whiteRemainingMillis == null && it.blackRemainingMillis == null }) {
                "Untimed games cannot contain clock snapshots"
            }
        }
    }
}
