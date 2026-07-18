package com.drawlesschess.ui

import android.content.Context
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel

/** Remembers the player's preferred named opponent for the Quick Play shortcut. */
internal class QuickPlayPreferenceStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun load(): NamedBotLevel =
        BotDifficultyCatalog.namedOrNull(preferences.getString(OPPONENT_LEVEL_ID, null))
            ?: DEFAULT_LEVEL

    fun save(level: NamedBotLevel) {
        val supported = BotDifficultyCatalog.namedOrNull(level.id) ?: DEFAULT_LEVEL
        preferences.edit().putString(OPPONENT_LEVEL_ID, supported.id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "drawless-quick-play-preferences-v1"
        const val OPPONENT_LEVEL_ID = "opponent-level-id"
        val DEFAULT_LEVEL = BotDifficultyCatalog.named("casual")
    }
}
