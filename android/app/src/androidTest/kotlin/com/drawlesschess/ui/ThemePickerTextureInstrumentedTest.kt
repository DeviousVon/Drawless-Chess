package com.drawlesschess.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.BoardTextureIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThemePickerTextureInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allThemesAreVisibleAndSelectionImmediatelyDismissesThePicker() {
        val selected = mutableStateOf(BoardThemes.CELESTIAL_OBSERVATORY)
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

        compose.onNodeWithTag("theme_option_celestial_observatory").assertIsSelected()
        listOf(
            "imperial_marble",
            "desert_sandstone",
            "glacier_slate",
            "verdigris_copper",
            "celestial_observatory",
            "halloween_emberwood",
            "halloween_witchglass",
        ).forEach { themeId ->
            compose.onNodeWithTag("theme_option_$themeId").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("theme_option_halloween_emberwood")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(BoardThemes.HALLOWEEN_EMBERWOOD, selected.value)
            showPicker.value = true
        }
        compose.onNodeWithTag("theme_option_halloween_witchglass")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("theme_picker").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(BoardThemes.HALLOWEEN_WITCHGLASS, selected.value)
        }
    }

    @Test
    fun celestialEngravingsAreCachedAndRetainClearSquareContrast() {
        val light = requireNotNull(textureBitmap(BoardTextureIds.CELESTIAL_OBSERVATORY, true, 0, 1, 72))
        val dark = requireNotNull(textureBitmap(BoardTextureIds.CELESTIAL_OBSERVATORY, false, 1, 1, 72))
        assertTrue(light === textureBitmap(BoardTextureIds.CELESTIAL_OBSERVATORY, true, 0, 1, 72))
        val lightBitmap = light.asAndroidBitmap()
        val darkBitmap = dark.asAndroidBitmap()
        var lightTotal = 0L
        var darkTotal = 0L
        repeat(72) { y ->
            repeat(72) { x ->
                val lightPixel = lightBitmap.getPixel(x, y)
                val darkPixel = darkBitmap.getPixel(x, y)
                lightTotal += (lightPixel ushr 16 and 0xFF) + (lightPixel ushr 8 and 0xFF) + (lightPixel and 0xFF)
                darkTotal += (darkPixel ushr 16 and 0xFF) + (darkPixel ushr 8 and 0xFF) + (darkPixel and 0xFF)
            }
        }
        assertTrue("Engraving must preserve the light/dark board separation", lightTotal > darkTotal * 3)
        assertNotNull(textureBitmap(BoardTextureIds.CELESTIAL_OBSERVATORY, false, 7, 0, 27))
    }

    @Test
    fun halloweenMaterialsAreDistinctCachedAndKeepSquareContrast() {
        val samples = listOf(BoardTextureIds.EMBERWOOD, BoardTextureIds.WITCHGLASS).map { texture ->
            val light = requireNotNull(textureBitmap(texture, true, 0, 1, 72))
            val dark = requireNotNull(textureBitmap(texture, false, 1, 1, 72))
            assertTrue(light === textureBitmap(texture, true, 0, 1, 72))
            fun brightness(image: androidx.compose.ui.graphics.ImageBitmap): Long {
                val bitmap = image.asAndroidBitmap()
                var sum = 0L
                repeat(bitmap.height) { y -> repeat(bitmap.width) { x ->
                    val pixel = bitmap.getPixel(x, y)
                    sum += (pixel ushr 16 and 255) * 2126L + (pixel ushr 8 and 255) * 7152L + (pixel and 255) * 722L
                } }
                return sum
            }
            assertTrue("$texture must retain a clear checkerboard", brightness(light) > brightness(dark) * 2)
            assertNotNull(textureBitmap(texture, false, 7, 0, 18))
            light.asAndroidBitmap()
        }
        assertTrue("Halloween candidates must have distinct materials", !samples[0].sameAs(samples[1]))
    }

    @Test
    fun boardSelectionDoesNotChangeAppChromeColors() {
        var sandstonePrimary = Color.Unspecified
        var sandstoneBackground = Color.Unspecified
        var sandstoneBoardId = ""
        var celestialPrimary = Color.Unspecified
        var celestialBackground = Color.Unspecified
        var celestialBoardId = ""

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
            DrawlessTheme(BoardThemes.CELESTIAL_OBSERVATORY) {
                val visualTheme = LocalDrawlessVisualTheme.current
                val appColors = MaterialTheme.colorScheme
                SideEffect {
                    celestialPrimary = appColors.primary
                    celestialBackground = appColors.background
                    celestialBoardId = visualTheme.boardTheme.id
                }
            }
        }

        compose.runOnIdle {
            assertEquals(sandstonePrimary, celestialPrimary)
            assertEquals(sandstoneBackground, celestialBackground)
            assertNotEquals(sandstoneBoardId, celestialBoardId)
        }
    }
}
