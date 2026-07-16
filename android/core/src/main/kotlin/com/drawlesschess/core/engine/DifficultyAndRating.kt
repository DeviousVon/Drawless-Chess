package com.drawlesschess.core.engine

import com.drawlesschess.core.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class NamedBotLevel(
    val id: String,
    val approximateElo: Int,
) {
    init {
        require(id.matches(Regex("^[a-z][a-z0-9-]*$")))
        require(approximateElo in BotDifficultyCatalog.MINIMUM_ELO..BotDifficultyCatalog.MAXIMUM_ELO)
    }
}

object BotDifficultyCatalog {
    const val MINIMUM_ELO = 500
    const val MAXIMUM_ELO = 2850

    val namedLevels: List<NamedBotLevel> = listOf(
        NamedBotLevel("learner", 500),
        NamedBotLevel("casual", 650),
        NamedBotLevel("challenger", 850),
        NamedBotLevel("club", 1_100),
        NamedBotLevel("expert", 1_500),
        NamedBotLevel("master", 2_000),
        NamedBotLevel("grandmaster", 2_500),
    )

    /**
     * Checkpoints written before the beginner-focused ladder stored only Elo, not the stable
     * opponent ID. Keep this immutable map so those games retain their original character,
     * label, rematch selection, and statistics bucket after the catalog changes.
     */
    private val legacyNamedLevelIdsByElo: Map<Int, String> = mapOf(
        600 to "learner",
        900 to "casual",
        1_200 to "challenger",
        1_500 to "club",
        1_850 to "expert",
        2_200 to "master",
        2_600 to "grandmaster",
    )

    init {
        require(namedLevels.map { it.id }.distinct().size == namedLevels.size)
        require(namedLevels.zipWithNext().all { (left, right) -> left.approximateElo < right.approximateElo })
    }

    fun named(id: String): NamedBotLevel = namedLevels.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown bot level '$id'")

    fun namedOrNull(id: String?): NamedBotLevel? =
        id?.let { candidate -> namedLevels.singleOrNull { it.id == candidate } }

    fun nearest(elo: Int): NamedBotLevel = namedLevels.minBy { abs(it.approximateElo - elo) }

    /** Used only while decoding checkpoints that predate the stable opponent-ID field. */
    fun legacyLevelIdForElo(elo: Int): String? = legacyNamedLevelIdsByElo[elo]

    /** Selects the best visual profile without pretending a raw engine skill is Casual. */
    fun displayLevel(explicitLevelId: String?, strength: EngineStrength): NamedBotLevel {
        namedOrNull(explicitLevelId)?.let { return it }
        val approximateElo = when (strength) {
            is EngineStrength.ApproximateElo -> strength.elo
            is EngineStrength.SkillLevel -> approximateEloForSkillLevel(strength.level)
        }
        return nearest(approximateElo)
    }

    /**
     * Inverts Fairy-Stockfish's published UCI_Elo-to-skill calibration closely enough to choose
     * a truthful named profile for legacy/raw SkillLevel checkpoints.
     */
    fun approximateEloForSkillLevel(level: Int): Int {
        require(level in -20..20)
        return (MINIMUM_ELO..MAXIMUM_ELO).minBy { elo ->
            abs(fractionalSkillLevel(elo) - level)
        }
    }

    private fun fractionalSkillLevel(elo: Int): Double {
        val shiftedElo = elo - 1_346.6
        val raw = if (shiftedElo > 0.0) {
            (shiftedElo / 143.4).pow(1.0 / 0.806)
        } else {
            val scaled = shiftedElo / 500.0
            shiftedElo / 143.4 + scaled * scaled * scaled * scaled * scaled
        }
        return raw.coerceIn(-20.0, 20.0)
    }

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
    val kind: Kind,
    /** Named level to present for NAMED/ADAPTIVE; null only for a custom Elo. */
    val levelId: String?,
    val targetElo: Int,
    val strength: EngineStrength.ApproximateElo = EngineStrength.ApproximateElo(targetElo),
) {
    enum class Kind { NAMED, CUSTOM_ELO, ADAPTIVE }

    val adaptive: Boolean get() = kind == Kind.ADAPTIVE
}

object BotDifficultyResolver {
    fun resolve(selection: BotDifficultySelection, playerRating: OfflineRating): ResolvedBotDifficulty =
        when (selection) {
            is BotDifficultySelection.Named -> {
                val level = BotDifficultyCatalog.named(selection.levelId)
                ResolvedBotDifficulty(ResolvedBotDifficulty.Kind.NAMED, level.id, level.approximateElo)
            }
            is BotDifficultySelection.CustomElo -> ResolvedBotDifficulty(
                kind = ResolvedBotDifficulty.Kind.CUSTOM_ELO,
                levelId = null,
                targetElo = selection.elo,
            )
            BotDifficultySelection.Adaptive -> {
                val target = BotDifficultyCatalog.clampElo(playerRating.rating)
                ResolvedBotDifficulty(
                    kind = ResolvedBotDifficulty.Kind.ADAPTIVE,
                    levelId = BotDifficultyCatalog.nearest(target).id,
                    targetElo = target,
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
