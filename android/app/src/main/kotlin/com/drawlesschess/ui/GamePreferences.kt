package com.drawlesschess.ui

import android.annotation.SuppressLint
import android.content.Context

internal data class GamePreferences(
    val soundEnabled: Boolean = true,
    val soundVolumePercent: Int = DEFAULT_SOUND_VOLUME_PERCENT,
    val hapticFeedbackEnabled: Boolean = true,
    val threatIndicationEnabled: Boolean = false,
    val boardCoordinatesEnabled: Boolean = true,
    val celebrationEffectsEnabled: Boolean = true,
)

/** Device-local presentation and assistance preferences; no account or network is involved. */
internal class GamePreferenceStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun load(): GamePreferences = GamePreferences(
        soundEnabled = preferences.getBoolean(SOUND_ENABLED, true),
        soundVolumePercent = preferences.getInt(SOUND_VOLUME_PERCENT, DEFAULT_SOUND_VOLUME_PERCENT)
            .coerceIn(MIN_SOUND_VOLUME_PERCENT, MAX_SOUND_VOLUME_PERCENT),
        hapticFeedbackEnabled = preferences.getBoolean(HAPTIC_FEEDBACK_ENABLED, true),
        threatIndicationEnabled = preferences.getBoolean(THREAT_INDICATION_ENABLED, false),
        boardCoordinatesEnabled = preferences.getBoolean(BOARD_COORDINATES_ENABLED, true),
        celebrationEffectsEnabled = preferences.getBoolean(CELEBRATION_EFFECTS_ENABLED, true),
    )

    @SuppressLint("UseKtx")
    fun save(value: GamePreferences) {
        preferences.edit()
            .putBoolean(SOUND_ENABLED, value.soundEnabled)
            .putInt(
                SOUND_VOLUME_PERCENT,
                value.soundVolumePercent.coerceIn(MIN_SOUND_VOLUME_PERCENT, MAX_SOUND_VOLUME_PERCENT),
            )
            .putBoolean(HAPTIC_FEEDBACK_ENABLED, value.hapticFeedbackEnabled)
            .putBoolean(THREAT_INDICATION_ENABLED, value.threatIndicationEnabled)
            .putBoolean(BOARD_COORDINATES_ENABLED, value.boardCoordinatesEnabled)
            .putBoolean(CELEBRATION_EFFECTS_ENABLED, value.celebrationEffectsEnabled)
            .apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "drawless-game-preferences-v1"
        private const val SOUND_ENABLED = "sound-enabled"
        private const val SOUND_VOLUME_PERCENT = "sound-volume-percent"
        private const val HAPTIC_FEEDBACK_ENABLED = "haptic-feedback-enabled"
        private const val THREAT_INDICATION_ENABLED = "threat-indication-enabled"
        private const val BOARD_COORDINATES_ENABLED = "board-coordinates-enabled"
        private const val CELEBRATION_EFFECTS_ENABLED = "celebration-effects-enabled"
    }
}

internal const val MIN_SOUND_VOLUME_PERCENT = 0
internal const val DEFAULT_SOUND_VOLUME_PERCENT = 50
internal const val MAX_SOUND_VOLUME_PERCENT = 100

internal fun soundVolumeMultiplier(volumePercent: Int): Float =
    volumePercent.coerceIn(MIN_SOUND_VOLUME_PERCENT, MAX_SOUND_VOLUME_PERCENT) /
        MAX_SOUND_VOLUME_PERCENT.toFloat()
