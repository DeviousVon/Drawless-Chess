package com.drawlesschess.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ThemePickerTextureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allThemesAreVisibleAndSelectionImmediatelyDismissesThePicker() {
        val selected = mutableStateOf(BoardThemes.AMETHYST_GEODE)
        val showPicker = mutableStateOf(true)
        compose.setContent {
            DrawlessTheme(selected.value) {
                if (showPicker.value) {
                    ThemePickerDialog(
                        selectedTheme = selected.value,
                        onSelect = { selected.value = it },
                        onDismiss = { showPicker.value = false },
                    )
                }
            }
        }

        compose.onNodeWithTag("theme_option_amethyst_geode").assertIsSelected()
        listOf(
            "imperial_marble",
            "desert_sandstone",
            "glacier_slate",
            "verdigris_copper",
            "amethyst_geode",
        ).forEach { themeId ->
            compose.onNodeWithTag("theme_option_$themeId").assertIsDisplayed()
        }
        compose.onNodeWithTag("theme_option_desert_sandstone")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(BoardThemes.DESERT_SANDSTONE, selected.value)
            showPicker.value = true
        }
        compose.onNodeWithTag("theme_option_imperial_marble")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(BoardThemes.IMPERIAL_MARBLE, selected.value)
        }
    }

    @Test
    fun boardSelectionDoesNotChangeAppChromeColors() {
        var sandstonePrimary = Color.Unspecified
        var sandstoneBackground = Color.Unspecified
        var sandstoneBoardId = ""
        var amethystPrimary = Color.Unspecified
        var amethystBackground = Color.Unspecified
        var amethystBoardId = ""

        compose.setContent {
            DrawlessTheme(BoardThemes.DESERT_SANDSTONE) {
                val visualTheme = LocalDrawlessVisualTheme.current
                val appColors = MaterialTheme.colorScheme
                SideEffect {
                    sandstonePrimary = appColors.primary
                    sandstoneBackground = appColors.background
                    sandstoneBoardId = visualTheme.boardTheme.id
                }
            }
            DrawlessTheme(BoardThemes.AMETHYST_GEODE) {
                val visualTheme = LocalDrawlessVisualTheme.current
                val appColors = MaterialTheme.colorScheme
                SideEffect {
                    amethystPrimary = appColors.primary
                    amethystBackground = appColors.background
                    amethystBoardId = visualTheme.boardTheme.id
                }
            }
        }

        compose.runOnIdle {
            assertEquals(sandstonePrimary, amethystPrimary)
            assertEquals(sandstoneBackground, amethystBackground)
            assertNotEquals(sandstoneBoardId, amethystBoardId)
        }
    }
}
