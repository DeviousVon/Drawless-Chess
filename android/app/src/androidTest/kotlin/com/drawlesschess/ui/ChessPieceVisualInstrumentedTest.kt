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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.presentation.PieceStyleIds
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
                        listOf(PieceType.BISHOP, PieceType.PAWN, PieceType.KING, PieceType.QUEEN).forEach { type ->
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
            val queen = compose.onNodeWithTag("queen_$size").captureToImage().asAndroidBitmap()
            val bishopMask = silhouette(bishop, background.toArgb())
            val pawnMask = silhouette(pawn, background.toArgb())
            val kingMask = silhouette(king, background.toArgb())
            val queenMask = silhouette(queen, background.toArgb())

            assertNoEdgeClipping(bishopMask, bishop.width, bishop.height)
            assertNoTopOrSideClipping(pawnMask, pawn.width, pawn.height)
            assertNoTopOrSideClipping(kingMask, king.width, king.height)

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
            val comparedHeight = (bishop.height * 0.68f).toInt()
            val overlap = intersectionOverUnion(bishopMask, pawnMask, bishop.width, comparedHeight)
            assertTrue("The ${size}dp bishop/pawn silhouettes are too similar: IoU=$overlap", overlap < 0.72)

            // The supplied king remains separated from the bishop and pawn in silhouette alone.
            // The approved queen silhouette is intentionally retained; its four outlined jewels
            // provide the additional cue and therefore receive their own compact-raster gate.
            val kingBishopOverlap = intersectionOverUnion(kingMask, bishopMask, king.width, comparedHeight)
            val kingPawnOverlap = intersectionOverUnion(kingMask, pawnMask, king.width, comparedHeight)
            val kingQueenOverlap = intersectionOverUnion(kingMask, queenMask, king.width, comparedHeight)
            assertTrue(
                "The ${size}dp king/bishop silhouettes are too similar: IoU=$kingBishopOverlap",
                kingBishopOverlap < 0.72,
            )
            assertTrue(
                "The ${size}dp king/pawn silhouettes are too similar: IoU=$kingPawnOverlap",
                kingPawnOverlap < 0.72,
            )
            val detailPixels = countNearColor(bishop, palette.whiteDetail.toArgb(), tolerance = 54)
            val kingAccentPixels = countNearColor(king, palette.whiteKingAccent.toArgb(), tolerance = 54)
            val queenJewelPixels = countNearColor(
                queen,
                palette.whiteQueenAccent.toArgb(),
                tolerance = 54,
            )
            Log.i(
                "ChessPieceVisual",
                "size_dp=$size pixels=${bishop.width} bishop_top=$bishopTop pawn_top=$pawnTop " +
                    "bishop_collar=$bishopCollar pawn_stem=$pawnStem upper_iou=$overlap " +
                    "king_bishop_iou=$kingBishopOverlap king_pawn_iou=$kingPawnOverlap " +
                    "king_queen_iou=$kingQueenOverlap " +
                    "bishop_detail_pixels=$detailPixels king_accent_pixels=$kingAccentPixels " +
                    "queen_jewel_pixels=$queenJewelPixels",
            )
            assertTrue("The ${size}dp bishop mitre cut disappeared", detailPixels >= size / 2)
            assertTrue(
                "The ${size}dp king lost its accent circlet band: pixels=$kingAccentPixels",
                kingAccentPixels >= maxOf(4, size / 4),
            )
            assertTrue(
                "The ${size}dp queen lost its colored crown jewels: pixels=$queenJewelPixels",
                queenJewelPixels >= maxOf(4, size / 4),
            )
        }
    }

    @Test
    fun allHallowsStyleHasDistinctDeterministicOrnamentsForEveryPiece() {
        val palette = DrawlessVisualThemes.ALL_HALLOWS_COURT.pieces
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        PieceType.entries.forEach { type ->
            val classic = chessPieceRaster(
                Side.WHITE,
                type,
                palette,
                PieceStyleIds.MODERN_FLAT,
            ).asAndroidBitmap()
            val seasonal = chessPieceRaster(
                Side.WHITE,
                type,
                palette,
                PieceStyleIds.ALL_HALLOWS,
                resources,
            ).asAndroidBitmap()
            val repeated = chessPieceRaster(
                Side.WHITE,
                type,
                palette,
                PieceStyleIds.ALL_HALLOWS,
                resources,
            ).asAndroidBitmap()

            assertTrue("$type must have visible All Hallows ornamentation", !classic.sameAs(seasonal))
            assertTrue("$type All Hallows raster must be deterministic", seasonal.sameAs(repeated))
        }
    }

    @Test
    fun celestialInstrumentsHaveTransparentMarginsAndRemainDistinctAtCompactSizes() {
        val palette = DrawlessVisualThemes.CELESTIAL_OBSERVATORY.pieces
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        Side.entries.forEach { side ->
            val rasters = PieceType.entries.associateWith { type ->
                val instrument = chessPieceRaster(
                    side, type, palette, PieceStyleIds.CELESTIAL_OBSERVATORY, resources,
                ).asAndroidBitmap()
                val repeated = chessPieceRaster(
                    side, type, palette, PieceStyleIds.CELESTIAL_OBSERVATORY, resources,
                ).asAndroidBitmap()
                val fallback = chessPieceRaster(
                    side, type, palette, PieceStyleIds.CELESTIAL_OBSERVATORY,
                ).asAndroidBitmap()
                assertTrue("$side $type must use its instrument atlas", !instrument.sameAs(fallback))
                assertTrue("$side $type must be deterministic", instrument.sameAs(repeated))
                val mask = alphaSilhouette(instrument)
                assertNoEdgeClipping(mask, instrument.width, instrument.height)
                assertTrue("$side $type must contain visible sculpture", mask.count { it } > mask.size / 20)
                var greenPixels = 0
                repeat(instrument.height) { y ->
                    repeat(instrument.width) { x ->
                        val pixel = instrument.getPixel(x, y)
                        val alpha = pixel ushr 24 and 0xFF
                        val red = pixel ushr 16 and 0xFF
                        val green = pixel ushr 8 and 0xFF
                        val blue = pixel and 0xFF
                        if (alpha > 80 && green > maxOf(red, blue) * 1.4f + 30) greenPixels++
                    }
                }
                assertEquals("$side $type retained green backdrop", 0, greenPixels)
                instrument
            }
            listOf(14, 18, 24, 48).forEach { size ->
                val pawn = Bitmap.createScaledBitmap(rasters.getValue(PieceType.PAWN), size, size, true)
                val bishop = Bitmap.createScaledBitmap(rasters.getValue(PieceType.BISHOP), size, size, true)
                val queen = Bitmap.createScaledBitmap(rasters.getValue(PieceType.QUEEN), size, size, true)
                val king = Bitmap.createScaledBitmap(rasters.getValue(PieceType.KING), size, size, true)
                val pawnMask = alphaSilhouette(pawn)
                val bishopMask = alphaSilhouette(bishop)
                val queenMask = alphaSilhouette(queen)
                val kingMask = alphaSilhouette(king)
                assertTrue(
                    "$side ${size}px bishop must stand taller than the lunar pawn",
                    firstOccupiedRow(bishopMask, size, size) < firstOccupiedRow(pawnMask, size, size),
                )
                val upperHeight = (size * 0.66f).toInt()
                assertTrue(
                    "$side ${size}px pawn and bishop need distinct upper silhouettes",
                    intersectionOverUnion(pawnMask, bishopMask, size, upperHeight) < 0.80,
                )
                assertTrue(
                    "$side ${size}px sun queen and armillary king need distinct crowns",
                    intersectionOverUnion(queenMask, kingMask, size, upperHeight) < 0.85,
                )
            }
        }
    }

    private fun alphaSilhouette(bitmap: Bitmap): BooleanArray =
        BooleanArray(bitmap.width * bitmap.height) { index ->
            (bitmap.getPixel(index % bitmap.width, index / bitmap.width) ushr 24 and 0xFF) > 80
        }

    @Test
    fun visualEvidenceCoversThemesBoardScaleAndAccessibility() {
        val themes = listOf(
            DrawlessVisualThemes.GLACIER_SLATE,
            DrawlessVisualThemes.VERDIGRIS_COPPER,
            DrawlessVisualThemes.CELESTIAL_OBSERVATORY,
            DrawlessVisualThemes.HALLOWEEN_EMBERWOOD,
            DrawlessVisualThemes.HALLOWEEN_WITCHGLASS,
        )
        val selected = mutableStateOf(themes.first())
        compose.setContent {
            DrawlessTheme(selected.value.boardTheme) {
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .background(Color(0xFF0A0E15))
                        .padding(10.dp)
                        .testTag("piece_evidence_sheet"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Piece legibility",
                        color = Color(0xFFF2F5F6),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    CompositionLocalProvider(LocalDrawlessVisualTheme provides selected.value) {
                        EvidenceThemeSection(selected.value)
                    }
                }
            }
        }
        val outputDirectory = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("External evidence directory is unavailable")
        // Capture each theme separately so later candidates cannot fall below the phone viewport.
        themes.forEach { theme ->
            compose.runOnIdle { selected.value = theme }
            compose.waitForIdle()
            compose.onNodeWithTag("accessible_bishop_${theme.boardTheme.id}")
                .assertContentDescriptionEquals("Black bishop")
                .assertIsDisplayed()
            val evidence = compose.onNodeWithTag("piece_evidence_sheet")
            val evidenceBounds = evidence.fetchSemanticsNode().boundsInRoot
            assertTrue("Evidence node has empty bounds: $evidenceBounds", evidenceBounds.width > 0f && evidenceBounds.height > 0f)
            val bitmap = evidence.captureToImage().asAndroidBitmap()
            assertEquals("Entire sheet must fit in the capture", evidenceBounds.height, bitmap.height.toFloat(), 1f)
            val evidencePixels = countFarFromColor(bitmap, Color(0xFF0A0E15).toArgb(), tolerance = 30)
            assertTrue(
                "${theme.boardTheme.id} capture contains too little rendered content",
                evidencePixels >= (bitmap.width * bitmap.height * 0.05f).toInt(),
            )
            val output = outputDirectory.resolve("chess-piece-visual-evidence-${theme.boardTheme.id}.png")
            FileOutputStream(output).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("Evidence PNG is empty", output.length() > 0)
            Log.i("ChessPieceVisual", "evidence=${output.absolutePath} bitmap=${bitmap.width}x${bitmap.height} content_pixels=$evidencePixels")
        }
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
            themeName(visualTheme.boardTheme.id),
            color = Color(0xFFDCE4EA),
            style = MaterialTheme.typography.labelMedium,
        )
        listOf(Side.WHITE, Side.BLACK).forEach { side ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(14, 18, 24).forEach { size ->
                    listOf(
                        PieceType.BISHOP,
                        PieceType.PAWN,
                        PieceType.KING,
                        PieceType.QUEEN,
                    ).forEach { type ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Side.WHITE, Side.BLACK).forEach { side ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(if (side == Side.WHITE) darkSquare else lightSquare),
                    contentAlignment = Alignment.Center,
                ) {
                    ChessPiece(side, PieceType.QUEEN, Modifier.fillMaxSize().padding(3.dp))
                }
            }
        }
        Spacer(Modifier.height(1.dp))
    }
}
