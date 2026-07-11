package com.drawlesschess.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Small procedural sounds: no network, media asset, or third-party audio license required. */
internal class MoveSoundPlayer : AutoCloseable {
    private val moveTrack = createTrack(SoundKind.MOVE)
    private val captureTrack = createTrack(SoundKind.CAPTURE)

    fun play(capture: Boolean) {
        val track = if (capture) captureTrack else moveTrack
        try {
            track?.run {
                pause()
                flush()
                setPlaybackHeadPosition(0)
                play()
            }
        } catch (_: IllegalStateException) {
            // Audio output can disappear while the app is backgrounded; chess must keep running.
        }
    }

    override fun close() {
        listOfNotNull(moveTrack, captureTrack).forEach { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // Already stopped or never initialized.
            }
            track.release()
        }
    }

    private fun createTrack(kind: SoundKind): AudioTrack? = try {
        val pcm = render(kind)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .build()
            .also { track -> track.write(pcm, 0, pcm.size) }
    } catch (_: RuntimeException) {
        null
    }

    private fun render(kind: SoundKind): ShortArray {
        val duration = if (kind == SoundKind.CAPTURE) 0.095 else 0.055
        val samples = ShortArray((SAMPLE_RATE * duration).toInt())
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE
            val envelope = exp(-time * if (kind == SoundKind.CAPTURE) 29.0 else 43.0)
            val value = when (kind) {
                SoundKind.MOVE ->
                    0.70 * sin(2.0 * PI * 720.0 * time) +
                        0.30 * sin(2.0 * PI * 1_180.0 * time)
                SoundKind.CAPTURE -> {
                    // The high component makes the impact audible; the low component gives it weight.
                    val click = sin(2.0 * PI * 1_050.0 * time) * exp(-time * 70.0)
                    0.72 * sin(2.0 * PI * 185.0 * time) + 0.28 * click
                }
            }
            samples[index] = (value * envelope * Short.MAX_VALUE * 0.42)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return samples
    }

    private enum class SoundKind { MOVE, CAPTURE }

    private companion object {
        const val SAMPLE_RATE = 22_050
    }
}
