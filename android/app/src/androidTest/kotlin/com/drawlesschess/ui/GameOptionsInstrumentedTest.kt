package com.drawlesschess.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drawlesschess.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameOptionsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun preferenceStoreDefaultsAndRoundTripsAllOptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "game-options-round-trip-test"
        context.deleteSharedPreferences(name)
        val store = GamePreferenceStore(context, name)

        assertEquals(GamePreferences(), store.load())
        val changed = GamePreferences(
            soundEnabled = false,
            threatIndicationEnabled = true,
            boardCoordinatesEnabled = false,
            celebrationEffectsEnabled = false,
        )
        store.save(changed)
        assertEquals(changed, GamePreferenceStore(context, name).load())
        context.deleteSharedPreferences(name)
    }

    @Test
    fun optionsNavigateAndPersistAcrossActivityRecreation() {
        val defaults = GamePreferences()
        val store = GamePreferenceStore(compose.activity)
        store.save(defaults)
        compose.activity.runOnUiThread {
            ViewModelProvider(compose.activity)[DrawlessAppViewModel::class.java]
                .updateGamePreferences(defaults)
        }
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()

        try {
            dismissRulesGuideIfShown()
            waitForTag("home_options")
            compose.onNodeWithTag("home_options").performScrollTo().performClick()
            waitForText("Options")

            compose.onNodeWithTag("option_sound_effects").assertIsOn().performClick().assertIsOff()
            compose.onNodeWithTag("option_board_coordinates").assertIsOn().performClick().assertIsOff()
            compose.onNodeWithTag("option_threat_indication").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("option_celebration_effects").performScrollTo()
                .assertIsOn().performClick().assertIsOff()
            waitForText("a win scores 95 instead of 100 points", substring = true)
            waitForText("matching fireworks or glass cue", substring = true)
            waitForText("Sound effects setting remains", substring = true)
            waitForText("not sent to BB_Games", substring = true)
            waitForText("Android backup may include these settings", substring = true)

            compose.onNodeWithText("Back").performClick()
            waitForTag("home_options")
            compose.activityRule.scenario.recreate()
            waitForTag("home_options")
            compose.onNodeWithTag("home_options").performScrollTo().performClick()

            compose.onNodeWithTag("option_sound_effects").assertIsOff()
            compose.onNodeWithTag("option_board_coordinates").assertIsOff()
            compose.onNodeWithTag("option_threat_indication").assertIsOn()
            compose.onNodeWithTag("option_celebration_effects").performScrollTo().assertIsOff()
        } finally {
            store.save(defaults)
        }
    }

    private fun waitForTag(value: String) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(value: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dismissRulesGuideIfShown() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("home_options").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Got it").performClick()
        }
    }
}
