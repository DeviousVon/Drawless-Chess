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
            TimedCompletionCue(CompletionEffectCue.GLASS_FRACTURE, 0.18f),
            TimedCompletionCue(CompletionEffectCue.GLASS_SHARDS, 0.36f),
        ),
    )

    fun forResult(playerWon: Boolean): CompletionEffectSpec = if (playerWon) Victory else Defeat
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
            // Start the pre-timed outcome buffer once when Android skips the visual animation.
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
