package com.drawlesschess.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drawlesschess.MainActivity
import org.junit.Rule
import org.junit.Test

class HomeLandscapeInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun landscapeKeepsBrandingAndPrimaryActionVisibleTogether() {
        dismissRulesGuideIfShown()

        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithTag("home_brand_hero").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("home_quick_play").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("home_brand_hero").assertIsDisplayed()
        compose.onNodeWithTag("home_quick_play").assertIsDisplayed()
    }

    private fun dismissRulesGuideIfShown() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("home_brand_hero").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Got it").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Got it").performClick()
        }
    }
}
