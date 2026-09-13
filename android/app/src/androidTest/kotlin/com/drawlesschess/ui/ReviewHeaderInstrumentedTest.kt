package com.drawlesschess.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewHeaderInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun phoneTitleIsCenteredBetweenVisibleButtonsWithLargeClickableTargets() {
        var saveClicks = 0
        var flipClicks = 0
        showHeader(
            width = 388,
            onSaveAndExit = { saveClicks += 1 },
            onFlip = { flipClicks += 1 },
        )

        val header = bounds("review_header_host")
        val title = bounds("review_header_title")
        val save = bounds("review_save_exit")
        val flip = bounds("review_flip")
        assertTrue(abs(title.center.x - header.center.x) <= 1f)
        assertTrue(save.right + 8f <= title.left)
        assertTrue(title.right + 8f <= flip.left)
        assertTrue(abs(save.center.y - title.center.y) <= 1f)
        assertTrue(abs(flip.center.y - title.center.y) <= 1f)

        assertAction("review_save_exit")
        assertAction("review_flip")
        assertLabelFits("Save & exit", action = true)
        assertLabelFits("Flip", action = true)
        // Touch near both controls' outer edges, rather than invoking semantics only.
        compose.onNodeWithTag("review_save_exit").performTouchInput {
            click(Offset(4f, center.y))
        }
        compose.onNodeWithTag("review_flip").performTouchInput {
            click(Offset(center.x * 2f - 4f, center.y))
        }
        compose.runOnIdle {
            assertEquals(1, saveClicks)
            assertEquals(1, flipClicks)
        }
    }

    @Test
    fun narrowPanelPlacesCenteredTitleAboveBothReadableActions() {
        showHeader(width = 240)

        val header = bounds("review_header_host")
        val title = bounds("review_header_title")
        val save = bounds("review_save_exit")
        val flip = bounds("review_flip")
        assertTrue(abs(title.center.x - header.center.x) <= 1f)
        assertTrue(title.bottom < save.top)
        assertTrue(title.bottom < flip.top)
        assertTrue(save.right < flip.left)
        assertAction("review_save_exit")
        assertAction("review_flip")
        assertLabelFits("Save & exit", action = true)
        assertLabelFits("Flip", action = true)
    }

    @Test
    fun doubleFontAndLongTranslationsWrapWithoutClippingOrOverlappingActions() {
        val titleText = "Analyse de la partie"
        val saveText = "Enregistrer et quitter"
        val flipText = "Retourner l’échiquier"
        showHeader(
            width = 320,
            fontScale = 2f,
            title = titleText,
            saveLabel = saveText,
            flipLabel = flipText,
        )

        val header = bounds("review_header_host")
        val title = bounds("review_header_title")
        val save = bounds("review_save_exit")
        val flip = bounds("review_flip")
        assertTrue(abs(title.center.x - header.center.x) <= 1f)
        assertTrue(title.bottom < save.top)
        assertTrue(save.bottom < flip.top)
        listOf(title, save, flip).forEach { bounds ->
            assertTrue(bounds.left >= header.left && bounds.right <= header.right)
            assertTrue(bounds.top >= header.top && bounds.bottom <= header.bottom)
        }
        assertAction("review_save_exit")
        assertAction("review_flip")
        assertLabelFits(titleText)
        assertLabelFits(saveText, action = true)
        assertLabelFits(flipText, action = true)
    }

    private fun showHeader(
        width: Int,
        fontScale: Float = 1f,
        title: String = "Review",
        saveLabel: String = "Save & exit",
        flipLabel: String = "Flip",
        onSaveAndExit: () -> Unit = {},
        onFlip: () -> Unit = {},
    ) {
        compose.setContent {
            val darkConfiguration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalConfiguration provides darkConfiguration,
            ) {
                DrawlessTheme {
                    Surface {
                        Box(Modifier.width(width.dp)) {
                            ReviewHeader(
                                title = title,
                                saveAndExitLabel = saveLabel,
                                flipLabel = flipLabel,
                                onSaveAndExit = onSaveAndExit,
                                onFlip = onFlip,
                                modifier = Modifier.testTag("review_header_host"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag)
        .assertIsDisplayed()
        .fetchSemanticsNode().boundsInRoot

    private fun assertAction(tag: String) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(64.dp)
    }

    private fun assertLabelFits(text: String, action: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        val layout = layouts.single()
        assertFalse(
            "$text overflow: size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "lines=${layout.lineCount}, width=${layout.didOverflowWidth}, " +
                "height=${layout.didOverflowHeight}, paragraphHeight=${layout.multiParagraph.height}",
            layout.hasVisualOverflow,
        )
        if (action) {
            assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)
            assertEquals(FontWeight.SemiBold, layouts.single().layoutInput.style.fontWeight)
        }
    }
}
