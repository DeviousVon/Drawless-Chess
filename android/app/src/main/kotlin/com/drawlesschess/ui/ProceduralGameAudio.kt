package com.drawlesschess.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Retained deterministic physical-model reference renderer. Production playback uses the
 * audited sampled library in [SampledSoundCatalog]; these functions remain for regression
 * comparisons and host-side diagnostics only.
 *
 * The renderer deliberately uses broadband excitation, filtered friction, and clusters of
 * inharmonic resonances instead of note-like alert tones. Nothing here is sampled or derived
 * from a third-party recording.
 */
internal const val SOUND_SAMPLE_RATE = 44_100

internal fun renderMoveSound(capture: Boolean): ShortArray {
    val duration = if (capture) 0.58 else 0.48
    val mixed = FloatArray(sampleCount(duration))

    // A subtle lift, continuous base/felt movement, then the piece settling on wood.
    addWoodContact(mixed, startSeconds = 0.0, strength = if (capture) 0.24 else 0.19, seed = 0x7183)
    addSurfaceScrape(
        mixed,
        startSeconds = 0.055,
        durationSeconds = if (capture) 0.35 else 0.31,
        strength = if (capture) 0.16 else 0.19,
        seed = if (capture) 0xCA7812 else 0x4D0B5,
    )

    if (capture) {
        // The moving piece strikes and displaces the captured piece before settling.
        addHeavyPieceImpact(mixed, startSeconds = 0.285, strength = 0.82, seed = 0xC4A7E)
        addWoodContact(mixed, startSeconds = 0.326, strength = 0.43, seed = 0xC0111)
        addWoodContact(mixed, startSeconds = 0.438, strength = 0.72, seed = 0x51A9)
        addWoodContact(mixed, startSeconds = 0.473, strength = 0.17, seed = 0x51AA)
    } else {
        addWoodContact(mixed, startSeconds = 0.382, strength = 0.86, seed = 0xB04D)
        addWoodContact(mixed, startSeconds = 0.414, strength = 0.14, seed = 0xB04E)
    }

    return masterPcm(mixed, targetPeak = if (capture) 0.80f else 0.72f)
}

/** A compact wood-crush gesture, deliberately unlike the normal lift-and-place recordings. */
internal fun renderCaptureCrushSound(): ShortArray {
    val mixed = FloatArray(sampleCount(0.56))
    addHeavyPieceImpact(mixed, startSeconds = 0.0, strength = 1.22, seed = 0x5A451)
    addCrushBurst(mixed, startSeconds = 0.018, strength = 0.72, seed = 0x5A452)
    addCrushBurst(mixed, startSeconds = 0.066, strength = 0.58, seed = 0x5A453)
    addCrushBurst(mixed, startSeconds = 0.112, strength = 0.46, seed = 0x5A454)
    addCrushBurst(mixed, startSeconds = 0.169, strength = 0.34, seed = 0x5A455)
    addSurfaceScrape(
        mixed,
        startSeconds = 0.075,
        durationSeconds = 0.30,
        strength = 0.27,
        seed = 0x5A456,
    )
    addWoodContact(mixed, startSeconds = 0.335, strength = 0.55, seed = 0x5A457)
    return masterPcm(mixed, targetPeak = 0.82f, attackMillis = 0.35)
}

/** Two spaced mechanical ticks; the initial gap keeps the cue clear of the move impact. */
internal fun renderCheckTickSound(): ShortArray {
    val mixed = FloatArray(sampleCount(0.48))
    addClockTick(mixed, startSeconds = 0.070, strength = 0.82, seed = 0x71C01)
    addClockTick(mixed, startSeconds = 0.255, strength = 1.00, seed = 0x71C02)
    return masterPcm(mixed, targetPeak = 0.76f, attackMillis = 0.20)
}

private fun addCrushBurst(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(0.085)
    val noise = DeterministicNoise(seed)
    val bodyFilter = OnePoleLowPass(920.0)
    val crackReference = OnePoleLowPass(4_100.0)
    val lowBody = DampedSine(83.0 + (seed and 7), 22.0, 0.31)
    val midBody = DampedSine(167.0 + (seed and 15), 31.0, 1.07)
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val raw = noise.next()
        val body = bodyFilter.next(raw)
        val crack = raw - crackReference.next(raw)
        val grain = 0.58 + 0.42 * abs(sin(2.0 * PI * 73.0 * time))
        val envelope = exp(-34.0 * time) * (time / 0.00035).coerceIn(0.0, 1.0)
        val resonant = lowBody.next() * 0.34 + midBody.next() * 0.22
        destination[destinationIndex] +=
            (strength * envelope * grain * (0.48 * crack + 0.30 * body + resonant)).toFloat()
    }
}

private fun addClockTick(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(0.072)
    val noise = DeterministicNoise(seed)
    val lowReference = OnePoleLowPass(2_250.0)
    val body = DampedSine(1_180.0 + (seed and 31), 54.0, 0.24)
    val edge = DampedSine(2_060.0 + (seed and 63), 79.0, 1.18)
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val raw = noise.next()
        val click = (raw - lowReference.next(raw)) * exp(-165.0 * time)
        val mechanism = 0.44 * body.next() + 0.22 * edge.next()
        val attack = (time / 0.00025).coerceIn(0.0, 1.0)
        destination[destinationIndex] +=
            (strength * attack * (0.62 * click + mechanism)).toFloat()
    }
}

internal fun renderCompletionCue(cue: CompletionEffectCue): ShortArray = when (cue) {
    CompletionEffectCue.FIREWORK_LOW -> renderFireworkBurst(variant = 0)
    CompletionEffectCue.FIREWORK_MID -> renderFireworkBurst(variant = 1)
    CompletionEffectCue.FIREWORK_HIGH -> renderFireworkBurst(variant = 2)
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
    return masterPcm(mixed, targetPeak = 0.82f, attackMillis = 0.35)
}

private fun addWoodContact(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(0.115)
    val noise = DeterministicNoise(seed)
    val lowPass = OnePoleLowPass(2_700.0)
    val bodyFrequencies = doubleArrayOf(171.0, 294.0, 487.0, 731.0, 1_126.0)
    val bodyWeights = doubleArrayOf(0.32, 0.27, 0.20, 0.13, 0.08)
    val bodyDecays = doubleArrayOf(27.0, 32.0, 38.0, 46.0, 59.0)
    val phases = DoubleArray(bodyFrequencies.size) { mode ->
        ((seed ushr (mode * 3)) and 0x1f) / 31.0 * PI
    }
    val bodyModes = bodyFrequencies.indices.map { mode ->
        DampedSine(bodyFrequencies[mode], bodyDecays[mode], phases[mode])
    }

    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val rawNoise = noise.next()
        val woodyNoise = lowPass.next(rawNoise)
        val transient = (rawNoise - woodyNoise * 0.56) * exp(-92.0 * time)
        var body = 0.0
        for (mode in bodyFrequencies.indices) {
            body += bodyWeights[mode] * bodyModes[mode].next()
        }
        val attack = (time / 0.0007).coerceIn(0.0, 1.0)
        destination[destinationIndex] +=
            (strength * attack * (0.63 * transient + 0.37 * body)).toFloat()
    }
}

private fun addSurfaceScrape(
    destination: FloatArray,
    startSeconds: Double,
    durationSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(durationSeconds)
    val noise = DeterministicNoise(seed)
    val broadLowPass = OnePoleLowPass(4_600.0)
    val dullLowPass = OnePoleLowPass(460.0)
    val pressureLowPass = OnePoleLowPass(31.0)
    var phase = 0.0
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val progress = localIndex.toDouble() / (length - 1).coerceAtLeast(1)
        val raw = noise.next()
        val broad = broadLowPass.next(raw)
        val texture = broad - dullLowPass.next(broad)
        val pressure = 0.78 + 0.22 * abs(pressureLowPass.next(raw) * 3.4).coerceAtMost(1.0)
        phase += 2.0 * PI * (18.0 + 7.0 * progress) / SOUND_SAMPLE_RATE
        val grain = 0.50 + 0.50 * abs(sin(phase))
        val envelope = sin(PI * progress).coerceAtLeast(0.0).pow(0.58)
        destination[destinationIndex] +=
            (strength * texture * pressure * grain * envelope).toFloat()
    }
}

private fun addHeavyPieceImpact(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(0.19)
    val noise = DeterministicNoise(seed)
    val lowPass = OnePoleLowPass(680.0)
    val highReference = OnePoleLowPass(3_200.0)
    val modes = doubleArrayOf(92.0, 147.0, 263.0, 419.0, 683.0)
    val weights = doubleArrayOf(0.28, 0.25, 0.21, 0.16, 0.10)
    val bodyModes = modes.indices.map { index ->
        DampedSine(modes[index], 13.0 + index * 5.5, index * 0.71)
    }
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val raw = noise.next()
        val low = lowPass.next(raw)
        val crack = raw - highReference.next(raw)
        var body = 0.0
        bodyModes.forEachIndexed { index, oscillator ->
            body += weights[index] * oscillator.next()
        }
        val impact = 0.40 * crack * exp(-68.0 * time) +
            0.37 * low * exp(-18.0 * time) +
            0.23 * body
        val attack = (time / 0.001).coerceIn(0.0, 1.0)
        destination[destinationIndex] += (strength * attack * impact).toFloat()
    }
}

private fun renderFireworkBurst(variant: Int): ShortArray {
    val duration = 0.96 - variant * 0.025
    val dry = FloatArray(sampleCount(duration))
    val noise = DeterministicNoise(0x51F15E + variant * 977)
    val rumbleFilter = OnePoleLowPass(245.0 + variant * 25.0)
    val bodyFilter = OnePoleLowPass(1_650.0 + variant * 180.0)
    val crackReference = OnePoleLowPass(3_700.0)
    val baseFrequency = 72.0 + variant * 9.0
    var boomPhase = 0.0

    for (index in dry.indices) {
        val time = index.toDouble() / SOUND_SAMPLE_RATE
        val progress = time / duration
        val raw = noise.next()
        val rumble = rumbleFilter.next(raw)
        val body = bodyFilter.next(raw) - 0.72 * rumble
        val crack = raw - crackReference.next(raw)
        val sweptFrequency = baseFrequency * (1.0 - 0.34 * progress)
        boomPhase += 2.0 * PI * sweptFrequency / SOUND_SAMPLE_RATE
        val boom = sin(boomPhase) * exp(-(4.2 + variant * 0.25) * time)
        val pressure = 0.42 * rumble * exp(-4.6 * time) +
            0.25 * body * exp(-6.2 * time) +
            0.18 * boom +
            0.52 * crack * exp(-120.0 * time)
        val attack = (time / 0.0012).coerceIn(0.0, 1.0)
        dry[index] = (attack * pressure).toFloat()
    }

    val sparkNoise = DeterministicNoise(0xF1A000 + variant * 1_171)
    val sparkCount = 21 + variant * 4
    repeat(sparkCount) { spark ->
        val spread = (spark + 1).toDouble() / (sparkCount + 1)
        val jitter = (sparkNoise.next() * 0.019)
        val start = 0.055 + spread.pow(1.35) * 0.67 + jitter
        addCrackle(dry, start, 0.16 + 0.13 * (1.0 - spread), sparkNoise.nextInt())
    }

    // Short, irregular outdoor reflections create distance without a pitched reverb tail.
    val reflected = dry.copyOf()
    addDelayedCopy(reflected, dry, delaySeconds = 0.083 + variant * 0.004, gain = 0.23)
    addDelayedCopy(reflected, dry, delaySeconds = 0.191 + variant * 0.007, gain = 0.13)
    addDelayedCopy(reflected, dry, delaySeconds = 0.337 + variant * 0.005, gain = 0.07)
    return masterPcm(dry, targetPeak = 0.80f, attackMillis = 0.5)
}

private fun addCrackle(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(0.025 + (seed and 0xf) / 1_000.0)
    val noise = DeterministicNoise(seed)
    val lowReference = OnePoleLowPass(2_500.0)
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val raw = noise.next()
        val bright = raw - lowReference.next(raw)
        val envelope = exp(-105.0 * time) * (time / 0.0004).coerceIn(0.0, 1.0)
        destination[destinationIndex] += (strength * bright * envelope).toFloat()
    }
}

private fun addDelayedCopy(
    source: FloatArray,
    destination: FloatArray,
    delaySeconds: Double,
    gain: Double,
) {
    val delay = sampleCount(delaySeconds)
    for (sourceIndex in 0 until source.size - delay) {
        destination[sourceIndex + delay] += (source[sourceIndex] * gain).toFloat()
    }
}

private fun renderGlassImpact(): ShortArray {
    val mixed = FloatArray(sampleCount(0.82))
    addGlassEvent(mixed, 0.0, 0.92, 0x6A5511, 1_120.0, ringSeconds = 0.70)
    addHeavyPieceImpact(mixed, 0.0, 0.58, 0x6A5512)
    addGlassEvent(mixed, 0.074, 0.31, 0x6A5513, 1_840.0, ringSeconds = 0.47)
    addGlassEvent(mixed, 0.142, 0.21, 0x6A5514, 2_360.0, ringSeconds = 0.36)
    return masterPcm(mixed, targetPeak = 0.78f)
}

private fun renderGlassFracture(): ShortArray {
    val mixed = FloatArray(sampleCount(0.78))
    val noise = DeterministicNoise(0x31AC7)
    repeat(13) { fracture ->
        val progress = fracture.toDouble() / 13.0
        val start = 0.006 + progress.pow(1.28) * 0.52 + noise.next() * 0.012
        val strength = 0.50 - progress * 0.24
        val frequency = 1_450.0 + (noise.next() + 1.0) * 1_260.0
        addGlassEvent(
            mixed,
            start.coerceAtLeast(0.0),
            strength,
            noise.nextInt(),
            frequency,
            ringSeconds = 0.16 + (1.0 - progress) * 0.14,
        )
    }
    return masterPcm(mixed, targetPeak = 0.72f)
}

private fun renderGlassShards(): ShortArray {
    val mixed = FloatArray(sampleCount(1.12))
    val noise = DeterministicNoise(0x5A4D5)
    repeat(15) { shard ->
        val progress = shard.toDouble() / 15.0
        val start = 0.015 + progress.pow(1.18) * 0.82 + noise.next() * 0.018
        val frequency = 1_750.0 + (noise.next() + 1.0) * 1_670.0
        addGlassEvent(
            mixed,
            start.coerceAtLeast(0.0),
            0.34 - progress * 0.15,
            noise.nextInt(),
            frequency,
            ringSeconds = 0.20 + (1.0 - progress) * 0.19,
        )
    }
    return masterPcm(mixed, targetPeak = 0.66f)
}

private fun addGlassEvent(
    destination: FloatArray,
    startSeconds: Double,
    strength: Double,
    seed: Int,
    baseFrequency: Double,
    ringSeconds: Double,
) {
    val start = sampleCount(startSeconds)
    val length = sampleCount(ringSeconds)
    val noise = DeterministicNoise(seed)
    val lowReference = OnePoleLowPass(3_100.0)
    val ratios = doubleArrayOf(1.0, 1.37, 1.91, 2.46, 3.14)
    val weights = doubleArrayOf(0.32, 0.26, 0.20, 0.14, 0.08)
    val ringModes = ratios.indices.map { mode ->
        val frequency = (baseFrequency * ratios[mode]).coerceAtMost(17_000.0)
        DampedSine(frequency, 10.0 + mode * 4.1, mode * 0.43)
    }
    for (localIndex in 0 until length) {
        val destinationIndex = start + localIndex
        if (destinationIndex !in destination.indices) break
        val time = localIndex.toDouble() / SOUND_SAMPLE_RATE
        val raw = noise.next()
        val click = (raw - lowReference.next(raw)) * exp(-145.0 * time)
        var rings = 0.0
        for (mode in ringModes.indices) {
            rings += weights[mode] * ringModes[mode].next()
        }
        val attack = (time / 0.00045).coerceIn(0.0, 1.0)
        destination[destinationIndex] +=
            (strength * attack * (0.58 * click + 0.42 * rings)).toFloat()
    }
}

private fun masterPcm(
    input: FloatArray,
    targetPeak: Float,
    attackMillis: Double = 0.65,
): ShortArray {
    if (input.isEmpty()) return ShortArray(0)
    val filtered = FloatArray(input.size)
    // Remove sub-audible/DC energy that wastes headroom on small phone speakers.
    val highPassCoefficient = exp(-2.0 * PI * 18.0 / SOUND_SAMPLE_RATE)
    var previousInput = 0.0
    var previousOutput = 0.0
    input.forEachIndexed { index, sample ->
        val output = highPassCoefficient * (previousOutput + sample - previousInput)
        previousInput = sample.toDouble()
        previousOutput = output
        filtered[index] = output.toFloat()
    }

    val attackSamples = sampleCount(attackMillis / 1_000.0).coerceAtLeast(1)
    val releaseSamples = sampleCount(0.018).coerceAtMost(filtered.size)
    filtered.indices.forEach { index ->
        val attack = (index.toDouble() / attackSamples).coerceIn(0.0, 1.0)
        val samplesFromEnd = filtered.lastIndex - index
        val release = (samplesFromEnd.toDouble() / releaseSamples).coerceIn(0.0, 1.0)
        filtered[index] = (filtered[index] * attack * release).toFloat()
    }

    var maximum = 0f
    filtered.forEach { sample -> maximum = maxOf(maximum, abs(sample)) }
    if (maximum < 0.000_001f) return ShortArray(filtered.size)
    val scale = Short.MAX_VALUE * targetPeak / maximum
    return ShortArray(filtered.size) { index ->
        (filtered[index] * scale)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private fun sampleCount(seconds: Double): Int = (seconds * SOUND_SAMPLE_RATE).roundToInt()

private class OnePoleLowPass(cutoffHz: Double) {
    private val coefficient = 1.0 - exp(-2.0 * PI * cutoffHz / SOUND_SAMPLE_RATE)
    private var value = 0.0

    fun next(input: Double): Double {
        value += coefficient * (input - value)
        return value
    }
}

/** Stable recurrence keeps the many short physical resonances cheap on older devices. */
private class DampedSine(frequency: Double, decayRate: Double, phase: Double) {
    private val step = 2.0 * PI * frequency / SOUND_SAMPLE_RATE
    private val recurrence = 2.0 * cos(step)
    private val decay = exp(-decayRate / SOUND_SAMPLE_RATE)
    private var previous = sin(phase - step)
    private var current = sin(phase)
    private var envelope = 1.0

    fun next(): Double {
        val value = current * envelope
        val following = recurrence * current - previous
        previous = current
        current = following
        envelope *= decay
        return value
    }
}

private class DeterministicNoise(seed: Int) {
    private var state = seed

    fun next(): Double {
        state = state * 1_664_525 + 1_013_904_223
        return (((state ushr 8) and 0xFFFF) / 32_767.5) - 1.0
    }

    fun nextInt(): Int {
        next()
        return state
    }
}
