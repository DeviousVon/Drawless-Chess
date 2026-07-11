package com.drawlesschess.ui

import android.content.Context
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes

/** Persists presentation only; game checkpoints remain independent of the chosen look. */
internal class ThemePreferenceStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun load(): BoardTheme = BoardThemes.fromId(preferences.getString(THEME_ID, null))

    fun save(theme: BoardTheme) {
        val supported = BoardThemes.fromId(theme.id)
        preferences.edit().putString(THEME_ID, supported.id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "drawless-settings"
        const val THEME_ID = "visual-theme-v1"
    }
}
