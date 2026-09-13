package com.drawlesschess.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.drawlesschess.R
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.presentation.PieceStyleIds

private const val PIECE_RASTER_PX = 192
private const val PIECE_RASTER_CACHE_KIB = 4 * 1024

private data class PieceRasterKey(
    val side: Side,
    val type: PieceType,
    val styleId: String,
    val fill: Color,
    val outline: Color,
    val detail: Color,
    val kingAccent: Color,
    val queenAccent: Color,
    val assetBacked: Boolean,
)

private val pieceRasterCache = object : LruCache<PieceRasterKey, ImageBitmap>(PIECE_RASTER_CACHE_KIB) {
    override fun sizeOf(key: PieceRasterKey, value: ImageBitmap): Int =
        (value.width * value.height * Int.SIZE_BYTES / 1024).coerceAtLeast(1)
}

private val sculptedAtlasCache = object : LruCache<Int, Bitmap>(16 * 1024) {
    override fun sizeOf(key: Int, value: Bitmap): Int =
        (value.width * value.height * Int.SIZE_BYTES / 1024).coerceAtLeast(1)
}

/** Project-owned chess art with a code-native fallback for every theme and platform state. */
@Composable
internal fun ChessPiece(
    side: Side,
    type: PieceType,
    modifier: Modifier = Modifier,
) {
    val visualTheme = LocalDrawlessVisualTheme.current
    val palette = visualTheme.pieces
    val styleId = visualTheme.boardTheme.pieceStyleId
    val geometry = remember(type) { pieceGeometry(type) }
    val fill = if (side == Side.WHITE) palette.whiteFill else palette.blackFill
    val outline = if (side == Side.WHITE) palette.whiteOutline else palette.blackOutline
    val detail = if (side == Side.WHITE) palette.whiteDetail else palette.blackDetail
    val kingAccent = if (side == Side.WHITE) palette.whiteKingAccent else palette.blackKingAccent
    val queenAccent = if (side == Side.WHITE) palette.whiteQueenAccent else palette.blackQueenAccent
    val resources = LocalContext.current.resources
    val atlas = remember(side, styleId, resources) {
        loadSculptedAtlas(resources, side, styleId)
    }
    val raster = remember(side, type, styleId, fill, outline, detail, kingAccent, queenAccent, atlas) {
        pieceRaster(
            key = PieceRasterKey(
                side, type, styleId, fill, outline, detail, kingAccent, queenAccent,
                assetBacked = atlas != null,
            ),
            geometry = geometry,
            pieceAtlas = atlas,
        )
    }
    Canvas(modifier) {
        drawImage(
            image = raster,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
}

internal fun chessPieceRaster(
    side: Side,
    type: PieceType,
    palette: DrawlessPiecePalette,
    styleId: String = PieceStyleIds.MODERN_FLAT,
    resources: Resources? = null,
): ImageBitmap {
    val fill = if (side == Side.WHITE) palette.whiteFill else palette.blackFill
    val outline = if (side == Side.WHITE) palette.whiteOutline else palette.blackOutline
    val detail = if (side == Side.WHITE) palette.whiteDetail else palette.blackDetail
    val kingAccent = if (side == Side.WHITE) palette.whiteKingAccent else palette.blackKingAccent
    val queenAccent = if (side == Side.WHITE) palette.whiteQueenAccent else palette.blackQueenAccent
    val atlas = resources?.let { loadSculptedAtlas(it, side, styleId) }
    return pieceRaster(
        key = PieceRasterKey(
            side, type, styleId, fill, outline, detail, kingAccent, queenAccent,
            assetBacked = atlas != null,
        ),
        geometry = pieceGeometry(type),
        pieceAtlas = atlas,
    )
}

private fun pieceRaster(
    key: PieceRasterKey,
    geometry: PieceGeometry,
    pieceAtlas: Bitmap?,
): ImageBitmap {
    pieceRasterCache.get(key)?.let { return it }
    if (pieceAtlas != null) {
        val rendered = when (key.styleId) {
            PieceStyleIds.ALL_HALLOWS -> renderAllHallowsAtlasPiece(
                pieceAtlas, key.type, ivory = key.side == Side.WHITE,
            )
            PieceStyleIds.CELESTIAL_OBSERVATORY -> renderCelestialAtlasPiece(
                pieceAtlas, key.type, ivory = key.side == Side.WHITE,
            )
            else -> null
        }
        if (rendered != null) return rendered.also { pieceRasterCache.put(key, it) }
    }
    val image = ImageBitmap(PIECE_RASTER_PX, PIECE_RASTER_PX)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = GraphicsCanvas(image),
        size = Size(PIECE_RASTER_PX.toFloat(), PIECE_RASTER_PX.toFloat()),
    ) {
        val scale = PIECE_RASTER_PX / 100f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPiece(
                type = key.type,
                geometry = geometry,
                fill = key.fill,
                outline = key.outline,
                detail = key.detail,
                kingAccent = key.kingAccent,
                queenAccent = key.queenAccent,
                styleId = key.styleId,
            )
        }
    }
    pieceRasterCache.put(key, image)
    return image
}

private data class AtlasCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun isSculptedPieceStyle(styleId: String): Boolean =
    styleId == PieceStyleIds.ALL_HALLOWS || styleId == PieceStyleIds.CELESTIAL_OBSERVATORY

private fun loadSculptedAtlas(resources: Resources, side: Side, styleId: String): Bitmap? {
    val resourceId = when (styleId) {
        PieceStyleIds.ALL_HALLOWS -> if (side == Side.WHITE) {
            R.drawable.all_hallows_ivory_atlas_chroma
        } else {
            R.drawable.all_hallows_obsidian_atlas_chroma
        }
        PieceStyleIds.CELESTIAL_OBSERVATORY -> if (side == Side.WHITE) {
            R.drawable.celestial_ivory_atlas_chroma
        } else {
            R.drawable.celestial_midnight_atlas_chroma
        }
        else -> return null
    }
    sculptedAtlasCache.get(resourceId)?.let { return it }
    return BitmapFactory.decodeResource(resources, resourceId)
        ?.also { sculptedAtlasCache.put(resourceId, it) }
}

/**
 * Renders a tight source crop through one color matrix. The green atlas backdrop becomes alpha;
 * neutral ivory/obsidian and warm ember pixels remain opaque. This preserves high-frequency 3D
 * detail without decoding twelve separate images or performing per-frame pixel work.
 */
private fun renderAllHallowsAtlasPiece(
    atlas: Bitmap,
    type: PieceType,
    ivory: Boolean,
): ImageBitmap {
    val source = allHallowsCrop(type, ivory)
    val availableHeight = PIECE_RASTER_PX * 0.96f
    val baseScale = availableHeight / source.height
    var targetWidth = source.width * baseScale * 1.28f
    var targetHeight = source.height * baseScale
    val maximumWidth = PIECE_RASTER_PX * 0.98f
    if (targetWidth > maximumWidth) {
        val correction = maximumWidth / targetWidth
        targetWidth *= correction
        targetHeight *= correction
    }

    val destination = RectF(
        (PIECE_RASTER_PX - targetWidth) / 2f,
        PIECE_RASTER_PX - targetHeight - PIECE_RASTER_PX * 0.01f,
        (PIECE_RASTER_PX + targetWidth) / 2f,
        PIECE_RASTER_PX - PIECE_RASTER_PX * 0.01f,
    )
    return renderChromaAtlasCrop(atlas, source, destination)
}

private fun renderChromaAtlasCrop(
    atlas: Bitmap,
    source: AtlasCrop,
    destination: RectF,
    suppressGreenSpill: Boolean = false,
): ImageBitmap {
    val result = Bitmap.createBitmap(PIECE_RASTER_PX, PIECE_RASTER_PX, Bitmap.Config.ARGB_8888)
    // Celestial's ivory, brass and navy contain no green pigment. Reconstructing green from the
    // two uncontaminated channels removes reflected backdrop at translucent edges without
    // tightening the alpha key, which would erode the thin armillary rings and sun rays.
    val greenRed = if (suppressGreenSpill) 0.6f else 0f
    val greenGreen = if (suppressGreenSpill) 0f else 1f
    val greenBlue = if (suppressGreenSpill) 0.4f else 0f
    val paint = AndroidPaint(
        AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.DITHER_FLAG,
    ).apply {
        colorFilter = ColorMatrixColorFilter(
            AndroidColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    greenRed, greenGreen, greenBlue, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    1f, -2f, 1f, 0f, 255f,
                ),
            ),
        )
    }
    AndroidCanvas(result).drawBitmap(
        atlas,
        Rect(source.left, source.top, source.left + source.width, source.top + source.height),
        destination,
        paint,
    )
    return result.asImageBitmap()
}

/** All instruments share a ground line; their real silhouettes retain their source proportions. */
private fun renderCelestialAtlasPiece(atlas: Bitmap, type: PieceType, ivory: Boolean): ImageBitmap {
    val source = celestialCrop(type, ivory)
    val heightRatio = when (type) {
        PieceType.PAWN -> 0.70f
        PieceType.ROOK -> 0.82f
        PieceType.KNIGHT -> 0.89f
        PieceType.BISHOP -> 0.91f
        PieceType.QUEEN -> 0.95f
        PieceType.KING -> 0.98f
    }
    val availableHeight = PIECE_RASTER_PX * 0.96f
    val scale = minOf(
        availableHeight * heightRatio / source.height,
        PIECE_RASTER_PX * 0.92f / source.width,
    )
    val width = source.width * scale
    val height = source.height * scale
    val baseline = PIECE_RASTER_PX * 0.985f
    return renderChromaAtlasCrop(
        atlas,
        source,
        RectF((PIECE_RASTER_PX - width) / 2f, baseline - height, (PIECE_RASTER_PX + width) / 2f, baseline),
        suppressGreenSpill = true,
    )
}

private fun celestialCrop(type: PieceType, ivory: Boolean): AtlasCrop {
    // Independent measured crops retain each atlas's ten-pixel air margin around the sculpture.
    if (ivory) {
        return when (type) {
            PieceType.PAWN -> AtlasCrop(49, 421, 208, 285)
            PieceType.ROOK -> AtlasCrop(295, 282, 263, 421)
            PieceType.KNIGHT -> AtlasCrop(584, 301, 266, 400)
            PieceType.BISHOP -> AtlasCrop(882, 252, 256, 444)
            PieceType.QUEEN -> AtlasCrop(1_169, 165, 254, 539)
            PieceType.KING -> AtlasCrop(1_447, 120, 268, 584)
        }
    }
    return when (type) {
        PieceType.PAWN -> AtlasCrop(48, 414, 205, 283)
        PieceType.ROOK -> AtlasCrop(290, 276, 261, 417)
        PieceType.KNIGHT -> AtlasCrop(575, 295, 263, 396)
        PieceType.BISHOP -> AtlasCrop(869, 247, 253, 439)
        PieceType.QUEEN -> AtlasCrop(1_151, 161, 251, 533)
        PieceType.KING -> AtlasCrop(1_426, 117, 265, 576)
    }
}

private fun allHallowsCrop(type: PieceType, ivory: Boolean): AtlasCrop {
    if (ivory) {
        return when (type) {
            PieceType.PAWN -> AtlasCrop(42, 375, 246, 380)
            PieceType.ROOK -> AtlasCrop(306, 320, 252, 438)
            PieceType.KNIGHT -> AtlasCrop(591, 320, 270, 438)
            PieceType.BISHOP -> AtlasCrop(910, 110, 250, 649)
            PieceType.QUEEN -> AtlasCrop(1_182, 205, 296, 553)
            PieceType.KING -> AtlasCrop(1_478, 173, 250, 583)
        }
    }
    return when (type) {
        PieceType.PAWN -> AtlasCrop(65, 325, 225, 425)
        PieceType.ROOK -> AtlasCrop(320, 230, 256, 520)
        PieceType.KNIGHT -> AtlasCrop(593, 265, 294, 486)
        PieceType.BISHOP -> AtlasCrop(887, 135, 270, 615)
        PieceType.QUEEN -> AtlasCrop(1_155, 175, 285, 575)
        PieceType.KING -> AtlasCrop(1_440, 135, 275, 615)
    }
}

private fun DrawScope.drawPiece(
    type: PieceType,
    geometry: PieceGeometry,
    fill: Color,
    outline: Color,
    detail: Color,
    kingAccent: Color,
    queenAccent: Color,
    styleId: String,
) {
    val shape = geometry.shape
    drawPath(shape, outline, style = Stroke(width = 7f))
    drawPath(shape, fill, style = Fill)
    drawPath(shape, outline, style = Stroke(width = 2.4f))

    when (type) {
        PieceType.KING -> {
            // The identifying mark is the notched crown in kingPath(), not anything drawn on top:
            // an applied cross is under a pixel of ink at 14dp, where the notches still read. The
            // accent fills the circlet band so it holds its color at the same size.
            drawRect(kingAccent, topLeft = Offset(25f, 40f), size = Size(50f, 12f))
            drawLine(outline, Offset(25f, 40f), Offset(75f, 40f), strokeWidth = 2.2f)
            drawCircle(detail, 2.6f, Offset(35f, 46f))
            drawCircle(detail, 2.6f, Offset(65f, 46f))
        }
        PieceType.QUEEN -> {
            listOf(
                Offset(27f, 18f),
                Offset(42f, 13f),
                Offset(58f, 13f),
                Offset(73f, 18f),
            ).forEach { center ->
                drawCircle(outline, 4.2f, center)
                drawCircle(queenAccent, 2.8f, center)
            }
        }
        PieceType.BISHOP -> {
            // The recessed mitre cut and separate, wide shoulder are readable silhouette
            // features at 14dp; the pawn has neither an angled crown nor a collar.
            val mitreCut = requireNotNull(geometry.bishopMitreCut)
            drawPath(mitreCut, outline)
            drawLine(detail, Offset(57f, 19f), Offset(43f, 43f), strokeWidth = 2.8f)

            val collar = requireNotNull(geometry.bishopCollar)
            drawPath(collar, outline, style = Stroke(width = 6f))
            drawPath(collar, fill)
            drawPath(collar, outline, style = Stroke(width = 2.4f))
            drawLine(detail, Offset(26f, 59f), Offset(74f, 59f), strokeWidth = 2.8f)
        }
        PieceType.KNIGHT -> {
            drawCircle(detail, radius = 2.6f, center = Offset(57f, 31f))
            drawLine(detail, Offset(48f, 48f), Offset(63f, 55f), strokeWidth = 3f)
        }
        PieceType.ROOK -> drawLine(detail, Offset(31f, 42f), Offset(69f, 42f), strokeWidth = 3f)
        PieceType.PAWN -> Unit
    }

    // A shared weighted base gives every piece a coherent, readable silhouette.
    // The bishop's oversized collar needs a slightly shorter plinth so the whole mark keeps a
    // clean raster margin in the 14dp promotion-history slot.
    val baseDetail = if (type == PieceType.BISHOP) 80f else 83f
    val base = geometry.base
    drawPath(base, outline, style = Stroke(width = 7f))
    drawPath(base, fill)
    drawPath(base, outline, style = Stroke(width = 2.4f))
    drawLine(detail, Offset(23f, baseDetail), Offset(77f, baseDetail), strokeWidth = 2.6f)

    when (styleId) {
        PieceStyleIds.ALL_HALLOWS -> drawAllHallowsOrnaments(type, outline, detail, kingAccent, queenAccent)
        PieceStyleIds.CELESTIAL_OBSERVATORY -> drawCelestialOrnaments(type, outline, detail, kingAccent)
    }
}

/**
 * Bold, low-frequency details keep the Halloween set recognizable in both full board cells and
 * the 14 dp history slot. The classic silhouettes remain untouched so piece recognition never
 * depends on the seasonal ornamentation.
 */
private fun DrawScope.drawAllHallowsOrnaments(
    type: PieceType,
    outline: Color,
    detail: Color,
    ember: Color,
    flame: Color,
) {
    when (type) {
        PieceType.PAWN -> {
            // A carved pumpkin crown: three readable ribs and a short, angled stem.
            drawArc(
                color = detail,
                startAngle = 118f,
                sweepAngle = 304f,
                useCenter = false,
                topLeft = Offset(38f, 20f),
                size = Size(24f, 25f),
                style = Stroke(width = 2.2f),
            )
            drawArc(
                color = ember,
                startAngle = 105f,
                sweepAngle = 330f,
                useCenter = false,
                topLeft = Offset(45f, 20f),
                size = Size(10f, 25f),
                style = Stroke(width = 2.5f),
            )
            drawLine(detail, Offset(48f, 17f), Offset(54f, 13f), strokeWidth = 3f)
        }
        PieceType.ROOK -> {
            // Ember-lit lancet windows turn the rook into a tiny haunted tower.
            listOf(Offset(37f, 50f), Offset(55f, 50f)).forEach { topLeft ->
                drawRoundRect(
                    color = outline,
                    topLeft = topLeft,
                    size = Size(8f, 15f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    color = ember,
                    topLeft = topLeft + Offset(2f, 2f),
                    size = Size(4f, 11f),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        PieceType.KNIGHT -> {
            // A hot eye and three bone-jaw cuts suggest a skeletal charger without changing it.
            drawCircle(outline, radius = 4.3f, center = Offset(57f, 31f))
            drawCircle(ember, radius = 2.7f, center = Offset(57f, 31f))
            repeat(3) { index ->
                val y = 49f + index * 4.5f
                drawLine(detail, Offset(48f + index, y), Offset(61f, y + 3f), strokeWidth = 2f)
            }
        }
        PieceType.BISHOP -> {
            // A spectral inner hood follows the familiar mitre and remains clear at small sizes.
            val hood = Path().apply {
                moveTo(52f, 17f)
                cubicTo(46f, 24f, 44f, 32f, 46f, 40f)
                cubicTo(48f, 44f, 51f, 47f, 52f, 49f)
                cubicTo(57f, 45f, 60f, 40f, 59f, 34f)
                cubicTo(58f, 27f, 55f, 21f, 52f, 17f)
                close()
            }
            drawPath(hood, flame.copy(alpha = 0.84f))
            drawPath(hood, outline, style = Stroke(width = 2.1f))
            drawLine(detail, Offset(55f, 23f), Offset(48f, 39f), strokeWidth = 2.2f)
        }
        PieceType.QUEEN -> {
            // Alternating embers and flame tips form a gothic reliquary crown.
            listOf(
                Offset(27f, 18f),
                Offset(42f, 13f),
                Offset(58f, 13f),
                Offset(73f, 18f),
            ).forEachIndexed { index, center ->
                drawCircle(if (index % 2 == 0) ember else flame, 2.8f, center)
                drawLine(
                    detail,
                    Offset(center.x, center.y + 5f),
                    Offset(50f, 42f),
                    strokeWidth = 1.6f,
                )
            }
        }
        PieceType.KING -> {
            // A compact bat notch over the circlet reads as ornament, never as a new silhouette.
            val bat = Path().apply {
                moveTo(37f, 45f)
                lineTo(44f, 42f)
                lineTo(50f, 46f)
                lineTo(56f, 42f)
                lineTo(63f, 45f)
                lineTo(57f, 50f)
                lineTo(50f, 48f)
                lineTo(43f, 50f)
                close()
            }
            drawPath(bat, flame)
            drawPath(bat, outline, style = Stroke(width = 1.8f))
            drawCircle(ember, radius = 2.2f, center = Offset(50f, 46f))
        }
    }
}

/** Brass instrument details preserve a recognizable, code-native fallback when art is unavailable. */
private fun DrawScope.drawCelestialOrnaments(type: PieceType, outline: Color, brass: Color, gold: Color) {
    fun star(center: Offset, radius: Float) {
        val path = Path().apply {
            repeat(8) { index ->
                val angle = index * Math.PI / 4.0 - Math.PI / 2.0
                val length = if (index % 2 == 0) radius else radius * 0.30f
                val x = center.x + kotlin.math.cos(angle).toFloat() * length
                val y = center.y + kotlin.math.sin(angle).toFloat() * length
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, outline, style = Stroke(width = 1.6f))
        drawPath(path, gold)
    }
    when (type) {
        PieceType.PAWN -> {
            drawCircle(brass, 13f, Offset(50f, 31f), style = Stroke(width = 2f))
            drawCircle(brass.copy(alpha = 0.75f), 3.6f, Offset(45f, 27f))
            drawCircle(brass.copy(alpha = 0.6f), 2.2f, Offset(55f, 35f))
            drawArc(gold, 10f, 160f, false, Offset(33f, 15f), Size(34f, 34f), style = Stroke(width = 2.6f))
        }
        PieceType.ROOK -> {
            drawArc(gold, 180f, 180f, false, Offset(29f, 16f), Size(42f, 35f), style = Stroke(width = 2.5f))
            drawLine(gold, Offset(50f, 16f), Offset(50f, 35f), strokeWidth = 2.5f)
            drawRoundRect(outline, Offset(45f, 46f), Size(10f, 17f), CornerRadius(5f, 5f))
            drawLine(brass, Offset(47f, 63f), Offset(53f, 63f), strokeWidth = 2.4f)
        }
        PieceType.KNIGHT -> {
            drawCircle(gold, 2.7f, Offset(57f, 31f))
            listOf(0f, 5f, 10f).forEach { shift ->
                drawArc(brass, 230f, 120f, false, Offset(36f + shift, 18f), Size(27f, 40f), style = Stroke(width = 1.5f))
            }
            star(Offset(57f, 61f), 4f)
        }
        PieceType.BISHOP -> star(Offset(50f, 32f), 9f)
        PieceType.QUEEN -> {
            repeat(9) { index ->
                val angle = Math.PI + index * Math.PI / 8.0
                val direction = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                val center = Offset(50f, 28f)
                drawLine(gold, center + direction * 19f, center + direction * 25f, strokeWidth = 1.8f)
            }
            drawCircle(gold, 7f, Offset(50f, 27f), style = Stroke(width = 2f))
        }
        PieceType.KING -> {
            drawCircle(gold, 16f, Offset(50f, 28f), style = Stroke(width = 2.2f))
            drawOval(brass, Offset(40f, 12f), Size(20f, 32f), style = Stroke(width = 2f))
            drawOval(gold, Offset(33f, 22f), Size(34f, 12f), style = Stroke(width = 2f))
            star(Offset(50f, 28f), 8f)
        }
    }
    drawLine(brass, Offset(24f, 85f), Offset(76f, 85f), strokeWidth = 2f)
    repeat(7) { index ->
        val x = 29f + index * 7f
        drawLine(gold, Offset(x, 86f), Offset(x, 89f), strokeWidth = 1.3f)
    }
}

private data class PieceGeometry(
    val shape: Path,
    val base: Path,
    val bishopMitreCut: Path? = null,
    val bishopCollar: Path? = null,
)

private fun pieceGeometry(type: PieceType): PieceGeometry {
    val bishop = type == PieceType.BISHOP
    val baseTop = if (bishop) 69f else 70f
    val baseBottom = if (bishop) 91f else 93f
    return PieceGeometry(
        shape = when (type) {
            PieceType.PAWN -> pawnPath()
            PieceType.KNIGHT -> knightPath()
            PieceType.BISHOP -> bishopPath()
            PieceType.ROOK -> rookPath()
            PieceType.QUEEN -> queenPath()
            PieceType.KING -> kingPath()
        },
        base = Path().apply {
            moveTo(25f, baseTop)
            lineTo(75f, baseTop)
            lineTo(82f, 88f)
            quadraticTo(83f, baseBottom, 77f, baseBottom)
            lineTo(23f, baseBottom)
            quadraticTo(17f, baseBottom, 18f, 88f)
            close()
        },
        bishopMitreCut = if (!bishop) null else Path().apply {
            moveTo(59f, 16f)
            lineTo(47f, 43f)
            quadraticTo(44f, 47f, 40f, 43f)
            lineTo(55f, 15f)
            close()
        },
        bishopCollar = if (!bishop) null else Path().apply {
            moveTo(27f, 51f)
            lineTo(73f, 51f)
            lineTo(78f, 60f)
            quadraticTo(80f, 65f, 73f, 66f)
            lineTo(27f, 66f)
            quadraticTo(20f, 65f, 22f, 60f)
            close()
        },
    )
}

private fun pawnPath() = Path().apply {
    moveTo(50f, 14f)
    cubicTo(39f, 14f, 33f, 22f, 33f, 32f)
    cubicTo(33f, 41f, 38f, 47f, 44f, 50f)
    cubicTo(37f, 56f, 32f, 64f, 31f, 74f)
    lineTo(69f, 74f)
    cubicTo(68f, 64f, 63f, 56f, 56f, 50f)
    cubicTo(62f, 47f, 67f, 41f, 67f, 32f)
    cubicTo(67f, 22f, 61f, 14f, 50f, 14f)
    close()
}

private fun rookPath() = Path().apply {
    moveTo(26f, 15f)
    lineTo(38f, 15f)
    lineTo(38f, 25f)
    lineTo(46f, 25f)
    lineTo(46f, 15f)
    lineTo(56f, 15f)
    lineTo(56f, 25f)
    lineTo(64f, 25f)
    lineTo(64f, 15f)
    lineTo(76f, 15f)
    lineTo(73f, 39f)
    lineTo(67f, 45f)
    lineTo(70f, 74f)
    lineTo(30f, 74f)
    lineTo(33f, 45f)
    lineTo(29f, 39f)
    close()
}

private fun knightPath() = Path().apply {
    moveTo(29f, 74f)
    cubicTo(30f, 60f, 35f, 49f, 43f, 42f)
    lineTo(36f, 29f)
    lineTo(52f, 34f)
    lineTo(47f, 18f)
    cubicTo(67f, 22f, 76f, 35f, 73f, 50f)
    cubicTo(71f, 62f, 62f, 65f, 64f, 74f)
    close()
}

private fun bishopPath() = Path().apply {
    moveTo(52f, 10f)
    cubicTo(44f, 17f, 37f, 28f, 39f, 38f)
    cubicTo(40f, 45f, 45f, 49f, 47f, 52f)
    lineTo(43f, 58f)
    cubicTo(38f, 62f, 33f, 68f, 29f, 74f)
    lineTo(71f, 74f)
    cubicTo(67f, 68f, 62f, 62f, 57f, 57f)
    lineTo(53f, 52f)
    cubicTo(58f, 49f, 63f, 44f, 64f, 37f)
    cubicTo(65f, 27f, 59f, 17f, 52f, 10f)
    close()
}

private fun queenPath() = Path().apply {
    moveTo(24f, 23f)
    lineTo(35f, 38f)
    lineTo(42f, 18f)
    lineTo(50f, 38f)
    lineTo(58f, 18f)
    lineTo(65f, 38f)
    lineTo(76f, 23f)
    lineTo(68f, 54f)
    cubicTo(66f, 62f, 68f, 67f, 70f, 74f)
    lineTo(30f, 74f)
    cubicTo(32f, 67f, 34f, 62f, 32f, 54f)
    close()
}

// A three-point circlet whose sides flare outward. Points of comparable height read as a crown;
// an outsized center point reads as a cone, which is how the dome-and-cross king was mistaken for
// the bishop. The crown reaches y=14 so the king finishes just above the queen's y=18 peak.
private fun kingPath() = Path().apply {
    moveTo(34f, 74f)
    cubicTo(33f, 65f, 37f, 58f, 41f, 53f)
    lineTo(24f, 53f)
    lineTo(23f, 20f)
    lineTo(35f, 36f)
    lineTo(50f, 14f)
    lineTo(65f, 36f)
    lineTo(77f, 20f)
    lineTo(76f, 53f)
    lineTo(59f, 53f)
    cubicTo(63f, 58f, 67f, 65f, 66f, 74f)
    close()
}
