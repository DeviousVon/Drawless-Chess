package com.drawlesschess.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.chess.Square
import com.drawlesschess.core.coordinator.CoordinatorPhase
import com.drawlesschess.core.presentation.BoardInteractionState
import com.drawlesschess.core.presentation.BoardScreenState
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardThemes
import com.drawlesschess.core.presentation.PieceSets
import com.drawlesschess.core.presentation.PieceView
import com.drawlesschess.core.presentation.SquareView
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class ThreatIndicatorVisualInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactThreatBadgeStaysAboveLargePiecesAcrossThemes() {
        val pieceTypes = listOf(PieceType.QUEEN, PieceType.BISHOP, PieceType.KING)
        val sides = listOf(Side.WHITE, Side.BLACK)
        compose.setContent {
            DrawlessTheme {
                val deviceDensity = LocalDensity.current.density
                Column(
                    modifier = Modifier
                        .width(286.dp)
                        .background(Color(0xFF11151A))
                        .padding(8.dp)
                        .testTag("threat_indicator_evidence"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Threat badge · 36 dp · 2× font",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    BoardThemes.all.forEach { theme ->
                        DrawlessTheme(theme) {
                            CompositionLocalProvider(
                                LocalDensity provides Density(deviceDensity, fontScale = 2f),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    sides.forEach { side ->
                                        pieceTypes.forEach { type ->
                                            ThreatSquareFixture(theme, side, type)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDirectory = targetContext.getExternalFilesDir(null)
            ?: error("External evidence directory is unavailable")
        val evidenceOutput = outputDirectory.resolve("threat-indicator-layer-evidence.png")
        writePng(
            compose.onNodeWithTag("threat_indicator_evidence")
                .captureToImage()
                .asAndroidBitmap(),
            evidenceOutput,
        )
        val lowDensityFixtureOutput = outputDirectory.resolve(
            "threat-indicator-obsidian-white-queen.png",
        )
        writePng(
            compose.onNodeWithTag(
                fixtureTag(BoardThemes.OBSIDIAN_GLASS, Side.WHITE, PieceType.QUEEN),
                useUnmergedTree = true,
            ).captureToImage().asAndroidBitmap(),
            lowDensityFixtureOutput,
        )
        Log.i(
            "DrawlessThreatVisual",
            "pre_assert_evidence=${evidenceOutput.absolutePath} bytes=${evidenceOutput.length()} " +
                "fixture=${lowDensityFixtureOutput.absolutePath} bytes=${lowDensityFixtureOutput.length()}",
        )
        val isDark = targetContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        BoardThemes.all.forEach { theme ->
            val visualTheme = DrawlessVisualThemes.fromBoardTheme(theme)
            val colors = if (isDark) visualTheme.darkColors else visualTheme.lightColors
            sides.forEach { side ->
                val silhouettes = mutableMapOf<PieceType, Bitmap>()
                pieceTypes.forEach { type ->
                    val tag = fixtureTag(theme, side, type)
                    val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
                        .captureToImage()
                        .asAndroidBitmap()
                    silhouettes[type] = bitmap
                    assertBadgeInteriorVisible(
                        bitmap = bitmap,
                        badgeColor = colors.tertiary.toArgb(),
                        markColor = colors.onTertiary.toArgb(),
                        label = "${theme.id} ${side.name.lowercase()} ${type.name.lowercase()}",
                    )
                    assertPieceSilhouetteVisible(
                        bitmap = bitmap,
                        squareColor = theme.darkSquare.value.toInt(),
                        label = "${theme.id} ${side.name.lowercase()} ${type.name.lowercase()}",
                    )
                }
                assertSilhouettesDistinct(
                    first = requireNotNull(silhouettes[PieceType.QUEEN]),
                    second = requireNotNull(silhouettes[PieceType.BISHOP]),
                    label = "${theme.id} ${side.name.lowercase()} queen/bishop",
                )
                assertSilhouettesDistinct(
                    first = requireNotNull(silhouettes[PieceType.BISHOP]),
                    second = requireNotNull(silhouettes[PieceType.KING]),
                    label = "${theme.id} ${side.name.lowercase()} bishop/king",
                )
                assertSilhouettesDistinct(
                    first = requireNotNull(silhouettes[PieceType.QUEEN]),
                    second = requireNotNull(silhouettes[PieceType.KING]),
                    label = "${theme.id} ${side.name.lowercase()} queen/king",
                )
            }
        }

        val badges = compose.onAllNodesWithTag("threat_badge_a1", useUnmergedTree = true)
            .fetchSemanticsNodes()
        val ranks = compose.onAllNodesWithTag("board_rank_a1", useUnmergedTree = true)
            .fetchSemanticsNodes()
        val files = compose.onAllNodesWithTag("board_file_a1", useUnmergedTree = true)
            .fetchSemanticsNodes()
        val fixtureCount = BoardThemes.all.size * sides.size * pieceTypes.size
        assertEquals(fixtureCount, badges.size)
        assertEquals(fixtureCount, ranks.size)
        assertEquals(fixtureCount, files.size)
        BoardThemes.all.forEach { theme ->
            sides.forEach { side ->
                pieceTypes.forEach { type ->
                    val fixture = compose.onNodeWithTag(
                        fixtureTag(theme, side, type),
                        useUnmergedTree = true,
                    ).fetchSemanticsNode().boundsInRoot
                    val badge = badges.single { fixture.contains(it.boundsInRoot.center) }.boundsInRoot
                    val rank = ranks.single { fixture.contains(it.boundsInRoot.center) }.boundsInRoot
                    val file = files.single { fixture.contains(it.boundsInRoot.center) }.boundsInRoot
                    assertTrue("Threat badge is too large for $theme $side $type", badge.width <= fixture.width * 0.36f)
                    assertTrue(badge.height <= fixture.height * 0.36f)
                    assertTrue("Threat badge left its top-right anchor", badge.center.x > fixture.center.x)
                    assertTrue(badge.center.y < fixture.center.y)
                    assertTrue("Rank label left its top-left anchor", rank.center.x < fixture.center.x)
                    assertTrue("File label left its bottom-right anchor", file.center.y > fixture.center.y)
                    assertTrue("Warning and file anchors collapsed", badge.center.y < file.center.y)
                    assertTrue("Warning and rank anchors collapsed", badge.center.x > rank.center.x)
                    assertFalse(
                        "Threat badge overlaps the rank label at 2× font: $badge vs $rank",
                        intersects(badge, rank),
                    )
                    assertFalse(
                        "Threat badge overlaps the file label at 2× font: $badge vs $file",
                        intersects(badge, file),
                    )
                }
            }
        }

        assertTrue(evidenceOutput.length() > 10_000)
        assertTrue(lowDensityFixtureOutput.length() > 500)
    }

    private fun assertBadgeInteriorVisible(
        bitmap: Bitmap,
        badgeColor: Int,
        markColor: Int,
        label: String,
    ) {
        val left = (bitmap.width * 0.72f).toInt()
        val right = (bitmap.width * 0.89f).toInt().coerceAtLeast(left + 1)
        val top = (bitmap.height * 0.11f).toInt()
        val bottom = (bitmap.height * 0.28f).toInt().coerceAtLeast(top + 1)
        var badgePixels = 0
        var markPixels = 0
        var total = 0
        var closestMarkDistance = Int.MAX_VALUE
        var closestMarkPixel = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (colorDistance(pixel, badgeColor) <= 36) badgePixels += 1
            val markDistance = colorDistance(pixel, markColor)
            if (markDistance <= 72) markPixels += 1
            if (markDistance < closestMarkDistance) {
                closestMarkDistance = markDistance
                closestMarkPixel = pixel
            }
            total += 1
        }
        assertTrue(
            "$label threat badge was occluded: badge=$badgePixels mark=$markPixels total=$total",
            badgePixels + markPixels >= total * 0.78f,
        )
        assertTrue(
            "$label threat mark was not visible: mark=$markPixels total=$total " +
                "closestDistance=$closestMarkDistance closestPixel=${closestMarkPixel.toUInt().toString(16)} " +
                "expected=${markColor.toUInt().toString(16)}",
            markPixels >= total * 0.01f,
        )
    }

    private fun assertPieceSilhouetteVisible(bitmap: Bitmap, squareColor: Int, label: String) {
        val left = (bitmap.width * 0.15f).toInt()
        val right = (bitmap.width * 0.72f).toInt().coerceAtLeast(left + 1)
        val top = (bitmap.height * 0.15f).toInt()
        val bottom = (bitmap.height * 0.88f).toInt().coerceAtLeast(top + 1)
        var silhouettePixels = 0
        var total = 0
        for (y in top until bottom) for (x in left until right) {
            if (colorDistance(bitmap.getPixel(x, y), squareColor) >= 72) silhouettePixels += 1
            total += 1
        }
        assertTrue(
            "$label piece silhouette is not materially visible: $silhouettePixels/$total",
            silhouettePixels >= total * 0.12f,
        )
    }

    private fun assertSilhouettesDistinct(first: Bitmap, second: Bitmap, label: String) {
        val left = (first.width * 0.18f).toInt()
        val right = (first.width * 0.72f).toInt().coerceAtLeast(left + 1)
        val top = (first.height * 0.10f).toInt()
        val bottom = (first.height * 0.70f).toInt().coerceAtLeast(top + 1)
        var distinctPixels = 0
        var total = 0
        for (y in top until bottom) for (x in left until right) {
            if (colorDistance(first.getPixel(x, y), second.getPixel(x, y)) >= 54) {
                distinctPixels += 1
            }
            total += 1
        }
        assertTrue(
            "$label silhouettes became too similar: $distinctPixels/$total",
            distinctPixels >= total * 0.025f,
        )
    }

    private fun intersects(
        first: androidx.compose.ui.geometry.Rect,
        second: androidx.compose.ui.geometry.Rect,
    ): Boolean = first.left < second.right && first.right > second.left &&
        first.top < second.bottom && first.bottom > second.top

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xFF) - (second shr 16 and 0xFF)) +
            abs((first shr 8 and 0xFF) - (second shr 8 and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))

    private fun writePng(bitmap: Bitmap, output: java.io.File) {
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}

@Composable
private fun ThreatSquareFixture(theme: BoardTheme, side: Side, type: PieceType) {
    val square = Square.parse("a1")
    val position = ChessPosition.starting()
    val cell = SquareView(
        square = square,
        displayRow = 7,
        displayColumn = 0,
        piece = PieceView(side, type, "visual-test"),
        selected = false,
        target = null,
        lastMove = false,
        inCheck = false,
        threatened = true,
        accessibilityLabel = "${side.name.lowercase()} ${type.name.lowercase()} on a1, under threat",
    )
    val board = BoardScreenState(
        positionMarker = position.fen(),
        plyCount = 0,
        humanSide = side,
        sideToMove = side,
        cells = listOf(cell),
        interaction = BoardInteractionState.initial(position, side),
        interactive = false,
        phase = CoordinatorPhase.HUMAN_TURN,
        statusText = "Visual test",
        theme = theme,
        pieceSet = PieceSets.MODERN_FLAT,
        promotionPrompt = null,
        moveMotion = null,
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .testTag(fixtureTag(theme, side, type)),
    ) {
        SquareCell(
            cell = cell,
            board = board,
            hidePiece = false,
            inputEnabled = false,
            showCoordinates = true,
            onClick = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun fixtureTag(theme: BoardTheme, side: Side, type: PieceType): String =
    "threat_fixture_${theme.id}_${side.name.lowercase()}_${type.name.lowercase()}"
