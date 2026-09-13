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

/** Adds a deterministic, cached material surface above a square's base color. */
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
    val boardTextureId = theme.textureId
    when (boardTextureId) {
        BoardTextureIds.CELESTIAL_OBSERVATORY ->
            drawCelestialBezel(canvas, squarePx * 8f, squarePx)
        BoardTextureIds.EMBERWOOD, BoardTextureIds.WITCHGLASS ->
            drawHalloweenMaterialRim(canvas, squarePx * 8f, squarePx, boardTextureId == BoardTextureIds.EMBERWOOD)
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
        BoardTextureIds.EMBERWOOD,
        BoardTextureIds.WITCHGLASS,
        -> requestedPx.coerceIn(1, MAX_TEXTURE_PX)
        BoardTextureIds.SANDSTONE,
        BoardTextureIds.MARBLE,
        BoardTextureIds.SLATE,
        BoardTextureIds.VERDIGRIS,
        BoardTextureIds.ALL_HALLOWS,
        BoardTextureIds.CELESTIAL_OBSERVATORY,
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
        BoardTextureIds.ALL_HALLOWS -> renderAllHallows(px, isLightSquare, seed)
        BoardTextureIds.EMBERWOOD -> renderEmberwood(px, isLightSquare, seed)
        BoardTextureIds.WITCHGLASS -> renderWitchglass(px, isLightSquare, seed)
        BoardTextureIds.CELESTIAL_OBSERVATORY -> renderCelestial(px, isLightSquare, seed)
        else -> error("Unsupported board texture: $textureId")
    }
    textureBitmapCache.put(key, rendered)
    return rendered
}

/** Amber endgrain and scorched oak: quiet organic detail leaves move markers legible. */
private fun renderEmberwood(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = side / 72f
    val paint = Paint().apply { isAntiAlias = true }
    paint.color = (if (light) Color(0xFFC99658) else Color(0xFF4B332A))
        .jittered(rng.nextInt(-3, 4) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    paint.style = PaintingStyle.Stroke
    paint.strokeCap = StrokeCap.Round
    if (light) {
        val center = Offset(
            side * (0.1f + rng.nextFloat() * 0.8f),
            side * (0.1f + rng.nextFloat() * 0.8f),
        )
        val phase = rng.nextFloat() * 6.28f
        val stretch = 0.78f + rng.nextFloat() * 0.30f
        repeat(22) { ring ->
            val radius = side * (0.035f + ring * 0.046f)
            val path = Path()
            for (step in 0..80) {
                val angle = step * 6.2831855f / 80f
                val unevenRadius = radius * (
                    1f + 0.025f * sin(angle * 3f + phase) +
                        0.013f * sin(angle * 7f - phase + ring * 0.12f)
                    )
                val x = center.x + cos(angle) * unevenRadius
                val y = center.y + sin(angle) * unevenRadius * stretch
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.strokeWidth = (if (ring % 4 == 0) 0.72f else 0.42f) * scale
            paint.color = Color(0xFF71482B).copy(alpha = 0.10f + rng.nextFloat() * 0.06f)
            canvas.drawPath(path, paint)
        }
        // A few short radial checks suggest cut timber without introducing bright cracks.
        repeat(3) {
            val angle = rng.nextFloat() * 6.28f
            val start = side * (0.20f + rng.nextFloat() * 0.45f)
            val length = side * (0.07f + rng.nextFloat() * 0.13f)
            val direction = Offset(cos(angle), sin(angle) * stretch)
            paint.strokeWidth = 0.5f * scale
            paint.color = Color(0xFF543825).copy(alpha = 0.16f)
            canvas.drawLine(center + direction * start, center + direction * (start + length), paint)
        }
    } else {
        val direction = if (rng.nextBoolean()) 1f else -1f
        val phase = rng.nextFloat() * 6.28f
        repeat(30) { line ->
            val start = side * (-0.65f + line * 0.075f)
            val path = Path()
            for (step in 0..24) {
                val x = side * step / 24f
                val y = start + direction * x * 0.72f +
                    side * 0.012f * sin(step * 0.37f + phase + line * 0.30f)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.strokeWidth = (if (line % 5 == 0) 0.95f else 0.50f) * scale
            paint.color = (if (line % 3 == 0) Color(0xFF94704B) else Color(0xFF231B18))
                .copy(alpha = 0.12f + rng.nextFloat() * 0.08f)
            canvas.drawPath(path, paint)
        }
        repeat(4) {
            val start = Offset(rng.nextFloat() * side, rng.nextFloat() * side)
            val length = side * (0.06f + rng.nextFloat() * 0.16f)
            val fracture = Path().apply {
                moveTo(start.x, start.y)
                lineTo(start.x + length * 0.43f, start.y + direction * length * 0.38f)
                lineTo(start.x + length * 0.62f, start.y + direction * length * 0.26f)
                lineTo(start.x + length, start.y + direction * length * 0.67f)
            }
            paint.strokeWidth = 0.68f * scale
            paint.color = Color(0xFF211A17).copy(alpha = 0.22f)
            canvas.drawPath(fracture, paint)
        }
    }

    paint.style = PaintingStyle.Fill
    repeat(36) {
        val point = Offset(rng.nextFloat() * side, rng.nextFloat() * side)
        paint.color = (if (light) Color(0xFF543725) else Color(0xFFC3A278))
            .copy(alpha = 0.04f + rng.nextFloat() * 0.06f)
        canvas.drawOval(
            Rect(point.x, point.y, point.x + 0.55f * scale, point.y + 1.2f * scale),
            paint,
        )
    }
    drawHalloweenTileEdges(canvas, side, scale, light, glass = false)
    return bitmap
}

/** Cloudy sage and mulberry glass with minute trapped bubbles beneath a satin surface. */
private fun renderWitchglass(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = side / 72f
    val paint = Paint().apply { isAntiAlias = true }
    paint.color = (if (light) Color(0xFFA6C2AF) else Color(0xFF59425D))
        .jittered(rng.nextInt(-3, 4) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    // Concentric translucent lobes feather the clouds rather than making opaque spots.
    repeat(7) { cloud ->
        val center = Offset(rng.nextFloat() * side, rng.nextFloat() * side)
        val radius = side * (0.22f + rng.nextFloat() * 0.32f)
        val flatten = 0.38f + rng.nextFloat() * 0.45f
        val tint = if (light) {
            if (cloud % 2 == 0) Color(0xFFE4E8C9) else Color(0xFF526F65)
        } else {
            if (cloud % 2 == 0) Color(0xFFAC778C) else Color(0xFF252334)
        }
        paint.color = tint.copy(alpha = if (light) 0.012f else 0.016f)
        repeat(10) { layer ->
            val size = radius * (1f - layer * 0.07f)
            canvas.drawOval(
                Rect(center.x - size, center.y - size * flatten, center.x + size, center.y + size * flatten),
                paint,
            )
        }
    }

    paint.style = PaintingStyle.Stroke
    paint.strokeCap = StrokeCap.Round
    repeat(4) {
        val y = side * (-0.1f + rng.nextFloat() * 1.2f)
        val drift = side * (rng.nextFloat() - 0.5f) * 0.6f
        val swirl = Path().apply {
            moveTo(-side * 0.1f, y)
            cubicTo(side * 0.25f, y + drift, side * 0.42f, y - side * 0.30f, side * 0.65f, y - drift)
            cubicTo(side * 0.82f, y + side * 0.20f, side * 0.95f, y + drift, side * 1.1f, y - side * 0.12f)
        }
        // Broad halos keep the fine curl recessed below the surface.
        val tint = if (light) Color(0xFFE2E8D0) else Color(0xFFC18C9C)
        for (width in listOf(5.5f, 2.5f, 0.7f)) {
            paint.strokeWidth = width * scale
            paint.color = tint.copy(alpha = if (width > 1f) 0.022f else 0.065f)
            canvas.drawPath(swirl, paint)
        }
    }

    repeat(3 + rng.nextInt(4)) {
        var x = side * (0.12f + rng.nextFloat() * 0.76f)
        val y = side * (0.12f + rng.nextFloat() * 0.76f)
        // Keep round decoration outside the central legal-move marker region.
        if (abs(x - side * 0.5f) < side * 0.21f && abs(y - side * 0.5f) < side * 0.21f) {
            x = side * if (x < side * 0.5f) 0.18f else 0.82f
        }
        val radius = (0.50f + rng.nextFloat() * 0.85f) * scale
        paint.strokeWidth = 0.52f * scale
        paint.color = (if (light) Color(0xFF3C6257) else Color(0xFF201D2A)).copy(alpha = 0.32f)
        canvas.drawCircle(Offset(x, y), radius, paint)
        paint.color = (if (light) Color(0xFFF0F2DB) else Color(0xFFD8B7C6)).copy(alpha = 0.40f)
        paint.strokeWidth = 0.46f * scale
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, 205f, 115f, false, paint)
    }
    paint.style = PaintingStyle.Fill
    repeat(24) {
        paint.color = (if (light) Color(0xFFF0EED2) else Color(0xFFD8B5C1)).copy(alpha = 0.08f)
        canvas.drawCircle(Offset(rng.nextFloat() * side, rng.nextFloat() * side), 0.25f * scale, paint)
    }
    drawHalloweenTileEdges(canvas, side, scale, light, glass = true)
    return bitmap
}

/** Integral hairline joints preserve the full playing area and all hit regions. */
private fun drawHalloweenTileEdges(canvas: Canvas, side: Float, scale: Float, light: Boolean, glass: Boolean) {
    val paint = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Stroke
        strokeWidth = (if (glass) 0.90f else 0.48f) * scale
        color = Color(0xFF191D1A).copy(alpha = if (glass) 0.54f else 0.30f)
    }
    val inset = paint.strokeWidth / 2f
    canvas.drawRect(inset, inset, side - inset, side - inset, paint)
    paint.strokeWidth = 0.5f * scale
    paint.color = (if (glass) Color(0xFFE5E8D3) else Color(0xFFE4C294))
        .copy(alpha = if (light) 0.20f else 0.15f)
    val bevel = (if (glass) 1.1f else 0.75f) * scale
    canvas.drawLine(Offset(bevel, bevel), Offset(side - bevel, bevel), paint)
    canvas.drawLine(Offset(bevel, bevel), Offset(bevel, side - bevel), paint)
}

/** A thin iron rim lies inside the board, adding no margin or layout padding. */
private fun drawHalloweenMaterialRim(canvas: Canvas, boardSide: Float, squarePx: Int, emberwood: Boolean) {
    val scale = squarePx / 72f
    val paint = Paint().apply {
        isAntiAlias = true
        style = PaintingStyle.Stroke
        strokeWidth = 1.6f * scale
        color = if (emberwood) Color(0xFF302622) else Color(0xFF262E29)
    }
    val inset = paint.strokeWidth / 2f
    canvas.drawRect(inset, inset, boardSide - inset, boardSide - inset, paint)
    paint.strokeWidth = 0.4f * scale
    paint.color = (if (emberwood) Color(0xFFA2734D) else Color(0xFF99A486)).copy(alpha = 0.68f)
    val edge = 1.35f * scale
    canvas.drawRect(edge, edge, boardSide - edge, boardSide - edge, paint)
}

/** Lunar alabaster and midnight enamel, with quiet star charts etched beneath the pieces. */
private fun renderCelestial(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = (side / 72f).coerceAtLeast(0.4f)
    val paint = Paint().apply { isAntiAlias = true }
    val base = if (light) Color(0xFFDED5C2) else Color(0xFF152A3B)
    paint.color = base.jittered(rng.nextInt(-3, 4) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    // Broad mineral clouds read as polished material, without competing with piece silhouettes.
    repeat(if (light) 16 else 10) {
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        val radius = side * (0.06f + rng.nextFloat() * 0.25f)
        paint.color = if (light) {
            (if (rng.nextBoolean()) Color(0xFF8E8166) else Color(0xFFFFF7E6))
                .copy(alpha = 0.035f + rng.nextFloat() * 0.035f)
        } else {
            (if (rng.nextBoolean()) Color(0xFF050F1C) else Color(0xFF7095B0))
                .copy(alpha = 0.025f + rng.nextFloat() * 0.035f)
        }
        canvas.drawOval(Rect(x - radius, y - radius * 0.4f, x + radius, y + radius * 0.4f), paint)
    }

    val brass = if (light) Color(0xFF806B43) else Color(0xFFCFB177)
    if (light) {
        // Off-centre astrolabe rings and graduated meridians are fine, recessed brass engraving.
        val center = Offset(side * (0.25f + rng.nextFloat() * 0.5f), side * (0.25f + rng.nextFloat() * 0.5f))
        val radius = side * (0.34f + rng.nextFloat() * 0.10f)
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = 0.6f * scale
        paint.color = brass.copy(alpha = 0.19f)
        listOf(0.72f, 1f, 1.10f).forEach { ratio ->
            canvas.drawCircle(center, radius * ratio, paint)
        }
        canvas.drawOval(Rect(center.x - radius, center.y - radius * 0.40f, center.x + radius, center.y + radius * 0.40f), paint)
        canvas.drawLine(Offset(center.x - radius * 1.18f, center.y), Offset(center.x + radius * 1.18f, center.y), paint)
        canvas.drawLine(Offset(center.x, center.y - radius * 1.18f), Offset(center.x, center.y + radius * 1.18f), paint)
        repeat(24) { mark ->
            val angle = mark * Math.PI * 2.0 / 24.0
            val inner = radius * if (mark % 3 == 0) 0.93f else 0.98f
            val outer = radius * 1.08f
            canvas.drawLine(
                center + Offset(cos(angle).toFloat() * inner, sin(angle).toFloat() * inner),
                center + Offset(cos(angle).toFloat() * outer, sin(angle).toFloat() * outer),
                paint,
            )
        }
        paint.style = PaintingStyle.Fill
    } else {
        // Each square gets its own small constellation. Stars stay subordinate to move markers.
        val mirror = if (rng.nextBoolean()) 1f else -1f
        val nodes = listOf(
            Offset(0.18f, 0.32f), Offset(0.38f, 0.21f), Offset(0.57f, 0.45f),
            Offset(0.78f, 0.32f), Offset(0.65f, 0.74f), Offset(0.32f, 0.80f),
        ).map { point ->
            Offset(
                side * (0.5f + mirror * (point.x - 0.5f)),
                side * (point.y + (rng.nextFloat() - 0.5f) * 0.10f),
            )
        }
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = 0.48f * scale
        paint.color = brass.copy(alpha = 0.19f)
        nodes.zipWithNext().forEach { (start, end) -> canvas.drawLine(start, end, paint) }
        paint.style = PaintingStyle.Fill
        nodes.forEachIndexed { index, point ->
            paint.color = brass.copy(alpha = if (index == 2) 0.54f else 0.38f)
            canvas.drawCircle(point, (if (index == 2) 0.85f else 0.65f) * scale, paint)
        }
    }

    // Small mineral flecks become alabaster grain or pinpricks in the enamel at board scale.
    repeat(if (light) 40 else 16) {
        paint.color = (if (light) Color(0xFF776B53) else Color(0xFFE1CE94))
            .copy(alpha = if (light) 0.035f + rng.nextFloat() * 0.04f else 0.08f + rng.nextFloat() * 0.12f)
        canvas.drawCircle(Offset(rng.nextFloat() * side, rng.nextFloat() * side), 0.45f * scale, paint)
    }
    // A fine, warm inlay separates the polished tiles without making a busy grid.
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = 0.65f * scale
    paint.color = brass.copy(alpha = if (light) 0.15f else 0.20f)
    canvas.drawRect(0.4f * scale, 0.4f * scale, side - 0.4f * scale, side - 0.4f * scale, paint)
    paint.color = Color.White.copy(alpha = if (light) 0.16f else 0.08f)
    canvas.drawLine(Offset(0f, 0.7f * scale), Offset(side, 0.7f * scale), paint)
    return bitmap
}

/** A thin astrolabe bezel sits inside the playing surface, preserving all board hit regions. */
private fun drawCelestialBezel(canvas: Canvas, boardSide: Float, squarePx: Int) {
    val scale = squarePx / 72f
    val paint = Paint().apply { isAntiAlias = true }
    val inset = 1.8f * scale
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = 3.2f * scale
    paint.color = Color(0xFF72552E)
    canvas.drawRect(inset, inset, boardSide - inset, boardSide - inset, paint)
    paint.strokeWidth = 0.7f * scale
    paint.color = Color(0xFFD7B570).copy(alpha = 0.88f)
    canvas.drawRect(inset, inset, boardSide - inset, boardSide - inset, paint)
    val step = squarePx / 4f
    repeat(31) { index ->
        val position = (index + 1) * step
        val length = (if (index % 4 == 3) 3.4f else 1.8f) * scale
        canvas.drawLine(Offset(position, inset), Offset(position, inset + length), paint)
        canvas.drawLine(Offset(position, boardSide - inset), Offset(position, boardSide - inset - length), paint)
        canvas.drawLine(Offset(inset, position), Offset(inset + length, position), paint)
        canvas.drawLine(Offset(boardSide - inset, position), Offset(boardSide - inset - length, position), paint)
    }
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

/** A deterministic candlelit-stone tile with restrained wear and a shallow carved bevel. */
private fun renderAllHallows(px: Int, light: Boolean, seed: Int): ImageBitmap {
    val rng = Random(seed)
    val bitmap = ImageBitmap(px, px)
    val canvas = Canvas(bitmap)
    val side = px.toFloat()
    val scale = (px / 72f).coerceAtLeast(0.5f)
    val paint = Paint().apply { isAntiAlias = true }

    val base = if (light) Color(0xFFC7BBAA) else Color(0xFF28313A)
    paint.style = PaintingStyle.Fill
    paint.color = base.jittered(rng.nextInt(-5, 6) / 255f)
    canvas.drawRect(0f, 0f, side, side, paint)

    // Broad translucent mineral blooms provide depth without a repeated photograph.
    repeat(rng.nextInt(8, 13)) {
        val width = side * (0.12f + rng.nextFloat() * 0.38f)
        val height = side * (0.04f + rng.nextFloat() * 0.16f)
        val left = rng.nextFloat() * (side + width) - width
        val top = rng.nextFloat() * (side + height) - height
        paint.style = PaintingStyle.Fill
        paint.color = if (light) {
            (if (rng.nextBoolean()) Color(0xFF786E62) else Color(0xFFF5ECDD))
                .copy(alpha = 0.025f + rng.nextFloat() * 0.055f)
        } else {
            (if (rng.nextBoolean()) Color(0xFF10161C) else Color(0xFF71808B))
                .copy(alpha = 0.035f + rng.nextFloat() * 0.06f)
        }
        canvas.drawOval(Rect(left, top, left + width, top + height), paint)
    }

    // Tiny pits and chisel marks survive downsampling as tactile stone rather than visual noise.
    repeat(rng.nextInt(22, 34)) { mark ->
        val x = rng.nextFloat() * side
        val y = rng.nextFloat() * side
        paint.style = PaintingStyle.Stroke
        paint.strokeCap = StrokeCap.Round
        paint.strokeWidth = (if (mark % 7 == 0) 0.9f else 0.45f) * scale
        paint.color = if (light) {
            Color(0xFF4E473F).copy(alpha = 0.07f + rng.nextFloat() * 0.08f)
        } else {
            Color(0xFFAAB4BC).copy(alpha = 0.05f + rng.nextFloat() * 0.07f)
        }
        canvas.drawLine(
            Offset(x, y),
            Offset((x + side * (0.01f + rng.nextFloat() * 0.045f)).coerceAtMost(side), y),
            paint,
        )
    }

    // Fine fractures add age; one restrained ember seam may show through a dark tile.
    repeat(rng.nextInt(2, 5)) { crackIndex ->
        val startX = rng.nextFloat() * side
        val startY = rng.nextFloat() * side
        val crack = Path().apply {
            moveTo(startX, startY)
            var x = startX
            var y = startY
            repeat(rng.nextInt(2, 5)) {
                x = (x + side * (rng.nextFloat() * 0.22f - 0.11f)).coerceIn(0f, side)
                y = (y + side * (0.06f + rng.nextFloat() * 0.10f)).coerceIn(0f, side)
                lineTo(x, y)
            }
        }
        paint.style = PaintingStyle.Stroke
        paint.strokeWidth = 0.75f * scale
        paint.color = Color(0xFF0D1116).copy(alpha = if (light) 0.16f else 0.28f)
        canvas.drawPath(crack, paint)
        if (!light && crackIndex == 0) {
            paint.strokeWidth = 0.35f * scale
            paint.color = Color(0xFFE78A2F).copy(alpha = 0.16f)
            canvas.save()
            canvas.translate(0.8f * scale, 0f)
            canvas.drawPath(crack, paint)
            canvas.restore()
        }
    }

    // A one-pixel carved lip supplies the reference board's tangible square-by-square depth.
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = 0.8f * scale
    paint.color = (if (light) Color.White else Color(0xFF8B99A3)).copy(alpha = 0.16f)
    canvas.drawLine(Offset(0f, 0.6f * scale), Offset(side, 0.6f * scale), paint)
    canvas.drawLine(Offset(0.6f * scale, 0f), Offset(0.6f * scale, side), paint)
    paint.color = Color.Black.copy(alpha = if (light) 0.18f else 0.34f)
    canvas.drawLine(Offset(0f, side - 0.6f * scale), Offset(side, side - 0.6f * scale), paint)
    canvas.drawLine(Offset(side - 0.6f * scale, 0f), Offset(side - 0.6f * scale, side), paint)

    // Only a minority of squares receive a faint corner engraving, avoiding a tiled-web effect.
    if ((seed ushr 2) and 7 == 0) {
        val fromRight = (seed and 1) == 0
        val originX = if (fromRight) side else 0f
        val direction = if (fromRight) -1f else 1f
        val webColor = if (light) Color(0xFF453E38) else Color(0xFFE7DDD0)
        paint.style = PaintingStyle.Stroke
        paint.strokeCap = StrokeCap.Round
        paint.strokeWidth = 0.55f * scale
        paint.color = webColor.copy(alpha = if (light) 0.14f else 0.12f)
        listOf(0.18f to 0.42f, 0.30f to 0.30f, 0.42f to 0.18f).forEach { (x, y) ->
            canvas.drawLine(
                Offset(originX, 0f),
                Offset(originX + direction * side * x, side * y),
                paint,
            )
        }
        repeat(2) { ring ->
            val radius = side * (0.16f + ring * 0.11f)
            val left = if (fromRight) side - radius else 0f
            val startAngle = if (fromRight) 90f else 0f
            canvas.drawArc(
                left,
                0f,
                left + radius,
                radius,
                startAngle,
                90f,
                false,
                paint,
            )
        }
    }

    return bitmap
}

private fun Color.jittered(amount: Float): Color = Color(
    red = (red + amount).coerceIn(0f, 1f),
    green = (green + amount).coerceIn(0f, 1f),
    blue = (blue + amount).coerceIn(0f, 1f),
)
