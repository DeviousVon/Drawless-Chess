package com.drawlesschess.ui

internal enum class CompletionEffectCue {
    FIREWORK_LOW,
    FIREWORK_MID,
    FIREWORK_HIGH,
    GLASS_IMPACT,
    GLASS_FRACTURE,
    GLASS_SHARDS,
}

internal data class TimedCompletionCue(
    val cue: CompletionEffectCue,
    val progress: Float,
)

internal data class CompletionEffectSpec(
    val durationMillis: Int,
    val cues: List<TimedCompletionCue>,
) {
    init {
        require(durationMillis >= 2_000)
        require(cues.isNotEmpty())
        require(cues.zipWithNext().all { (first, second) -> first.progress < second.progress })
        require(cues.all { it.progress in 0f..1f })
    }
}

internal object CompletionEffectTimeline {
    val Victory = CompletionEffectSpec(
        durationMillis = 2_600,
        cues = listOf(
            TimedCompletionCue(CompletionEffectCue.FIREWORK_LOW, 0.08f),
            TimedCompletionCue(CompletionEffectCue.FIREWORK_MID, 0.30f),
            TimedCompletionCue(CompletionEffectCue.FIREWORK_HIGH, 0.52f),
        ),
    )

    val Defeat = CompletionEffectSpec(
        durationMillis = 2_200,
        cues = listOf(
            TimedCompletionCue(CompletionEffectCue.GLASS_IMPACT, 0.04f),
            // Fracture must accompany the first visible crack rays. The prior 0.18 marker made
            // the recognizable crack arrive 308 ms after the visual began.
            TimedCompletionCue(CompletionEffectCue.GLASS_FRACTURE, 0.05f),
            TimedCompletionCue(CompletionEffectCue.GLASS_SHARDS, 0.36f),
        ),
    )

    fun forResult(playerWon: Boolean): CompletionEffectSpec = if (playerWon) Victory else Defeat
}

internal fun CompletionEffectSpec.progressOf(cue: CompletionEffectCue): Float =
    cues.firstOrNull { it.cue == cue }?.progress
        ?: error("Completion cue $cue is not part of this timeline")

/**
 * The impact marker owns only the central impact ring. Crack rays begin with the fracture cue;
 * the final group expands with the shard cue. Keeping this mapping beside the timeline makes the
 * visual start markers directly testable instead of hiding them in Canvas arithmetic.
 */
internal fun CompletionEffectSpec.defeatCrackStartProgress(
    rayIndex: Int,
    rayCount: Int,
): Float {
    require(rayCount > 0)
    require(rayIndex in 0 until rayCount)
    val phase = (rayIndex * 3 / rayCount).coerceAtMost(2)
    val cue = if (phase < 2) {
        CompletionEffectCue.GLASS_FRACTURE
    } else {
        CompletionEffectCue.GLASS_SHARDS
    }
    return progressOf(cue)
}

/** Emits each cue once as an animation crosses its shared visual/audio timeline markers. */
internal class CompletionCueCursor(
    private val cues: List<TimedCompletionCue>,
) {
    private var nextIndex = 0
    private var previousProgress = 0f

    fun advanceTo(progress: Float): List<CompletionEffectCue> {
        val clamped = progress.coerceIn(0f, 1f)
        if (nextIndex == 0 && previousProgress == 0f && clamped >= 0.999f) {
            // Reduced motion deliberately gets one concise outcome cue. Emitting every crossed
            // marker here would collapse three distinct samples into an unpleasant audio burst.
            nextIndex = cues.size
            previousProgress = clamped
            return listOf(cues.first().cue)
        }

        if (clamped < previousProgress) {
            // A frame-clock correction must never replay a sound that was already emitted.
            previousProgress = clamped
            return emptyList()
        }
        previousProgress = clamped

        if (nextIndex >= cues.size || clamped < cues[nextIndex].progress) return emptyList()
        val emitted = mutableListOf<CompletionEffectCue>()
        while (nextIndex < cues.size && clamped >= cues[nextIndex].progress) {
            emitted += cues[nextIndex].cue
            nextIndex += 1
        }
        return emitted
    }
}
