package com.drawlesschess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class BoardTextureVisualInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sameSquareTextureRendersIdenticallyAcrossFreshCacheInstances() {
        val themes = BoardThemes.all
        compose.setContent {
            Column {
                themes.forEach { theme ->
                    Row {
                        TextureFixture(theme, file = 4, rank = 3, tag = "${theme.id}_first")
                        TextureFixture(theme, file = 4, rank = 3, tag = "${theme.id}_second")
                        TextureFixture(theme, file = 5, rank = 3, tag = "${theme.id}_neighbor")
                    }
                }
            }
        }
        themes.forEach { theme ->
            val first = pixels("${theme.id}_first")
            assertArrayEquals(first, pixels("${theme.id}_second"))
            assertFalse(
                "Adjacent ${theme.id} squares unexpectedly reused the same stone cut",
                first.contentEquals(pixels("${theme.id}_neighbor")),
            )
        }
    }

    @Test
    fun generatedTexturesAreReusedBySquareAndSize() {
        val theme = BoardThemes.IMPERIAL_MARBLE
        val textureId = requireNotNull(theme.textureId)
        val first = textureBitmap(textureId, true, file = 4, rank = 3, requestedPx = 112)

        assertSame(first, textureBitmap(textureId, true, file = 4, rank = 3, requestedPx = 112))
        assertNotSame(first, textureBitmap(textureId, true, file = 5, rank = 3, requestedPx = 112))
        assertNotSame(first, textureBitmap(textureId, true, file = 4, rank = 3, requestedPx = 96))
    }

    private fun pixels(tag: String): IntArray {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { output ->
            bitmap.getPixels(output, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}

@androidx.compose.runtime.Composable
private fun TextureFixture(theme: BoardTheme, file: Int, rank: Int, tag: String) {
    val light = (file + rank) % 2 != 0
    val base = Color(if (light) theme.lightSquare.value else theme.darkSquare.value)
    Box(
        Modifier
            .size(72.dp)
            .background(base)
            .squareTexture(theme.textureId, light, file, rank)
            .testTag(tag),
    )
}
