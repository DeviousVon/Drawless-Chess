package com.drawlesschess.ui

import com.drawlesschess.core.EndReason
import kotlin.math.roundToLong

internal const val CHECKMATE_COMPLETION_FOLLOWUP_MILLIS = 1_000L
internal const val CHECKMATE_AUDIO_SETTLE_WITHOUT_EFFECT_MILLIS = 1_900L
internal const val COMPLETION_AUDIO_END_SAFETY_MILLIS = 50L

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
    val postVisualAudioTailMillis: Long,
) {
    init {
        require(durationMillis >= 2_000)
        require(cues.isNotEmpty())
        require(postVisualAudioTailMillis >= 0L)
        require(cues.zipWithNext().all { (first, second) -> first.progress < second.progress })
        require(cues.all { it.progress in 0f..1f })
    }
}

internal object CompletionEffectTimeline {
    val Victory = CompletionEffectSpec(
        durationMillis = 2_600,
        // The 2.175-second middle firework begins at 780 ms and ends 355 ms after
        // the visual timeline. Keep GameRoute alive long enough to play the full tail.
        postVisualAudioTailMillis = 400L,
        cues = listOf(
            TimedCompletionCue(CompletionEffectCue.FIREWORK_LOW, 0.08f),
            TimedCompletionCue(CompletionEffectCue.FIREWORK_MID, 0.30f),
            TimedCompletionCue(CompletionEffectCue.FIREWORK_HIGH, 0.52f),
        ),
    )

    val Defeat = CompletionEffectSpec(
        durationMillis = 2_200,
        // The shard layer finishes about 12 ms after the visual timeline.
        postVisualAudioTailMillis = 25L,
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

internal fun postGameReviewAudioSettleMillis(
    playerWon: Boolean,
    reason: EndReason,
    soundEnabled: Boolean,
    celebrationEffectsEnabled: Boolean,
): Long = when {
    !soundEnabled -> 0L
    celebrationEffectsEnabled ->
        CompletionEffectTimeline.forResult(playerWon).postVisualAudioTailMillis
    reason == EndReason.CHECKMATE -> CHECKMATE_AUDIO_SETTLE_WITHOUT_EFFECT_MILLIS
    else -> 0L
}

/**
 * Conservative authored-sample length used to keep GameRoute alive after the final cue starts.
 * This also covers Android's reduced-motion path, where the concise first cue can start at the
 * same instant that the visual animation finishes.
 */
internal fun CompletionEffectCue.audioEndUptimeMillis(startedAtUptimeMillis: Long): Long =
    startedAtUptimeMillis + when (this) {
        CompletionEffectCue.FIREWORK_LOW -> 805L
        CompletionEffectCue.FIREWORK_MID -> 2_175L
        CompletionEffectCue.FIREWORK_HIGH -> 1_251L
        CompletionEffectCue.GLASS_IMPACT -> 420L
        CompletionEffectCue.GLASS_FRACTURE -> 840L
        CompletionEffectCue.GLASS_SHARDS -> 1_420L
    } + COMPLETION_AUDIO_END_SAFETY_MILLIS

internal fun remainingCompletionAudioMillis(
    audioEndsAtUptimeMillis: Long,
    nowUptimeMillis: Long,
): Long = (audioEndsAtUptimeMillis - nowUptimeMillis).coerceAtLeast(0L)

internal fun CompletionEffectSpec.progressOf(cue: CompletionEffectCue): Float =
    cues.firstOrNull { it.cue == cue }?.progress
        ?: error("Completion cue $cue is not part of this timeline")

internal fun CompletionEffectSpec.firstCueOffsetMillis(): Long =
    (durationMillis * cues.first().progress).roundToLong()

/**
 * Starts the overlay early enough for its first timed cue to land on the requested wall-clock
 * instant. A final opponent animation can consume the whole gap, in which case no extra wait is
 * added and the move remains visible through the end of its existing animation.
 */
internal fun completionOverlayDelayMillis(
    firstCueNotBeforeUptimeMillis: Long,
    nowUptimeMillis: Long,
    spec: CompletionEffectSpec,
): Long =
    (firstCueNotBeforeUptimeMillis - nowUptimeMillis - spec.firstCueOffsetMillis())
        .coerceAtLeast(0L)

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
