package com.drawlesschess.ui

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.drawlesschess.core.Side
import com.drawlesschess.core.chess.PieceType

private const val PIECE_RASTER_PX = 192
private const val PIECE_RASTER_CACHE_KIB = 4 * 1024

private data class PieceRasterKey(
    val side: Side,
    val type: PieceType,
    val fill: Color,
    val outline: Color,
    val detail: Color,
    val kingAccent: Color,
)

private val pieceRasterCache = object : LruCache<PieceRasterKey, ImageBitmap>(PIECE_RASTER_CACHE_KIB) {
    override fun sizeOf(key: PieceRasterKey, value: ImageBitmap): Int =
        (value.width * value.height * Int.SIZE_BYTES / 1024).coerceAtLeast(1)
}

/** Original, code-native chess pieces so rendering is consistent and redistributable. */
@Composable
internal fun ChessPiece(
    side: Side,
    type: PieceType,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDrawlessVisualTheme.current.pieces
    val geometry = remember(type) { pieceGeometry(type) }
    val fill = if (side == Side.WHITE) palette.whiteFill else palette.blackFill
    val outline = if (side == Side.WHITE) palette.whiteOutline else palette.blackOutline
    val detail = if (side == Side.WHITE) palette.whiteDetail else palette.blackDetail
    val kingAccent = if (side == Side.WHITE) palette.whiteKingAccent else palette.blackKingAccent
    val raster = remember(side, type, fill, outline, detail, kingAccent) {
        pieceRaster(
            key = PieceRasterKey(side, type, fill, outline, detail, kingAccent),
            geometry = geometry,
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
): ImageBitmap {
    val fill = if (side == Side.WHITE) palette.whiteFill else palette.blackFill
    val outline = if (side == Side.WHITE) palette.whiteOutline else palette.blackOutline
    val detail = if (side == Side.WHITE) palette.whiteDetail else palette.blackDetail
    val kingAccent = if (side == Side.WHITE) palette.whiteKingAccent else palette.blackKingAccent
    return pieceRaster(
        key = PieceRasterKey(side, type, fill, outline, detail, kingAccent),
        geometry = pieceGeometry(type),
    )
}

private fun pieceRaster(
    key: PieceRasterKey,
    geometry: PieceGeometry,
): ImageBitmap {
    pieceRasterCache.get(key)?.let { return it }
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
            )
        }
    }
    pieceRasterCache.put(key, image)
    return image
}

private fun DrawScope.drawPiece(
    type: PieceType,
    geometry: PieceGeometry,
    fill: Color,
    outline: Color,
    detail: Color,
    kingAccent: Color,
) {
    val shape = geometry.shape
    drawPath(shape, outline, style = Stroke(width = 7f))
    drawPath(shape, fill, style = Fill)
    drawPath(shape, outline, style = Stroke(width = 2.4f))

    when (type) {
        PieceType.KING -> {
            // The colored, outlined cross keeps the king distinct from the bishop at a glance.
            drawLine(outline, Offset(50f, 5f), Offset(50f, 29f), strokeWidth = 8f)
            drawLine(outline, Offset(40f, 14f), Offset(60f, 14f), strokeWidth = 8f)
            drawLine(kingAccent, Offset(50f, 5f), Offset(50f, 29f), strokeWidth = 4.5f)
            drawLine(kingAccent, Offset(40f, 14f), Offset(60f, 14f), strokeWidth = 4.5f)
        }
        PieceType.QUEEN -> {
            drawCircle(detail, 3.4f, Offset(27f, 18f))
            drawCircle(detail, 3.4f, Offset(42f, 13f))
            drawCircle(detail, 3.4f, Offset(58f, 13f))
            drawCircle(detail, 3.4f, Offset(73f, 18f))
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

private fun kingPath() = Path().apply {
    moveTo(50f, 25f)
    cubicTo(36f, 25f, 29f, 35f, 34f, 47f)
    cubicTo(37f, 54f, 40f, 58f, 36f, 64f)
    lineTo(31f, 74f)
    lineTo(69f, 74f)
    lineTo(64f, 64f)
    cubicTo(60f, 58f, 63f, 54f, 66f, 47f)
    cubicTo(71f, 35f, 64f, 25f, 50f, 25f)
    close()
}
