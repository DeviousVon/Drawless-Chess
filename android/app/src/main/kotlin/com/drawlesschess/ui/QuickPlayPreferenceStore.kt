package com.drawlesschess.ui

import android.content.Context
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel

/** Remembers the player's preferred named or adaptive opponent for Quick Play. */
internal class QuickPlayPreferenceStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun load(): NamedBotLevel {
        val id = preferences.getString(OPPONENT_LEVEL_ID, null)
        return if (id == BotDifficultyCatalog.ADAPTIVE_LEVEL_ID) {
            BotDifficultyCatalog.adaptiveLevel()
        } else {
            BotDifficultyCatalog.namedOrNull(id) ?: DEFAULT_LEVEL
        }
    }

    fun save(level: NamedBotLevel) {
        val supported = when (level.id) {
            BotDifficultyCatalog.ADAPTIVE_LEVEL_ID -> BotDifficultyCatalog.adaptiveLevel()
            else -> BotDifficultyCatalog.namedOrNull(level.id) ?: DEFAULT_LEVEL
        }
        preferences.edit().putString(OPPONENT_LEVEL_ID, supported.id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "drawless-quick-play-preferences-v1"
        const val OPPONENT_LEVEL_ID = "opponent-level-id"
        val DEFAULT_LEVEL = BotDifficultyCatalog.named("casual")
    }
}
