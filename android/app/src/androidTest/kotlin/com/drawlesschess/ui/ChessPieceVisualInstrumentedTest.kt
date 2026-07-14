package com.drawlesschess.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.PieceType
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class ChessPieceVisualInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bishopReadsDifferentlyFromPawnAtEveryCompactIconSize() {
        val background = Color(0xFFFF00FF)
        val sizes = listOf(14, 18, 24)
        compose.setContent {
            DrawlessTheme {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    sizes.forEach { size ->
                        listOf(PieceType.BISHOP, PieceType.PAWN, PieceType.KING).forEach { type ->
                            ChessPiece(
                                side = Side.WHITE,
                                type = type,
                                modifier = Modifier
                                    .size(size.dp)
                                    .background(background)
                                    .testTag("${type.name.lowercase()}_$size"),
                            )
                        }
                    }
                }
            }
        }

        val palette = DrawlessVisualThemes.DEFAULT.pieces
        sizes.forEach { size ->
            val bishop = compose.onNodeWithTag("bishop_$size").captureToImage().asAndroidBitmap()
            val pawn = compose.onNodeWithTag("pawn_$size").captureToImage().asAndroidBitmap()
            val king = compose.onNodeWithTag("king_$size").captureToImage().asAndroidBitmap()
            val bishopMask = silhouette(bishop, background.toArgb())
            val pawnMask = silhouette(pawn, background.toArgb())

            assertNoEdgeClipping(bishopMask, bishop.width, bishop.height)
            assertNoTopOrSideClipping(pawnMask, pawn.width, pawn.height)
            assertNoTopOrSideClipping(
                silhouette(king, background.toArgb()),
                king.width,
                king.height,
            )

            val bishopTop = firstOccupiedRow(bishopMask, bishop.width, bishop.height)
            val pawnTop = firstOccupiedRow(pawnMask, pawn.width, pawn.height)
            assertTrue("The ${size}dp bishop must have a visibly taller mitre", bishopTop < pawnTop)

            val collarRow = (bishop.height * 0.60f).toInt()
            val bishopCollar = occupiedWidth(bishopMask, bishop.width, collarRow)
            val pawnStem = occupiedWidth(pawnMask, pawn.width, collarRow)
            assertTrue(
                "The ${size}dp bishop collar must flare beyond the pawn stem: $bishopCollar <= $pawnStem",
                bishopCollar >= pawnStem + (bishop.width * 0.10f).toInt().coerceAtLeast(2),
            )

            // Ignore the common weighted base and compare the identifying crown/neck/collar.
            val overlap = intersectionOverUnion(
                bishopMask,
                pawnMask,
                width = bishop.width,
                comparedHeight = (bishop.height * 0.68f).toInt(),
            )
            assertTrue("The ${size}dp bishop/pawn silhouettes are too similar: IoU=$overlap", overlap < 0.72)

            val detailPixels = countNearColor(bishop, palette.whiteDetail.toArgb(), tolerance = 54)
            val kingAccentPixels = countNearColor(king, palette.whiteKingAccent.toArgb(), tolerance = 54)
            Log.i(
                "ChessPieceVisual",
                "size_dp=$size pixels=${bishop.width} bishop_top=$bishopTop pawn_top=$pawnTop " +
                    "bishop_collar=$bishopCollar pawn_stem=$pawnStem upper_iou=$overlap " +
                    "bishop_detail_pixels=$detailPixels king_accent_pixels=$kingAccentPixels",
            )
            assertTrue("The ${size}dp bishop mitre cut disappeared", detailPixels >= size / 2)
            assertTrue(
                "The ${size}dp king lost its colored cross: pixels=$kingAccentPixels",
                kingAccentPixels >= maxOf(4, size / 4),
            )
        }
    }

    @Test
    fun visualEvidenceCoversThemesBoardScaleAndAccessibility() {
        compose.setContent {
            DrawlessTheme {
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .background(Color(0xFF0A0E15))
                        .padding(10.dp)
                        .testTag("piece_evidence_sheet"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Bishop · pawn · king legibility",
                        color = Color(0xFFF2F5F6),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    listOf(
                        DrawlessVisualThemes.OBSIDIAN_GLASS,
                        DrawlessVisualThemes.MODERN_WALNUT,
                        DrawlessVisualThemes.ROYAL_AMETHYST,
                    ).forEach { visualTheme ->
                        CompositionLocalProvider(LocalDrawlessVisualTheme provides visualTheme) {
                            EvidenceThemeSection(visualTheme)
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("accessible_bishop_obsidian_glass")
            .assertContentDescriptionEquals("Black bishop")
        val evidence = compose.onNodeWithTag("piece_evidence_sheet")
        val evidenceBounds = evidence.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Evidence node has empty bounds: $evidenceBounds",
            evidenceBounds.width > 0f && evidenceBounds.height > 0f,
        )
        val bitmap = evidence.captureToImage().asAndroidBitmap()
        assertTrue(
            "Evidence capture is empty: ${bitmap.width}x${bitmap.height}",
            bitmap.width > 0 && bitmap.height > 0,
        )
        val sheetBackground = Color(0xFF0A0E15).toArgb()
        val evidencePixels = countFarFromColor(bitmap, sheetBackground, tolerance = 30)
        assertTrue(
            "Evidence capture contains too little rendered content: $evidencePixels of " +
                "${bitmap.width * bitmap.height} pixels",
            evidencePixels >= (bitmap.width * bitmap.height * 0.05f).toInt(),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val outputDirectory = targetContext.getExternalFilesDir(null)
            ?: error("External evidence directory is unavailable")
        val output = outputDirectory.resolve("chess-piece-visual-evidence.png")
        FileOutputStream(output).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue("Evidence PNG is empty", output.length() > 0)
        Log.i(
            "ChessPieceVisual",
            "evidence=${output.absolutePath} bounds=$evidenceBounds " +
                "bitmap=${bitmap.width}x${bitmap.height} content_pixels=$evidencePixels " +
                "bytes=${output.length()}",
        )
    }

    private fun silhouette(bitmap: Bitmap, background: Int): BooleanArray =
        BooleanArray(bitmap.width * bitmap.height) { index ->
            val x = index % bitmap.width
            val y = index / bitmap.width
            colorDistance(bitmap.getPixel(x, y), background) > 42
        }

    private fun assertNoEdgeClipping(mask: BooleanArray, width: Int, height: Int) {
        val top = (0 until width).count { x -> mask[x] }
        val bottom = (0 until width).count { x -> mask[(height - 1) * width + x] }
        val left = (0 until height).count { y -> mask[y * width] }
        val right = (0 until height).count { y -> mask[y * width + width - 1] }
        assertTrue(
            "Piece touches capture edge: ${width}x$height top=$top bottom=$bottom left=$left right=$right",
            top == 0 && bottom == 0 && left == 0 && right == 0,
        )
    }

    private fun assertNoTopOrSideClipping(mask: BooleanArray, width: Int, height: Int) {
        val top = (0 until width).count { x -> mask[x] }
        val left = (0 until height).count { y -> mask[y * width] }
        val right = (0 until height).count { y -> mask[y * width + width - 1] }
        assertTrue(
            "Piece touches top/side capture edge: ${width}x$height top=$top left=$left right=$right",
            top == 0 && left == 0 && right == 0,
        )
    }

    private fun firstOccupiedRow(mask: BooleanArray, width: Int, height: Int): Int =
        (0 until height).first { row -> (0 until width).any { column -> mask[row * width + column] } }

    private fun occupiedWidth(mask: BooleanArray, width: Int, row: Int): Int {
        val occupied = (0 until width).filter { column -> mask[row * width + column] }
        return if (occupied.isEmpty()) 0 else occupied.last() - occupied.first() + 1
    }

    private fun intersectionOverUnion(
        first: BooleanArray,
        second: BooleanArray,
        width: Int,
        comparedHeight: Int,
    ): Double {
        assertEquals(first.size, second.size)
        var intersection = 0
        var union = 0
        repeat((width * comparedHeight).coerceAtMost(first.size)) { index ->
            if (first[index] && second[index]) intersection += 1
            if (first[index] || second[index]) union += 1
        }
        return intersection.toDouble() / union.coerceAtLeast(1)
    }

    private fun countNearColor(bitmap: Bitmap, target: Int, tolerance: Int): Int {
        var count = 0
        repeat(bitmap.height) { y ->
            repeat(bitmap.width) { x ->
                if (colorDistance(bitmap.getPixel(x, y), target) <= tolerance) count += 1
            }
        }
        return count
    }

    private fun countFarFromColor(bitmap: Bitmap, target: Int, tolerance: Int): Int {
        var count = 0
        repeat(bitmap.height) { y ->
            repeat(bitmap.width) { x ->
                if (colorDistance(bitmap.getPixel(x, y), target) > tolerance) count += 1
            }
        }
        return count
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs((first shr 16 and 0xFF) - (second shr 16 and 0xFF)) +
            abs((first shr 8 and 0xFF) - (second shr 8 and 0xFF)) +
            abs((first and 0xFF) - (second and 0xFF))
}

@Composable
private fun EvidenceThemeSection(visualTheme: DrawlessVisualTheme) {
    val lightSquare = Color(visualTheme.boardTheme.lightSquare.value)
    val darkSquare = Color(visualTheme.boardTheme.darkSquare.value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            visualTheme.boardTheme.name,
            color = Color(0xFFDCE4EA),
            style = MaterialTheme.typography.labelMedium,
        )
        listOf(Side.WHITE, Side.BLACK).forEach { side ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(14, 18, 24).forEach { size ->
                    listOf(PieceType.BISHOP, PieceType.PAWN, PieceType.KING).forEach { type ->
                        ChessPiece(
                            side = side,
                            type = type,
                            modifier = Modifier
                                .size(size.dp)
                                .background(if (side == Side.WHITE) darkSquare else lightSquare),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Side.WHITE, Side.BLACK).forEach { side ->
                listOf(PieceType.BISHOP, PieceType.PAWN, PieceType.KING).forEach { type ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (side == Side.WHITE) darkSquare else lightSquare)
                            .then(
                                if (side == Side.BLACK && type == PieceType.BISHOP) {
                                    Modifier
                                        .testTag("accessible_bishop_${visualTheme.boardTheme.id}")
                                        .semantics { contentDescription = "Black bishop" }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChessPiece(side, type, Modifier.fillMaxSize().padding(3.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(1.dp))
    }
}
