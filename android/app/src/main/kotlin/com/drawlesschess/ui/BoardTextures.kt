package com.drawlesschess.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.drawlesschess.core.presentation.BoardOrientation
import com.drawlesschess.core.presentation.BoardTheme
import com.drawlesschess.core.presentation.BoardTextureIds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val MAX_TEXTURE_PX = 72
private const val MAX_AMETHYST_PX = 72
private const val TEXTURE_CACHE_KIB = 24 * 1024
private const val BOARD_SURFACE_CACHE_KIB = 16 * 1024

private data class TextureCacheKey(
    val textureId: String,
    val isLightSquare: Boolean,
    val file: Int,
    val rank: Int,
    val px: Int,
)

/**
 * Compose can replace a draw-cache lambda whenever a square recomposes. Keeping the generated
 * stone cut outside that lambda avoids rebuilding and re-uploading 64 bitmaps on every move.
 */
private val textureBitmapCache = object : LruCache<TextureCacheKey, ImageBitmap>(TEXTURE_CACHE_KIB) {
    override fun sizeOf(key: TextureCacheKey, value: ImageBitmap): Int =
        (value.width * value.height * Int.SIZE_BYTES / 1024).coerceAtLeast(1)
}

private data class BoardSurfaceCacheKey(
    val themeId: String,
    val orientation: BoardOrientation,
    val squarePx: Int,
)

private val boardSurfaceCache = object : LruCache<BoardSurfaceCacheKey, ImageBitmap>(BOARD_SURFACE_CACHE_KIB) {
    override fun sizeOf(key: BoardSurfaceCacheKey, value: ImageBitmap): Int =
        (value.width * value.height * Int.SIZE_BYTES / 1024).coerceAtLeast(1)
}

/** Adds a deterministic, cached stone surface above a square's base color. */
internal fun Modifier.squareTexture(
    textureId: String?,
    isLightSquare: Boolean,
    file: Int,
    rank: Int,
): Modifier {
    if (textureId == null) return this
    return drawWithCache {
        val px = min(size.minDimension.toInt().coerceAtLeast(1), MAX_TEXTURE_PX)
        val bitmap = textureBitmap(textureId, isLightSquare, file, rank, px)
        onDrawBehind {
            bitmap?.let {
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.scale(size.width / it.width, size.height / it.height)
                    canvas.drawImage(it, Offset.Zero, Paint())
                    canvas.restore()
                }
            }
        }
    }
}

/** Draws the 64 immutable square surfaces as one GPU image behind interactive square content. */
internal fun Modifier.boardSurface(
    theme: BoardTheme,
    orientation: BoardOrientation,
): Modifier = drawWithCache {
    val squarePx = min(
        (size.minDimension.toInt() / 8).coerceAtLeast(1),
        MAX_TEXTURE_PX,
    )
    val key = BoardSurfaceCacheKey(theme.id, orientation, squarePx)
    val bitmap = boardSurfaceCache.get(key) ?: renderBoardSurface(theme, orientation, squarePx)
        .also { boardSurfaceCache.put(key, it) }
    val paint = Paint()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.scale(size.width / bitmap.width, size.height / bitmap.height)
            canvas.drawImage(bitmap, Offset.Zero, paint)
            canvas.restore()
        }
    }
}

private fun renderBoardSurface(
    theme: BoardTheme,
    orientation: BoardOrientation,
    squarePx: Int,
): ImageBitmap {
    val board = ImageBitmap(squarePx * 8, squarePx * 8)
    val canvas = Canvas(board)
    val paint = Paint()
    for (row in 0..7) {
        for (column in 0..7) {
            val square = orientation.squareAt(row, column)
            val offset = Offset(column * squarePx.toFloat(), row * squarePx.toFloat())
            val texture = theme.textureId?.let {
                textureBitmap(it, square.isLight, square.file, square.rank, squarePx)
            }
            if (texture != null) {
                canvas.drawImage(texture, offset, paint)
            } else {
                paint.color = Color(
                    if (square.isLight) theme.lightSquare.value else theme.darkSquare.value,
                )
                canvas.drawRect(
                    offset.x,
                    offset.y,
                    offset.x + squarePx,
                    offset.y + squarePx,
                    paint,
                )
            }
        }
    }
    return board
}

internal fun textureBitmap(
    textureId: String,
    isLightSquare: Boolean,
    file: Int,
    rank: Int,
    requestedPx: Int,
): ImageBitmap? {
    val px = when (textureId) {
        BoardTextureIds.AMETHYST -> min(requestedPx, MAX_AMETHYST_PX)
        BoardTextureIds.SANDSTONE,
        BoardTextureIds.MARBLE,
        BoardTextureIds.SLATE,
        BoardTextureIds.VERDIGRIS,
        -> requestedPx
        else -> return null
    }
    val key = TextureCacheKey(textureId, isLightSquare, file, rank, px)
    textureBitmapCache.get(key)?.let { return it }
    val seed = (textureId.hashCode() * 31 + file) * 31 + rank
    val rendered = when (textureId) {
        BoardTextureIds.SANDSTONE -> renderSandstone(px, isLightSquare, seed)
        BoardTextureIds.MARBLE -> renderMarble(px, isLightSquare, seed)
        BoardTextureIds.SLATE -> renderSlate(px, isLightSquare, seed)
        BoardTextureIds.VERDIGRIS -> renderVerdigris(px, isLightSquare, seed)
        BoardTextureIds.AMETHYST -> renderAmethyst(px, isLightSquare, seed)
        else -> error("Unsupported board texture: $textureId")
    }
    textureBitmapCache.put(key, rendered)
    return rendered
}

private fun renderSandstone(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val paint = Paint()

    val jitter = rng.nextInt(-7, 8) / 255f
    val base = if (light) Color(0xFFE9D9B0) else Color(0xFFB07E54)
    paint.color = base.jittered(jitter)
    canvas.drawRect(0f, 0f, side, side, paint)

    repeat(rng.nextInt(3, 6)) {
        val y0 = rng.nextFloat() * side
        val thickness = side * (0.05f + rng.nextFloat() * 0.11f)
        val amplitude = 1.5f + rng.nextFloat() * 3f
        val phase = rng.nextFloat() * 6.28f
        val frequency = (0.8f + rng.nextFloat()) * 6.28f / side
        val darker = rng.nextFloat() < 0.6f
        val tint = when {
            light && darker -> Color(0xFFB08A5C)
            light -> Color(0xFFF6ECCD)
            darker -> Color(0xFF805234)
            else -> Color(0xFFCEA070)
        }
        paint.color = tint.copy(alpha = (22 + rng.nextInt(25)) / 255f)
        val band = Path()
        band.moveTo(0f, y0 + amplitude * sin(phase))
        var x = 0f
        while (x <= side) {
            band.lineTo(x, y0 + amplitude * sin(frequency * x + phase))
            x += 4f
        }
        x = side
        while (x >= 0f) {
            band.lineTo(x, y0 + amplitude * sin(frequency * x + phase) + thickness)
            x -= 4f
        }
        band.close()
        canvas.drawPath(band, paint)
    }

    repeat(260 + rng.nextInt(80)) {
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        val radius = 0.4f + rng.nextFloat() * 0.7f
        val dark = rng.nextFloat() < 0.55f
        paint.color = when {
            dark && light -> Color(0xFF96764C).copy(alpha = (16 + rng.nextInt(27)) / 255f)
            dark -> Color(0xFF785436).copy(alpha = (16 + rng.nextInt(27)) / 255f)
            else -> Color(0xFFFFF6DC).copy(alpha = (12 + rng.nextInt(19)) / 255f)
        }
        canvas.drawCircle(Offset(x, y), radius * px / 96f, paint)
    }

    repeat(rng.nextInt(3, 8)) {
        val margin = min(4f, side / 4f)
        val x = margin + rng.nextFloat() * (side - margin * 2f)
        val y = margin + rng.nextFloat() * (side - margin * 2f)
        val radius = (1.2f + rng.nextFloat() * 1.4f) * px / 96f
        paint.color = Color(0xFF46301E).copy(alpha = (18 + rng.nextInt(19)) / 255f)
        canvas.drawCircle(Offset(x, y), radius, paint)
        paint.color = Color(0xFFFFF4D6).copy(alpha = 0.10f)
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = 1f
        canvas.drawArc(
            x - radius,
            y - radius - 1f,
            x + radius,
            y + radius - 1f,
            200f,
            140f,
            false,
            paint,
        )
        paint.style = PaintingStyle.Fill
    }
    return bitmap
}

private fun renderMarble(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val paint = Paint().apply { isAntiAlias = true }

    val jitter = rng.nextInt(-5, 6) / 255f
    val base = if (light) Color(0xFFF2F0EB) else Color(0xFF344A3F)
    paint.color = base.jittered(jitter)
    canvas.drawRect(0f, 0f, side, side, paint)

    repeat(rng.nextInt(4, 7)) {
        val center = Offset(rng.nextFloat() * side, rng.nextFloat() * side)
        val radius = side * (0.2f + rng.nextFloat() * 0.3f)
        paint.color = (if (light) {
            if (rng.nextFloat() < 0.7f) Color(0xFFC4C6C8) else Color(0xFFDED8C8)
        } else {
            if (rng.nextFloat() < 0.6f) Color(0xFF26382F) else Color(0xFF546E60)
        }).copy(alpha = 0.04f)
        canvas.drawCircle(center, radius, paint)
    }

    fun vein(
        start: Offset,
        angle0: Float,
        width: Float,
        tint: Color,
        alpha: Float,
        steps: Int,
        wobble: Float,
    ): List<Offset> {
        var angle = angle0
        var point = start
        val points = ArrayList<Offset>(steps)
        paint.style = PaintingStyle.Stroke
        paint.strokeCap = StrokeCap.Round
        repeat(steps) { index ->
            points.add(point)
            angle += (rng.nextFloat() * 2f - 1f) * wobble
            val step = (2f + rng.nextFloat() * 2f) * px / 192f
            val next = Offset(point.x + step * cos(angle), point.y + step * sin(angle))
            val progress = index.toFloat() / steps
            paint.strokeWidth = (
                width * (1f - 0.7f * progress) * (0.75f + rng.nextFloat() * 0.5f)
            ).coerceAtLeast(0.6f) * px / 192f
            paint.color = tint.copy(alpha = alpha * (1f - 0.45f * progress))
            canvas.drawLine(point, next, paint)
            point = next
        }
        paint.style = PaintingStyle.Fill
        return points
    }

    repeat(rng.nextInt(2, 4)) { veinIndex ->
        val fromTop = rng.nextBoolean()
        val start = if (fromTop) {
            Offset(rng.nextFloat() * side, -4f)
        } else {
            Offset(-4f, rng.nextFloat() * side)
        }
        val angle = if (fromTop) {
            1.57f + (rng.nextFloat() * 1.8f - 0.9f)
        } else {
            rng.nextFloat() * 0.85f - 0.4f
        }
        val tint = if (light) {
            if (rng.nextFloat() < 0.8f) Color(0xFF96989E) else Color(0xFFAC9876)
        } else {
            if (rng.nextFloat() < 0.75f) Color(0xFFD6DED2) else Color(0xFF96B29E)
        }
        val bold = veinIndex == 0
        val alpha = ((if (light) 46 else 40) + rng.nextInt(35) + if (bold) 26 else 0) / 255f
        val points = vein(
            start = start,
            angle0 = angle,
            width = if (bold) 4.5f + rng.nextFloat() * 2f else 2f + rng.nextFloat() * 1.4f,
            tint = tint,
            alpha = alpha,
            steps = if (bold) 60 + rng.nextInt(30) else 40 + rng.nextInt(30),
            wobble = 0.30f,
        )
        repeat(rng.nextInt(1, 4)) {
            val branchPoint = points[rng.nextInt(points.size / 4, points.size - 1)]
            vein(
                branchPoint,
                rng.nextFloat() * 6.28f,
                0.8f + rng.nextFloat() * 0.8f,
                tint,
                alpha * 0.6f,
                14 + rng.nextInt(16),
                0.5f,
            )
        }
    }

    repeat(rng.nextInt(3, 7)) {
        vein(
            Offset(rng.nextFloat() * side, rng.nextFloat() * side),
            rng.nextFloat() * 6.28f,
            0.9f,
            if (light) Color(0xFFA8AAAF) else Color(0xFFBCC8BC),
            0.12f,
            10 + rng.nextInt(16),
            0.6f,
        )
    }
    return bitmap
}

private fun renderSlate(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = px / 192f
    val paint = Paint().apply { isAntiAlias = true }
    val base = if (light) Color(0xFFE4EAF0) else Color(0xFF61748A)
    paint.color = base.jittered(rng.nextInt(-6, 7) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    repeat(rng.nextInt(3, 6)) {
        val width = side * (0.28f + rng.nextFloat() * 0.42f)
        val height = side * (0.12f + rng.nextFloat() * 0.25f)
        val x = rng.nextFloat() * (side + width) - width
        val y = rng.nextFloat() * (side + height) - height
        paint.color = (if (light) {
            if (rng.nextBoolean()) Color(0xFFB8C5D0) else Color(0xFFF9FBFD)
        } else {
            if (rng.nextBoolean()) Color(0xFF34485C) else Color(0xFFA7B6C5)
        }).copy(alpha = 0.045f)
        canvas.drawOval(Rect(x, y, x + width, y + height), paint)
    }

    val slope = rng.nextFloat() * 0.12f - 0.06f
    repeat(rng.nextInt(26, 39)) {
        val x0 = rng.nextFloat() * side * 0.75f
        val length = side * (0.25f + rng.nextFloat() * 0.65f)
        val x1 = min(side, x0 + length)
        val y0 = rng.nextFloat() * side
        val y1 = y0 + slope * (x1 - x0) + (rng.nextFloat() * 1.4f - 0.7f) * scale
        val darkLine = rng.nextFloat() < 0.62f
        paint.color = when {
            light && darkLine -> Color(0xFF52677A).copy(alpha = 0.06f + rng.nextFloat() * 0.11f)
            light -> Color.White.copy(alpha = 0.055f + rng.nextFloat() * 0.07f)
            darkLine -> Color(0xFF243545).copy(alpha = 0.07f + rng.nextFloat() * 0.11f)
            else -> Color(0xFFB5C2CE).copy(alpha = 0.05f + rng.nextFloat() * 0.07f)
        }
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = (if (rng.nextFloat() < 0.2f) 1.6f else 0.8f) * scale.coerceAtLeast(0.6f)
        canvas.drawLine(Offset(x0, y0), Offset(x1, y1), paint)
    }

    if (rng.nextFloat() < 0.45f) {
        val darkFracture = Path()
        val lightEdge = Path()
        var x = side * (0.15f + rng.nextFloat() * 0.7f)
        darkFracture.moveTo(x, -2f)
        lightEdge.moveTo(x + scale, -2f)
        var y = -2f
        while (y < side + 2f) {
            y += side * (0.08f + rng.nextFloat() * 0.09f)
            x = (x + side * (rng.nextFloat() * 0.16f - 0.08f)).coerceIn(-3f, side + 3f)
            darkFracture.lineTo(x, y)
            lightEdge.lineTo(x + scale, y)
        }
        paint.style = PaintingStyle.Stroke
        paint.strokeCap = StrokeCap.Round
        paint.strokeWidth = (1.6f * scale).coerceAtLeast(0.7f)
        paint.color = Color(0xFF182632).copy(alpha = 0.16f)
        canvas.drawPath(darkFracture, paint)
        paint.strokeWidth = (0.75f * scale).coerceAtLeast(0.45f)
        paint.color = Color.White.copy(alpha = 0.09f)
        canvas.drawPath(lightEdge, paint)
    }

    paint.style = PaintingStyle.Fill
    repeat(rng.nextInt(10, 21)) {
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        val sparkle = scale.coerceAtLeast(0.55f)
        paint.color = Color.White.copy(alpha = 0.12f + rng.nextFloat() * 0.23f)
        canvas.drawRect(x, y, x + sparkle, y + sparkle, paint)
        if (rng.nextFloat() < 0.3f) {
            canvas.drawRect(x + sparkle, y, x + sparkle * 2f, y + sparkle, paint)
        }
    }
    return bitmap
}

private fun renderVerdigris(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = px / 192f
    val paint = Paint().apply { isAntiAlias = true }

    if (light) {
        paint.color = Color(0xFFECE4D2).jittered(rng.nextInt(-5, 6) / 255f)
        canvas.drawRect(0f, 0f, side, side, paint)
        repeat(rng.nextInt(5, 9)) {
            val y = rng.nextFloat() * side
            val height = side * (0.025f + rng.nextFloat() * 0.075f)
            paint.color = (if (rng.nextBoolean()) Color(0xFFD6CAB0) else Color(0xFFF8F3E5))
                .copy(alpha = 0.05f + rng.nextFloat() * 0.05f)
            canvas.drawRect(0f, y, side, min(side, y + height), paint)
        }
        repeat(rng.nextInt(60, 101)) {
            val width = (1.5f + rng.nextFloat() * 3.5f) * scale.coerceAtLeast(0.55f)
            val height = (0.7f + rng.nextFloat() * 0.9f) * scale.coerceAtLeast(0.55f)
            val x = rng.nextFloat() * side
            val y = rng.nextFloat() * side
            paint.color = Color(0xFF887A60).copy(alpha = 0.065f + rng.nextFloat() * 0.075f)
            canvas.drawOval(Rect(x, y, min(side, x + width), min(side, y + height)), paint)
        }
        repeat(rng.nextInt(40, 71)) {
            val x = rng.nextFloat() * side
            val y = rng.nextFloat() * side
            val dot = scale.coerceAtLeast(0.45f)
            paint.color = Color.White.copy(alpha = 0.05f + rng.nextFloat() * 0.08f)
            canvas.drawRect(x, y, x + dot, y + dot, paint)
        }
        return bitmap
    }

    paint.color = Color(0xFF356C67).jittered(rng.nextInt(-4, 5) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    val patinaColors = listOf(
        Color(0xFF173F3B),
        Color(0xFF285A56),
        Color(0xFF4E8179),
        Color(0xFF6A9288),
        Color(0xFF244D4B),
    )
    repeat(rng.nextInt(14, 23)) {
        val width = side * (0.18f + rng.nextFloat() * 0.48f)
        val height = side * (0.12f + rng.nextFloat() * 0.40f)
        val x = rng.nextFloat() * (side + width) - width
        val y = rng.nextFloat() * (side + height) - height
        paint.style = PaintingStyle.Fill
        paint.color = patinaColors[rng.nextInt(patinaColors.size)]
            .copy(alpha = 0.025f + rng.nextFloat() * 0.055f)
        canvas.drawOval(Rect(x, y, x + width, y + height), paint)
    }

    repeat(rng.nextInt(45, 81)) {
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        val radius = (0.35f + rng.nextFloat() * 0.85f) * scale.coerceAtLeast(0.6f)
        paint.style = PaintingStyle.Fill
        paint.color = (if (rng.nextFloat() < 0.82f) Color(0xFF123936) else Color(0xFFB87333))
            .copy(alpha = 0.04f + rng.nextFloat() * 0.09f)
        canvas.drawCircle(Offset(x, y), radius, paint)
    }

    paint.style = PaintingStyle.Stroke
    paint.strokeCap = StrokeCap.Round
    repeat(rng.nextInt(8, 15)) {
        val x0 = rng.nextFloat() * side
        val y0 = rng.nextFloat() * side
        val length = side * (0.08f + rng.nextFloat() * 0.25f)
        val angle = rng.nextFloat() * 6.28f
        val x1 = x0 + cos(angle) * length
        val y1 = y0 + sin(angle) * length
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(
                x0 + (x1 - x0) * 0.48f + (rng.nextFloat() * 2f - 1f) * 1.5f * scale,
                y0 + (y1 - y0) * 0.48f + (rng.nextFloat() * 2f - 1f) * 1.5f * scale,
            )
            lineTo(x1, y1)
        }
        paint.strokeWidth = (0.55f * scale).coerceAtLeast(0.4f)
        paint.color = (if (rng.nextBoolean()) Color(0xFF0D302E) else Color(0xFFB9D0C6))
            .copy(alpha = 0.045f + rng.nextFloat() * 0.055f)
        canvas.drawPath(path, paint)
    }

    val sheenStart = rng.nextFloat() * side * 0.45f - side * 0.15f
    paint.strokeWidth = side * (0.10f + rng.nextFloat() * 0.06f)
    paint.color = Color(0xFFD9EEE5).copy(alpha = 0.022f)
    canvas.drawLine(
        Offset(sheenStart, side),
        Offset(sheenStart + side * 0.7f, 0f),
        paint,
    )

    paint.style = PaintingStyle.Fill
    repeat(rng.nextInt(12, 26)) {
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        val length = (0.8f + rng.nextFloat() * 2.2f) * scale.coerceAtLeast(0.6f)
        paint.color = Color(0xFFCB8546).copy(alpha = 0.10f + rng.nextFloat() * 0.17f)
        canvas.drawRect(x, y, min(side, x + length), y + scale.coerceAtLeast(0.45f), paint)
    }
    return bitmap
}

private fun renderAmethyst(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val count = rng.nextInt(7, 11)
    val seedX = FloatArray(count) { rng.nextFloat() * px }
    val seedY = FloatArray(count) { rng.nextFloat() * px }
    val facetBrightness = FloatArray(count) { rng.nextFloat() * 0.19f - 0.07f }
    val facetBlue = FloatArray(count) { rng.nextFloat() * 0.08f - 0.03f }
    val base = if (light) floatArrayOf(227f, 217f, 240f) else floatArrayOf(84f, 64f, 110f)
    val jitter = rng.nextInt(-4, 5).toFloat()
    val pixels = IntArray(px * px)
    val edgeScale = (px / 192f).coerceAtLeast(0.35f)

    for (y in 0 until px) {
        for (x in 0 until px) {
            var nearest = Float.MAX_VALUE
            var second = Float.MAX_VALUE
            var nearestIndex = 0
            for (index in 0 until count) {
                val dx = x - seedX[index]
                val dy = y - seedY[index]
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared < nearest) {
                    second = nearest
                    nearest = distanceSquared
                    nearestIndex = index
                } else if (distanceSquared < second) {
                    second = distanceSquared
                }
            }
            val nearestDistance = sqrt(nearest)
            val edgeDistance = sqrt(second) - nearestDistance
            val glow = (
                1f - nearestDistance / (px * 0.62f)
            ).coerceIn(0f, 1f) * 0.055f
            val edge = (1f - edgeDistance / (2.4f * edgeScale)).coerceIn(0f, 1f)
            val refraction = (
                1f - abs(edgeDistance - 4.2f * edgeScale) / (2f * edgeScale)
            ).coerceIn(0f, 1f)
            val brightness = 1f + facetBrightness[nearestIndex] + glow
            val seam = 1f - 0.20f * edge
            fun channel(value: Float, colorLean: Float = 1f): Int =
                ((value + jitter) * brightness * seam * colorLean + refraction * 11f)
                    .coerceIn(0f, 255f)
                    .toInt()
            val red = channel(base[0])
            val green = channel(base[1])
            val blue = channel(base[2], 1f + facetBlue[nearestIndex])
            pixels[y * px + x] =
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    val androidBitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, px, 0, 0, px, px)
    }
    val image = androidBitmap.asImageBitmap()
    val canvas = Canvas(image)
    val paint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeWidth = (px / 192f).coerceAtLeast(0.55f)
    }
    repeat(rng.nextInt(3, 7)) {
        val x = rng.nextFloat() * px
        val y = rng.nextFloat() * px
        val length = (2f + rng.nextFloat() * 3f) * edgeScale
        paint.color = Color.White.copy(alpha = (50 + rng.nextInt(60)) / 255f)
        canvas.drawLine(Offset(x - length, y), Offset(x + length, y), paint)
        canvas.drawLine(Offset(x, y - length), Offset(x, y + length), paint)
    }
    return image
}

private fun Color.jittered(amount: Float): Color = Color(
    red = (red + amount).coerceIn(0f, 1f),
    green = (green + amount).coerceIn(0f, 1f),
    blue = (blue + amount).coerceIn(0f, 1f),
)
