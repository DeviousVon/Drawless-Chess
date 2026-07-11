package com.drawlesschess.core.engine

import com.drawlesschess.core.*
import kotlin.math.pow
import kotlin.math.roundToInt

data class NamedBotLevel(
    val id: String,
    val displayName: String,
    val approximateElo: Int,
    val description: String,
) {
    init {
        require(id.matches(Regex("^[a-z][a-z0-9-]*$")))
        require(displayName.isNotBlank() && description.isNotBlank())
        require(approximateElo in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO)
    }
}

object BotDifficultyCatalog {
    const val MINIMUM_ELO = 500
    const val MAXIMUM_ELO = 2850

    val namedLevels: List<NamedBotLevel> = listOf(
        NamedBotLevel("learner", "Learner", 600, "Leaves clear openings and rewards basic tactics."),
        NamedBotLevel("casual", "Casual", 900, "Plays a relaxed game with frequent practical chances."),
        NamedBotLevel("challenger", "Challenger", 1_200, "Punishes simple mistakes without deep calculation."),
        NamedBotLevel("club", "Club", 1_500, "A balanced opponent for experienced social players."),
        NamedBotLevel("expert", "Expert", 1_850, "Finds combinations and defends consistently."),
        NamedBotLevel("master", "Master", 2_200, "Calculates accurately and applies sustained pressure."),
        NamedBotLevel("grandmaster", "Grandmaster", 2_600, "Near-maximum practical strength for mobile play."),
    )

    init {
        require(namedLevels.map { it.id }.distinct().size == namedLevels.size)
        require(namedLevels.zipWithNext().all { (left, right) -> left.approximateElo < right.approximateElo })
    }

    fun named(id: String): NamedBotLevel = namedLevels.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown bot level '$id'")

    fun nearest(elo: Int): NamedBotLevel = namedLevels.minBy { kotlin.math.abs(it.approximateElo - elo) }

    fun clampElo(elo: Int): Int = elo.coerceIn(MINIMUM_ELO, MAXIMUM_ELO)
}

sealed interface BotDifficultySelection {
    data class Named(val levelId: String) : BotDifficultySelection {
        init { require(levelId.isNotBlank()) }
    }

    data class CustomElo(val elo: Int) : BotDifficultySelection {
        init { require(elo in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO) }
    }

    data object Adaptive : BotDifficultySelection
}

data class ResolvedBotDifficulty(
    val label: String,
    val targetElo: Int,
    val strength: EngineStrength.ApproximateElo = EngineStrength.ApproximateElo(targetElo),
    val adaptive: Boolean,
)

object BotDifficultyResolver {
    fun resolve(selection: BotDifficultySelection, playerRating: OfflineRating): ResolvedBotDifficulty =
        when (selection) {
            is BotDifficultySelection.Named -> {
                val level = BotDifficultyCatalog.named(selection.levelId)
                ResolvedBotDifficulty(level.displayName, level.approximateElo, adaptive = false)
            }
            is BotDifficultySelection.CustomElo -> ResolvedBotDifficulty(
                label = "Custom ${selection.elo}",
                targetElo = selection.elo,
                adaptive = false,
            )
            BotDifficultySelection.Adaptive -> {
                val target = BotDifficultyCatalog.clampElo(playerRating.rating)
                ResolvedBotDifficulty(
                    label = "Adaptive · ${BotDifficultyCatalog.nearest(target).displayName}",
                    targetElo = target,
                    adaptive = true,
                )
            }
        }
}

enum class RatedResult(val score: Double) {
    WIN(1.0),
    LOSS(0.0),
}

data class OfflineRating(
    val rating: Int = 1_200,
    val gamesPlayed: Int = 0,
) {
    init {
        require(rating in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO)
        require(gamesPlayed >= 0)
    }

    val provisional: Boolean get() = gamesPlayed < 10
}

enum class RatingTimePool {
    UNTIMED,
    BLITZ,
    RAPID,
    CLASSICAL;

    companion object {
        fun from(control: TimeControl): RatingTimePool = when (control) {
            TimeControl.Untimed -> UNTIMED
            is TimeControl.Clock -> when {
                control.initialMillis < 10 * 60_000L -> BLITZ
                control.initialMillis < 30 * 60_000L -> RAPID
                else -> CLASSICAL
            }
        }
    }
}

data class RatingPoolKey(
    val preset: RulesContractV1.Preset,
    val timePool: RatingTimePool,
)

data class OfflineRatingBook(
    val overall: OfflineRating = OfflineRating(),
    val pools: Map<RatingPoolKey, OfflineRating> = emptyMap(),
) {
    fun forGame(rules: RulesContractV1, timeControl: TimeControl): OfflineRating =
        pools[RatingPoolKey(rules.preset, RatingTimePool.from(timeControl))] ?: OfflineRating()

    fun recordRated(
        mode: GameMode,
        rules: RulesContractV1,
        timeControl: TimeControl,
        opponentElo: Int,
        result: RatedResult,
    ): OfflineRatingBook {
        require(mode == GameMode.RATED) { "Casual games do not change offline ratings" }
        require(opponentElo in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO)
        val key = RatingPoolKey(rules.preset, RatingTimePool.from(timeControl))
        val updatedPool = OfflineElo.update(pools[key] ?: OfflineRating(), opponentElo, result)
        return copy(
            overall = OfflineElo.update(overall, opponentElo, result),
            pools = pools + (key to updatedPool),
        )
    }
}

object OfflineElo {
    fun update(current: OfflineRating, opponentElo: Int, result: RatedResult): OfflineRating {
        require(opponentElo in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO)
        val expected = 1.0 / (1.0 + 10.0.pow((opponentElo - current.rating) / 400.0))
        val k = when {
            current.gamesPlayed < 10 -> 48.0
            current.gamesPlayed < 30 -> 32.0
            else -> 20.0
        }
        val next = (current.rating + k * (result.score - expected)).roundToInt()
        return OfflineRating(
            rating = BotDifficultyCatalog.clampElo(next),
            gamesPlayed = current.gamesPlayed + 1,
        )
    }
}
