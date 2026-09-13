package com.drawlesschess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.R
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemePickerScrollingInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portraitSwipesReachAndSelectBothHalloweenThemes() {
        assertHalloweenThemesReachable(width = 320, height = 480, fontScale = 1f)
    }

    @Test
    fun portraitSwipesReachAndSelectBothHalloweenThemesAtDoubleFontScale() {
        assertHalloweenThemesReachable(width = 320, height = 480, fontScale = 2f)
    }

    @Test
    fun shortLandscapeSwipesReachAndSelectBothHalloweenThemes() {
        assertHalloweenThemesReachable(width = 640, height = 320, fontScale = 1f)
    }

    @Test
    fun shortLandscapeSwipesReachAndSelectBothHalloweenThemesAtDoubleFontScale() {
        assertHalloweenThemesReachable(width = 640, height = 320, fontScale = 2f)
    }

    @Test
    fun closeButtonDismissesWithoutSelectingATheme() {
        val showPicker = mutableStateOf(true)
        var selections = 0
        var dismissals = 0
        compose.setContent {
            DrawlessTheme {
                if (showPicker.value) {
                    ThemePickerContent(
                        selectedTheme = BoardThemes.IMPERIAL_MARBLE,
                        onSelect = { selections += 1 },
                        onDismiss = {
                            dismissals += 1
                            showPicker.value = false
                        },
                        modifier = Modifier.size(width = 320.dp, height = 480.dp),
                    )
                }
            }
        }

        compose.onNodeWithTag("theme_picker_close")
            .assertIsDisplayed()
            .performTouchInput { click() }
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun realDialogBackDismissesWithoutSelectingATheme() {
        val showPicker = mutableStateOf(true)
        var selections = 0
        var dismissals = 0
        compose.setContent {
            DrawlessTheme {
                if (showPicker.value) {
                    ThemePickerDialog(
                        selectedTheme = BoardThemes.IMPERIAL_MARBLE,
                        onSelect = { selections += 1 },
                        onDismiss = {
                            dismissals += 1
                            showPicker.value = false
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag("theme_picker").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals(1, dismissals)
        }
    }

    private fun assertHalloweenThemesReachable(width: Int, height: Int, fontScale: Float) {
        val selected = mutableStateOf(BoardThemes.IMPERIAL_MARBLE)
        val showPicker = mutableStateOf(true)
        var selections = 0
        var dismissals = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                DrawlessTheme(selected.value) {
                    Box(Modifier.size(width = width.dp, height = height.dp)) {
                        if (showPicker.value) {
                            ThemePickerContent(
                                selectedTheme = selected.value,
                                onSelect = {
                                    selections += 1
                                    selected.value = it
                                },
                                onDismiss = {
                                    dismissals += 1
                                    showPicker.value = false
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        listOf(BoardThemes.HALLOWEEN_EMBERWOOD, BoardThemes.HALLOWEEN_WITCHGLASS)
            .forEachIndexed { index, theme ->
                if (index > 0) compose.runOnIdle { showPicker.value = true }
                assertHeaderVisible()
                compose.onNodeWithTag("theme_scroll_indicator").assertIsDisplayed()
                val scroller = compose.onNodeWithTag("theme_options_scroller")
                scroller.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
                val initialScroll = scroller.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()

                swipeUntilThemeVisible(theme.id)

                val currentScroll = scroller.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("Touch swipes must scroll to ${theme.id}", currentScroll > initialScroll)
                assertHeaderVisible()
                compose.onNodeWithTag("theme_scroll_indicator").assertIsDisplayed()
                val viewport = scroller.fetchSemanticsNode().boundsInRoot
                val option = compose.onNodeWithTag("theme_option_${theme.id}")
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val visibleTop = maxOf(option.top, viewport.top)
                val visibleBottom = minOf(option.bottom, viewport.bottom)
                // Tap the visible row through the same touch region that handled the swipes.
                // A semantic performClick could activate a row even if it remained off-screen.
                val tap = Offset(
                    x = option.center.x - viewport.left,
                    y = (visibleTop + visibleBottom) / 2f - viewport.top,
                )
                scroller.performTouchInput { click(tap) }
                compose.onNodeWithTag("theme_picker").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(theme, selected.value)
                    assertEquals(index + 1, selections)
                    assertEquals(index + 1, dismissals)
                }
            }
    }

    private fun swipeUntilThemeVisible(themeId: String) {
        val scroller = compose.onNodeWithTag("theme_options_scroller")
        val option = compose.onNodeWithTag("theme_option_$themeId")
        repeat(30) {
            val viewport = scroller.fetchSemanticsNode().boundsInRoot
            val target = option.fetchSemanticsNode().boundsInRoot
            val visibleHeight = minOf(target.bottom, viewport.bottom) - maxOf(target.top, viewport.top)
            if (visibleHeight >= 32f) return
            scroller.performTouchInput { swipeUp(durationMillis = 400L) }
            compose.waitForIdle()
            assertHeaderVisible()
        }
        throw AssertionError("Thirty touch swipes did not reveal $themeId")
    }

    private fun assertHeaderVisible() {
        val title = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.theme_choose)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithTag("theme_picker_close").assertIsDisplayed()
    }
}
