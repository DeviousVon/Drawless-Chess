package com.drawlesschess.core.coordinator

import com.drawlesschess.core.*

data class GameConfig(
    val gameId: String,
    val initialFen: String,
    val rules: RulesContractV1,
    val mode: GameMode,
    val timeControl: TimeControl,
    val humanSide: Side,
    val engineStrength: EngineStrength,
    val engineLimits: EngineLimits,
    /** Stable named-opponent identity; null only for legacy or custom engine configurations. */
    val opponentLevelId: String? = null,
) {
    init {
        require(gameId.isNotBlank() && initialFen.isNotBlank())
        require(opponentLevelId == null || opponentLevelId.matches(Regex("^[a-z][a-z0-9-]*$"))) {
            "Opponent level ID must be a stable lowercase identifier"
        }
    }
}

data class TimeReading(
    val monotonicMillis: Long,
    val epochMillis: Long,
)

fun interface CoordinatorTimeSource {
    fun now(): TimeReading
}

fun interface CoordinatorIdSource {
    fun nextId(): String
}

data class ClockSnapshot(
    val whiteRemainingMillis: Long?,
    val blackRemainingMillis: Long?,
    val runningSide: Side?,
    val paused: Boolean,
)

data class MoveClockSnapshot(
    val ply: Int,
    val whiteRemainingMillis: Long?,
    val blackRemainingMillis: Long?,
)

data class CoordinatorClock(
    val whiteRemainingMillis: Long?,
    val blackRemainingMillis: Long?,
    val runningSide: Side?,
    val startedAtMonotonicMillis: Long?,
    val startedAtEpochMillis: Long?,
    val paused: Boolean = false,
) {
    init {
        require(whiteRemainingMillis == null || whiteRemainingMillis >= 0)
        require(blackRemainingMillis == null || blackRemainingMillis >= 0)
        require((whiteRemainingMillis == null) == (blackRemainingMillis == null))
        require((runningSide == null) == (startedAtMonotonicMillis == null))
        require((runningSide == null) == (startedAtEpochMillis == null))
        require(!paused || runningSide == null)
    }

    val timed: Boolean get() = whiteRemainingMillis != null

    fun projected(now: TimeReading): CoordinatorClock {
        val side = runningSide ?: return this
        if (paused || !timed) return this
        val elapsed = elapsedSince(now)
        return when (side) {
            Side.WHITE -> copy(
                whiteRemainingMillis = (whiteRemainingMillis!! - elapsed).coerceAtLeast(0),
                startedAtMonotonicMillis = now.monotonicMillis,
                startedAtEpochMillis = now.epochMillis,
            )
            Side.BLACK -> copy(
                blackRemainingMillis = (blackRemainingMillis!! - elapsed).coerceAtLeast(0),
                startedAtMonotonicMillis = now.monotonicMillis,
                startedAtEpochMillis = now.epochMillis,
            )
        }
    }

    fun remaining(side: Side): Long? = if (side == Side.WHITE) whiteRemainingMillis else blackRemainingMillis

    fun start(side: Side, now: TimeReading): CoordinatorClock = if (!timed) this else copy(
        runningSide = side,
        startedAtMonotonicMillis = now.monotonicMillis,
        startedAtEpochMillis = now.epochMillis,
        paused = false,
    )

    fun stop(now: TimeReading): CoordinatorClock = projected(now).copy(
        runningSide = null,
        startedAtMonotonicMillis = null,
        startedAtEpochMillis = null,
    )

    fun pause(now: TimeReading): CoordinatorClock = stop(now).copy(paused = true)

    fun resume(side: Side, now: TimeReading): CoordinatorClock = copy(paused = false).start(side, now)

    fun completeMove(
        mover: Side,
        nextSide: Side,
        incrementMillis: Long,
        now: TimeReading,
        nextSideStartDelayMillis: Long = 0,
    ): CoordinatorClock {
        require(nextSideStartDelayMillis >= 0) { "Next-side clock delay must not be negative" }
        if (!timed) return this
        var settled = projected(now)
        settled = when (mover) {
            Side.WHITE -> settled.copy(whiteRemainingMillis = settled.whiteRemainingMillis!! + incrementMillis)
            Side.BLACK -> settled.copy(blackRemainingMillis = settled.blackRemainingMillis!! + incrementMillis)
        }
        val nextSideStartsAt = TimeReading(
            monotonicMillis = now.monotonicMillis + nextSideStartDelayMillis,
            epochMillis = now.epochMillis + nextSideStartDelayMillis,
        )
        return settled.start(nextSide, nextSideStartsAt)
    }

    fun snapshot(now: TimeReading): ClockSnapshot {
        val value = projected(now)
        return ClockSnapshot(
            value.whiteRemainingMillis,
            value.blackRemainingMillis,
            runningSide,
            paused,
        )
    }

    private fun elapsedSince(now: TimeReading): Long {
        val monotonicDelta = now.monotonicMillis - startedAtMonotonicMillis!!
        val wallDelta = now.epochMillis - startedAtEpochMillis!!
        return if (monotonicDelta >= 0 && wallDelta >= 0 && kotlin.math.abs(monotonicDelta - wallDelta) <= 5_000) {
            monotonicDelta
        } else {
            wallDelta.coerceAtLeast(0)
        }
    }

    companion object {
        fun initial(control: TimeControl, sideToMove: Side, now: TimeReading): CoordinatorClock = when (control) {
            TimeControl.Untimed -> CoordinatorClock(null, null, null, null, null)
            is TimeControl.Clock -> CoordinatorClock(
                control.initialMillis,
                control.initialMillis,
                sideToMove,
                now.monotonicMillis,
                now.epochMillis,
            )
        }
    }
}

enum class CoordinatorPhase {
    HUMAN_TURN,
    HINT_THINKING,
    BOT_THINKING,
    BOT_ERROR,
    PAUSED,
    COMPLETED,
}

data class CoordinatorSnapshot(
    val revision: Long,
    val session: GameSession,
    val currentFen: String,
    val phase: CoordinatorPhase,
    val clock: ClockSnapshot,
    val assistance: AssistanceCounts,
    val engineError: String?,
)

data class CoordinatorCheckpoint(
    val revision: Long,
    val config: GameConfig,
    val moves: List<UciMove>,
    val currentFen: String,
    val outcome: GameOutcome?,
    val clock: CoordinatorClock,
    val moveClocks: List<MoveClockSnapshot>,
    val assistance: AssistanceCounts,
)

/**
 * Converts a live checkpoint into the terminal loss used when its player deliberately starts
 * another game. The caller must durably commit this checkpoint before activating a replacement
 * game; keeping that ordering outside the coordinator also prevents a closed runtime from
 * resurrecting the abandoned save with a late write.
 */
fun CoordinatorCheckpoint.forfeitByHuman(now: TimeReading): CoordinatorCheckpoint {
    require(outcome == null) { "Only an unfinished game can be forfeited" }
    return copy(
        revision = Math.addExact(revision, 1L),
        outcome = GameOutcome(
            winner = config.humanSide.opposite(),
            reason = EndReason.RESIGNATION,
            explanation = "${config.humanSide} forfeits the game",
        ),
        clock = clock.stop(now).copy(paused = false),
    )
}

fun interface CheckpointSink {
    fun persist(checkpoint: CoordinatorCheckpoint)
}
