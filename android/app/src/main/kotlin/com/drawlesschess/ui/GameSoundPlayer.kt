package com.drawlesschess.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Procedural game sounds: no network, packaged media, or third-party audio license required. */
internal class GameSoundPlayer : AutoCloseable {
    private enum class SoundSlot {
        MOVE,
        CAPTURE,
        VICTORY,
        DEFEAT,
    }

    private val lock = Any()
    private val tracks = EnumMap<SoundSlot, AudioTrack>(SoundSlot::class.java)
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closed = false
    private var pendingCompletion: SoundSlot? = null

    init {
        // Rendering and AudioTrack allocation can be slow on older devices. Prepare the four
        // reusable static tracks away from Compose's UI thread, with common actions first.
        preparationScope.launch {
            prepare(SoundSlot.MOVE, renderMoveSound(capture = false))
            ensureActive()
            prepare(SoundSlot.CAPTURE, renderMoveSound(capture = true))
            ensureActive()
            prepare(SoundSlot.DEFEAT, renderCompletionSequence(CompletionEffectTimeline.Defeat))
            ensureActive()
            prepare(SoundSlot.VICTORY, renderCompletionSequence(CompletionEffectTimeline.Victory))
        }
    }

    fun playMove(capture: Boolean) {
        synchronized(lock) {
            if (closed) return
            rewindAndPlay(tracks[if (capture) SoundSlot.CAPTURE else SoundSlot.MOVE])
        }
    }

    fun playCompletionCue(cue: CompletionEffectCue) {
        // Each outcome is one static buffer. Its later sounds are mixed at the exact timeline
        // offsets, so only the first cue starts playback and no six-track pile-up is possible.
        val slot = when (cue) {
            CompletionEffectCue.FIREWORK_LOW -> SoundSlot.VICTORY
            CompletionEffectCue.GLASS_IMPACT -> SoundSlot.DEFEAT
            else -> return
        }
        synchronized(lock) {
            if (closed) return
            val track = tracks[slot]
            if (track == null) {
                pendingCompletion = slot
            } else {
                pendingCompletion = null
                rewindAndPlay(track)
            }
        }
    }

    fun stopAll() {
        synchronized(lock) {
            if (closed) return
            pendingCompletion = null
            tracks.values.forEach(::stopTrack)
        }
    }

    override fun close() {
        preparationScope.cancel()
        val prepared = synchronized(lock) {
            if (closed) return
            closed = true
            tracks.values.toList().also { tracks.clear() }
        }
        prepared.forEach { track ->
            stopTrack(track)
            releaseAudioTrack(track)
        }
    }

    private fun prepare(slot: SoundSlot, pcm: ShortArray) {
        val track = createStaticAudioTrack(pcm) ?: return
        synchronized(lock) {
            if (closed) {
                releaseAudioTrack(track)
            } else {
                tracks.put(slot, track)?.let(::releaseAudioTrack)
                if (pendingCompletion == slot) {
                    pendingCompletion = null
                    rewindAndPlay(track)
                }
            }
        }
    }

    private fun rewindAndPlay(track: AudioTrack?) {
        try {
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) return
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            val rewound = track.setPlaybackHeadPosition(0) == AudioTrack.SUCCESS ||
                track.reloadStaticData() == AudioTrack.SUCCESS
            if (!rewound) return
            track.play()
        } catch (exception: RuntimeException) {
            Log.w(AUDIO_LOG_TAG, "Audio playback failed", exception)
            // Audio output can disappear while backgrounded; chess must keep running.
        }
    }

    private fun stopTrack(track: AudioTrack?) {
        try {
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) return
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
            if (track.setPlaybackHeadPosition(0) != AudioTrack.SUCCESS) track.reloadStaticData()
        } catch (_: RuntimeException) {
            // Already stopped, released by the platform, or never initialized.
        }
    }
}

/**
 * Builds one reusable static track. MODE_STATIC tracks intentionally report
 * STATE_NO_STATIC_DATA until their first successful write; only then may they be
 * required to report STATE_INITIALIZED.
 *
 * Kept internal so the Android regression suite can exercise the real platform state
 * transition instead of validating synthesized PCM alone.
 */
internal fun createStaticAudioTrack(pcm: ShortArray): AudioTrack? {
    var track: AudioTrack? = null
    return try {
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SOUND_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .build()
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.w(AUDIO_LOG_TAG, "Static AudioTrack was uninitialized after construction")
            releaseAudioTrack(track)
            null
        } else {
            val written = track.write(pcm, 0, pcm.size)
            if (written != pcm.size || track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(
                    AUDIO_LOG_TAG,
                    "Static AudioTrack rejected PCM: written=$written expected=${pcm.size} " +
                        "state=${track.state}",
                )
                releaseAudioTrack(track)
                null
            } else {
                track
            }
        }
    } catch (exception: RuntimeException) {
        Log.w(AUDIO_LOG_TAG, "Static AudioTrack initialization failed", exception)
        track?.let(::releaseAudioTrack)
        null
    }
}

private fun releaseAudioTrack(track: AudioTrack) {
    try {
        track.release()
    } catch (_: RuntimeException) {
        // Some vendor audio drivers throw when a half-initialized track is released.
    }
}

private const val AUDIO_LOG_TAG = "DrawlessAudio"

internal const val SOUND_SAMPLE_RATE = 22_050

internal fun renderCompletionCue(cue: CompletionEffectCue): ShortArray = when (cue) {
    CompletionEffectCue.FIREWORK_LOW -> renderFireworkPop(variant = 0)
    CompletionEffectCue.FIREWORK_MID -> renderFireworkPop(variant = 1)
    CompletionEffectCue.FIREWORK_HIGH -> renderFireworkPop(variant = 2)
    CompletionEffectCue.GLASS_IMPACT -> renderGlassImpact()
    CompletionEffectCue.GLASS_FRACTURE -> renderGlassFracture()
    CompletionEffectCue.GLASS_SHARDS -> renderGlassShards()
}

/** Mixes one outcome into one static buffer, keeping every sound on its visual cue marker. */
internal fun renderCompletionSequence(spec: CompletionEffectSpec): ShortArray {
    val firstProgress = spec.cues.first().progress
    val clips = spec.cues.map { timedCue ->
        val offsetSeconds = (timedCue.progress - firstProgress) * spec.durationMillis / 1_000f
        (offsetSeconds * SOUND_SAMPLE_RATE).roundToInt() to renderCompletionCue(timedCue.cue)
    }
    val mixed = FloatArray(clips.maxOf { (offset, pcm) -> offset + pcm.size })
    clips.forEach { (offset, pcm) ->
        pcm.forEachIndexed { index, sample ->
            mixed[offset + index] += sample.toFloat() / Short.MAX_VALUE
        }
    }
    return normalizePcm(mixed, targetPeak = 0.62f)
}

private fun renderMoveSound(capture: Boolean): ShortArray {
    val duration = if (capture) 0.095 else 0.055
    val samples = ShortArray((SOUND_SAMPLE_RATE * duration).toInt())
    val primary = SineOscillator(if (capture) 185.0 else 720.0)
    val secondary = SineOscillator(if (capture) 1_050.0 else 1_180.0)
    val mainDecay = ExponentialDecay(if (capture) 29.0 else 43.0)
    val clickDecay = ExponentialDecay(70.0)
    for (index in samples.indices) {
        val value = if (capture) {
            val click = secondary.next() * clickDecay.next()
            0.72 * primary.next() + 0.28 * click
        } else {
            0.70 * primary.next() + 0.30 * secondary.next()
        }
        samples[index] = (value * mainDecay.next() * Short.MAX_VALUE * 0.42)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
    return samples
}

private fun renderFireworkPop(variant: Int): ShortArray {
    val duration = 0.52 - variant * 0.035
    val samples = FloatArray((SOUND_SAMPLE_RATE * duration).toInt())
    val noise = DeterministicNoise(seed = 0x51F15E + variant * 977)
    val baseFrequency = 152.0 + variant * 34.0
    val highThump = SineOscillator(baseFrequency)
    val lowThump = SineOscillator(baseFrequency * 0.78)
    val thumpDecay = ExponentialDecay(7.2)
    val snapDecay = ExponentialDecay(42.0)
    val crackleDecay = ExponentialDecay(7.5)
    for (index in samples.indices) {
        val time = index.toDouble() / SOUND_SAMPLE_RATE
        val attack = (time / 0.006).coerceIn(0.0, 1.0)
        val sweep = (time / duration).coerceIn(0.0, 1.0)
        val thump = ((1.0 - sweep) * highThump.next() + sweep * lowThump.next()) *
            thumpDecay.next()
        val snap = noise.next() * snapDecay.next()
        val crackleGate = if ((index + variant * 41) % 149 < 11) 1.0 else 0.10
        val crackle = noise.next() * crackleGate * crackleDecay.next()
        samples[index] = (attack * (0.64 * thump + 0.25 * snap + 0.11 * crackle)).toFloat()
    }
    return normalizePcm(samples, targetPeak = 0.60f)
}

private fun renderGlassImpact(): ShortArray {
    val samples = FloatArray((SOUND_SAMPLE_RATE * 0.62).toInt())
    val noise = DeterministicNoise(seed = 0x6A5511)
    val weightOscillator = SineOscillator(178.0)
    val ringLow = SineOscillator(1_280.0)
    val ringHigh = SineOscillator(2_430.0)
    val crackDecay = ExponentialDecay(30.0)
    val weightDecay = ExponentialDecay(9.0)
    val ringDecay = ExponentialDecay(8.2)
    for (index in samples.indices) {
        val time = index.toDouble() / SOUND_SAMPLE_RATE
        val attack = (time / 0.003).coerceIn(0.0, 1.0)
        val crack = noise.next() * crackDecay.next()
        val weight = weightOscillator.next() * weightDecay.next()
        val ring = (ringLow.next() + 0.62 * ringHigh.next()) * ringDecay.next()
        samples[index] = (attack * (0.55 * crack + 0.28 * weight + 0.17 * ring)).toFloat()
    }
    return normalizePcm(samples, targetPeak = 0.58f)
}

private fun renderGlassFracture(): ShortArray {
    val samples = FloatArray((SOUND_SAMPLE_RATE * 0.54).toInt())
    val noise = DeterministicNoise(seed = 0x31AC7)
    val ringLow = SineOscillator(1_710.0)
    val ringHigh = SineOscillator(3_080.0)
    val crackDecay = ExponentialDecay(24.0)
    val branchDecay = ExponentialDecay(9.0)
    val ringDecay = ExponentialDecay(10.5)
    for (index in samples.indices) {
        val crack = noise.next() * crackDecay.next()
        val branchPulse = if (index % 223 < 18) 1.0 else 0.18
        val branches = noise.next() * branchPulse * branchDecay.next()
        val ring = (ringLow.next() + 0.55 * ringHigh.next()) * ringDecay.next()
        samples[index] = (0.49 * crack + 0.25 * branches + 0.26 * ring).toFloat()
    }
    return normalizePcm(samples, targetPeak = 0.54f)
}

private fun renderGlassShards(): ShortArray {
    val duration = 1.02
    val samples = FloatArray((SOUND_SAMPLE_RATE * duration).toInt())
    val starts = doubleArrayOf(0.00, 0.12, 0.27, 0.43, 0.62)
    val frequencies = doubleArrayOf(1_340.0, 1_780.0, 2_260.0, 2_910.0, 3_540.0)
    val oscillators = frequencies.map(::SineOscillator)
    val decays = frequencies.indices.map { shard -> ExponentialDecay(8.5 + shard * 0.9) }
    for (index in samples.indices) {
        val time = index.toDouble() / SOUND_SAMPLE_RATE
        var value = 0.0
        for (shard in starts.indices) {
            val local = time - starts[shard]
            if (local >= 0.0) {
                val attack = (local / 0.004).coerceIn(0.0, 1.0)
                value += attack * oscillators[shard].next() *
                    decays[shard].next() * (0.72 - shard * 0.08)
            }
        }
        samples[index] = value.toFloat()
    }
    return normalizePcm(samples, targetPeak = 0.50f)
}

private fun normalizePcm(samples: FloatArray, targetPeak: Float): ShortArray {
    var maximum = 0f
    samples.forEach { sample -> maximum = maxOf(maximum, abs(sample)) }
    if (maximum < 0.000_001f) return ShortArray(samples.size)
    val scale = Short.MAX_VALUE * targetPeak / maximum
    return ShortArray(samples.size) { index ->
        (samples[index] * scale)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private class DeterministicNoise(seed: Int) {
    private var state = seed

    fun next(): Double {
        state = state * 1_664_525 + 1_013_904_223
        return (((state ushr 8) and 0xFFFF) / 32_767.5) - 1.0
    }
}

/** Stable sine recurrence: one sin/cos pair per tone instead of one trig call per sample. */
private class SineOscillator(frequency: Double) {
    private val step = 2.0 * PI * frequency / SOUND_SAMPLE_RATE
    private val coefficient = 2.0 * cos(step)
    private var previous = -sin(step)
    private var current = 0.0

    fun next(): Double {
        val value = current
        val next = coefficient * current - previous
        previous = current
        current = next
        return value
    }
}

/** Exact sample-to-sample exponential envelope with one exp call per decay, not per sample. */
private class ExponentialDecay(rate: Double) {
    private val multiplier = exp(-rate / SOUND_SAMPLE_RATE)
    private var value = 1.0

    fun next(): Double {
        val current = value
        value *= multiplier
        return current
    }
}
