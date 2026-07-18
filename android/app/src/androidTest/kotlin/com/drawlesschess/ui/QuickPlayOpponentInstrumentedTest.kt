package com.drawlesschess.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.drawlesschess.core.engine.BotDifficultyCatalog
import com.drawlesschess.core.engine.NamedBotLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickPlayOpponentInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun opponentDialogOffersTheFullDifficultyLadder() {
        val selected = mutableStateOf(BotDifficultyCatalog.named("casual"))
        compose.setContent {
            DrawlessTheme {
                QuickPlayOpponentDialog(
                    selectedLevel = selected.value,
                    onSelected = { selected.value = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("opponent_option_casual").assertIsSelected()
        compose.onNodeWithTag("opponent_picker").performScrollToIndex(6)
        compose.onNodeWithTag("opponent_option_grandmaster")
            .performClick()
            .assertIsSelected()
        compose.runOnIdle {
            assertEquals(BotDifficultyCatalog.named("grandmaster"), selected.value)
        }
    }

    @Test
    fun preferenceSurvivesReloadAndRejectsUnknownLevels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "quick-play-opponent-test"
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val store = QuickPlayPreferenceStore(context, preferencesName)
            assertEquals(BotDifficultyCatalog.named("casual"), store.load())

            store.save(BotDifficultyCatalog.named("grandmaster"))
            assertEquals(
                BotDifficultyCatalog.named("grandmaster"),
                QuickPlayPreferenceStore(context, preferencesName).load(),
            )

            store.save(NamedBotLevel("unknown", 1_000))
            assertEquals(BotDifficultyCatalog.named("casual"), store.load())
        } finally {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
