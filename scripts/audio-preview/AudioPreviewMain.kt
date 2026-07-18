package com.drawlesschess.ui

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Host-side preview/quality report for the retained procedural reference renderer. */
fun main(arguments: Array<String>) {
    val outputDirectory = File(arguments.firstOrNull() ?: "android/app/build/audio-previews")
    check(outputDirectory.exists() || outputDirectory.mkdirs()) {
        "Could not create ${outputDirectory.absolutePath}"
    }

    val clips = linkedMapOf(
        "piece_move" to renderMoveSound(capture = false),
        "piece_capture" to renderMoveSound(capture = true),
        "capture_crush" to renderCaptureCrushSound(),
        "check_tick" to renderCheckTickSound(),
        "victory_fireworks" to renderCompletionSequence(CompletionEffectTimeline.Victory),
        "defeat_glass" to renderCompletionSequence(CompletionEffectTimeline.Defeat),
    )

    val metrics = clips.map { (name, pcm) ->
        val file = outputDirectory.resolve("$name.wav")
        writeWave(file, pcm)
        measure(name, pcm, file)
    }
    validate(clips, metrics)
    outputDirectory.resolve("metrics.csv").writeText(
        buildString {
            appendLine("name,duration_seconds,peak,rms,crest_factor,non_silent_percent,clipped_samples,brightness,sha256")
            metrics.forEach { metric ->
                appendLine(
                    listOf(
                        metric.name,
                        "%.4f".formatInvariant(metric.durationSeconds),
                        "%.5f".formatInvariant(metric.peak),
                        "%.5f".formatInvariant(metric.rms),
                        "%.3f".formatInvariant(metric.crestFactor),
                        "%.2f".formatInvariant(metric.nonSilentPercent),
                        metric.clippedSamples,
                        "%.4f".formatInvariant(metric.brightness),
                        metric.sha256,
                    ).joinToString(","),
                )
            }
        },
    )
    renderDiagnostics(outputDirectory.resolve("audio-diagnostics.png"), clips)

    metrics.forEach { metric ->
        println(
            "${metric.name}: ${"%.3f".formatInvariant(metric.durationSeconds)}s, " +
                "peak=${"%.3f".formatInvariant(metric.peak)}, " +
                "rms=${"%.3f".formatInvariant(metric.rms)}, " +
                "crest=${"%.2f".formatInvariant(metric.crestFactor)}, " +
                "non_silent=${"%.1f".formatInvariant(metric.nonSilentPercent)}%, " +
                "clipped=${metric.clippedSamples}, " +
                "brightness=${"%.3f".formatInvariant(metric.brightness)}, " +
                "sha256=${metric.sha256}",
        )
    }
    println(
        "move_capture_correlation=" +
            "%.4f".formatInvariant(normalizedCorrelation(clips.getValue("piece_move"), clips.getValue("piece_capture"))),
    )
    println("Wrote ${outputDirectory.absolutePath}")
}

private fun validate(clips: Map<String, ShortArray>, metrics: List<AudioMetrics>) {
    check(renderMoveSound(capture = false).contentEquals(clips.getValue("piece_move")))
    check(renderMoveSound(capture = true).contentEquals(clips.getValue("piece_capture")))
    check(renderCaptureCrushSound().contentEquals(clips.getValue("capture_crush")))
    check(renderCheckTickSound().contentEquals(clips.getValue("check_tick")))
    check(renderCompletionSequence(CompletionEffectTimeline.Victory).contentEquals(clips.getValue("victory_fireworks")))
    check(renderCompletionSequence(CompletionEffectTimeline.Defeat).contentEquals(clips.getValue("defeat_glass")))

    metrics.forEach { metric ->
        check(metric.durationSeconds in 0.45..2.20) { "Unexpected duration for ${metric.name}" }
        check(metric.peak in 0.65..0.85) { "Unexpected peak for ${metric.name}" }
        check(metric.rms >= 0.035) { "Near-silent output for ${metric.name}" }
        if (metric.name != "check_tick") {
            check(metric.nonSilentPercent >= 60.0) { "Sparse/silent output for ${metric.name}" }
        } else {
            check(metric.nonSilentPercent in 8.0..35.0) { "Check cue does not read as discrete ticks" }
        }
        check(metric.clippedSamples == 0) { "Clipped output for ${metric.name}" }
        check(metric.brightness in 0.65..1.40) { "Unexpected spectrum for ${metric.name}" }
    }
    val byName = metrics.associateBy(AudioMetrics::name)
    check(byName.getValue("piece_capture").rms > byName.getValue("piece_move").rms * 1.45)
    check(byName.getValue("capture_crush").rms > byName.getValue("piece_move").rms * 1.35)
    check(byName.getValue("victory_fireworks").brightness > byName.getValue("defeat_glass").brightness * 1.08)
    check(abs(normalizedCorrelation(clips.getValue("piece_move"), clips.getValue("piece_capture"))) < 0.20)
    check(abs(normalizedCorrelation(clips.getValue("piece_move"), clips.getValue("capture_crush"))) < 0.20)
    check(abs(normalizedCorrelation(clips.getValue("piece_move"), clips.getValue("check_tick"))) < 0.12)
    check(abs(normalizedCorrelation(clips.getValue("victory_fireworks"), clips.getValue("defeat_glass"))) < 0.20)
}

private data class AudioMetrics(
    val name: String,
    val durationSeconds: Double,
    val peak: Double,
    val rms: Double,
    val crestFactor: Double,
    val nonSilentPercent: Double,
    val clippedSamples: Int,
    val brightness: Double,
    val sha256: String,
)

private fun measure(name: String, pcm: ShortArray, waveFile: File): AudioMetrics {
    val normalized = pcm.map { it.toDouble() / Short.MAX_VALUE }
    val peak = normalized.maxOf(::abs)
    val rms = sqrt(normalized.sumOf { it * it } / normalized.size)
    val differenceRms = sqrt(
        normalized.zipWithNext().sumOf { (first, second) ->
            val difference = second - first
            difference * difference
        } / (normalized.size - 1).coerceAtLeast(1),
    )
    return AudioMetrics(
        name = name,
        durationSeconds = pcm.size.toDouble() / SOUND_SAMPLE_RATE,
        peak = peak,
        rms = rms,
        crestFactor = peak / rms.coerceAtLeast(0.000_001),
        nonSilentPercent = pcm.count { abs(it.toInt()) >= 128 } * 100.0 / pcm.size,
        clippedSamples = pcm.count { abs(it.toInt()) >= Short.MAX_VALUE.toInt() },
        brightness = differenceRms / rms.coerceAtLeast(0.000_001),
        sha256 = sha256(waveFile),
    )
}

private fun writeWave(file: File, pcm: ShortArray) {
    BufferedOutputStream(FileOutputStream(file)).use { output ->
        val dataBytes = pcm.size * Short.SIZE_BYTES
        output.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        output.writeLittleEndian(36 + dataBytes, 4)
        output.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
        output.writeLittleEndian(16, 4)
        output.writeLittleEndian(1, 2)
        output.writeLittleEndian(1, 2)
        output.writeLittleEndian(SOUND_SAMPLE_RATE, 4)
        output.writeLittleEndian(SOUND_SAMPLE_RATE * Short.SIZE_BYTES, 4)
        output.writeLittleEndian(Short.SIZE_BYTES, 2)
        output.writeLittleEndian(16, 2)
        output.write("data".toByteArray(StandardCharsets.US_ASCII))
        output.writeLittleEndian(dataBytes, 4)
        pcm.forEach { sample -> output.writeLittleEndian(sample.toInt(), 2) }
    }
}

private fun BufferedOutputStream.writeLittleEndian(value: Int, byteCount: Int) {
    repeat(byteCount) { index -> write((value ushr (index * 8)) and 0xff) }
}

private fun renderDiagnostics(file: File, clips: Map<String, ShortArray>) {
    val width = 1_080
    val rowHeight = 250
    val left = 184
    val right = 18
    val plotWidth = width - left - right
    val image = BufferedImage(width, rowHeight * clips.size, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.color = Color(10, 14, 22)
    graphics.fillRect(0, 0, image.width, image.height)
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)

    clips.entries.forEachIndexed { row, (name, pcm) ->
        val top = row * rowHeight
        graphics.color = Color(235, 239, 246)
        graphics.drawString(name.replace('_', ' '), 18, top + 34)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        graphics.color = Color(145, 157, 178)
        graphics.drawString(
            "${"%.2f".formatInvariant(pcm.size.toDouble() / SOUND_SAMPLE_RATE)} s · 44.1 kHz · mono PCM16",
            18,
            top + 57,
        )
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)

        val waveformTop = top + 14
        val waveformHeight = 72
        graphics.color = Color(30, 38, 53)
        graphics.fillRect(left, waveformTop, plotWidth, waveformHeight)
        graphics.color = Color(114, 211, 191)
        for (x in 0 until plotWidth) {
            val from = x * pcm.size / plotWidth
            val until = max(from + 1, (x + 1) * pcm.size / plotWidth).coerceAtMost(pcm.size)
            var minimum = 0
            var maximum = 0
            for (index in from until until) {
                minimum = minOf(minimum, pcm[index].toInt())
                maximum = maxOf(maximum, pcm[index].toInt())
            }
            val center = waveformTop + waveformHeight / 2
            val y1 = center - (maximum / Short.MAX_VALUE.toDouble() * waveformHeight * 0.47).roundToInt()
            val y2 = center - (minimum / Short.MAX_VALUE.toDouble() * waveformHeight * 0.47).roundToInt()
            graphics.drawLine(left + x, y1, left + x, y2)
        }

        val spectrogramTop = top + 96
        val spectrogramHeight = 138
        val spectra = spectrogram(pcm, plotWidth, 512)
        val maximumDb = spectra.maxOf { column -> column.maxOrNull() ?: -90.0 }
        for (x in spectra.indices) {
            for (y in 0 until spectrogramHeight) {
                val normalizedY = 1.0 - y.toDouble() / (spectrogramHeight - 1)
                val bin = (normalizedY.pow(1.75) * (spectra[x].size - 1)).roundToInt()
                val normalized = ((spectra[x][bin] - (maximumDb - 68.0)) / 68.0).coerceIn(0.0, 1.0)
                image.setRGB(left + x, spectrogramTop + y, heatColor(normalized).rgb)
            }
        }
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        graphics.color = Color(145, 157, 178)
        graphics.drawString("11 kHz", left - 48, spectrogramTop + 9)
        graphics.drawString("0 Hz", left - 35, spectrogramTop + spectrogramHeight)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
    }
    graphics.dispose()
    ImageIO.write(image, "png", file)
}

private fun spectrogram(pcm: ShortArray, columns: Int, fftSize: Int): Array<DoubleArray> =
    Array(columns) { column ->
        val real = DoubleArray(fftSize)
        val imaginary = DoubleArray(fftSize)
        val center = ((column + 0.5) / columns * pcm.size).roundToInt()
        val start = center - fftSize / 2
        repeat(fftSize) { local ->
            val sampleIndex = start + local
            val sample = if (sampleIndex in pcm.indices) pcm[sampleIndex].toDouble() / Short.MAX_VALUE else 0.0
            val window = 0.5 - 0.5 * cos(2.0 * PI * local / (fftSize - 1))
            real[local] = sample * window
        }
        fft(real, imaginary)
        DoubleArray(fftSize / 4) { bin ->
            20.0 * log10(hypot(real[bin], imaginary[bin]).coerceAtLeast(0.000_000_1))
        }
    }

private fun fft(real: DoubleArray, imaginary: DoubleArray) {
    var j = 0
    for (i in 1 until real.size) {
        var bit = real.size shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val realValue = real[i]
            real[i] = real[j]
            real[j] = realValue
            val imaginaryValue = imaginary[i]
            imaginary[i] = imaginary[j]
            imaginary[j] = imaginaryValue
        }
    }

    var length = 2
    while (length <= real.size) {
        val angle = -2.0 * PI / length
        val rootReal = cos(angle)
        val rootImaginary = sin(angle)
        var offset = 0
        while (offset < real.size) {
            var currentReal = 1.0
            var currentImaginary = 0.0
            for (index in 0 until length / 2) {
                val even = offset + index
                val odd = even + length / 2
                val oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary
                val oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal
                real[odd] = real[even] - oddReal
                imaginary[odd] = imaginary[even] - oddImaginary
                real[even] += oddReal
                imaginary[even] += oddImaginary
                val nextReal = currentReal * rootReal - currentImaginary * rootImaginary
                currentImaginary = currentReal * rootImaginary + currentImaginary * rootReal
                currentReal = nextReal
            }
            offset += length
        }
        length = length shl 1
    }
}

private fun heatColor(value: Double): Color {
    val stops = arrayOf(
        0.00 to Color(8, 13, 24),
        0.30 to Color(28, 51, 91),
        0.55 to Color(43, 132, 139),
        0.76 to Color(225, 157, 57),
        1.00 to Color(255, 243, 210),
    )
    val upper = stops.indexOfFirst { it.first >= value }.coerceAtLeast(1)
    val (lowPoint, lowColor) = stops[upper - 1]
    val (highPoint, highColor) = stops[upper]
    val mix = ((value - lowPoint) / (highPoint - lowPoint)).coerceIn(0.0, 1.0)
    fun channel(low: Int, high: Int) = (low + (high - low) * mix).roundToInt()
    return Color(
        channel(lowColor.red, highColor.red),
        channel(lowColor.green, highColor.green),
        channel(lowColor.blue, highColor.blue),
    )
}

private fun normalizedCorrelation(first: ShortArray, second: ShortArray): Double {
    val size = minOf(first.size, second.size)
    var dot = 0.0
    var firstEnergy = 0.0
    var secondEnergy = 0.0
    repeat(size) { index ->
        val left = first[index].toDouble()
        val right = second[index].toDouble()
        dot += left * right
        firstEnergy += left * left
        secondEnergy += right * right
    }
    return dot / sqrt(firstEnergy * secondEnergy).coerceAtLeast(1.0)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun String.formatInvariant(vararg arguments: Any): String =
    java.lang.String.format(java.util.Locale.ROOT, this, *arguments)
