package com.drawlesschess.core

data class EngineRequest(
    val requestId: String,
    val gameId: String,
    val positionId: String,
    val initialFen: String,
    val moves: List<UciMove>,
    val rules: RulesContractV1,
    val strength: EngineStrength,
    val limits: EngineLimits,
    val purpose: EnginePurpose = EnginePurpose.BOT_MOVE,
) {
    init {
        require(requestId.isNotBlank() && gameId.isNotBlank() && positionId.isNotBlank())
        require(initialFen.isNotBlank())
    }
}

enum class EnginePurpose {
    BOT_MOVE,
    HINT,
    REVIEW,
}

sealed interface EngineStrength {
    data class ApproximateElo(val elo: Int) : EngineStrength {
        init { require(elo in 500..2850) }
    }

    data class SkillLevel(val level: Int) : EngineStrength {
        init { require(level in -20..20) }
    }
}

data class EngineLimits(
    val moveTimeMillis: Long,
    val multiPv: Int = 1,
) {
    init {
        require(moveTimeMillis > 0)
        require(multiPv in 1..10)
    }
}

/** Root-side outcome counts reported by UCI, normally expressed per thousand positions. */
data class EngineWdl(
    val wins: Int,
    val draws: Int,
    val losses: Int,
) {
    init {
        require(wins >= 0 && draws >= 0 && losses >= 0)
        require(wins + draws + losses > 0) { "WDL evidence cannot be empty" }
    }
}

data class PrincipalVariation(
    val scoreCentipawns: Int?,
    val mateIn: Int?,
    val moves: List<UciMove>,
    val rank: Int = 1,
    val bound: EngineScoreBound = EngineScoreBound.EXACT,
    val wdl: EngineWdl? = null,
    val depth: Int? = null,
    /** False only for the compatibility fallback emitted when no scored PV was received. */
    val evidenceAvailable: Boolean = true,
) {
    init {
        require((scoreCentipawns == null) xor (mateIn == null)) {
            "A variation requires exactly one score representation"
        }
        require(moves.isNotEmpty()) { "A variation requires at least one move" }
        require(rank >= 1) { "Variation rank must be positive" }
        require(depth == null || depth >= 0) { "Variation depth cannot be negative" }
    }
}

enum class EngineScoreBound {
    EXACT,
    LOWER,
    UPPER,
}

data class EngineResponse(
    val requestId: String,
    val gameId: String,
    val positionId: String,
    val bestMove: UciMove,
    val ponderMove: UciMove?,
    val depth: Int,
    val nodes: Long,
    val variations: List<PrincipalVariation>,
    val engine: EngineIdentity,
) {
    init {
        require(depth >= 0 && nodes >= 0)
        require(variations.isNotEmpty())
    }

    fun matches(request: EngineRequest): Boolean =
        requestId == request.requestId && gameId == request.gameId && positionId == request.positionId
}

fun interface EngineCancellation {
    fun cancel()
}

interface ChessEngine {
    fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation
}
