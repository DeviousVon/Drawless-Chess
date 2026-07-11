package com.drawlesschess.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drawlesschess.core.chess.PieceType
import com.drawlesschess.core.presentation.GameResultView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A finite, decorative finish effect. The persistent and accessible outcome is rendered by
 * [PostGameBar]; this layer deliberately owns no semantics or pointer input.
 */
@Composable
internal fun CompletionEffectOverlay(
    result: GameResultView,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(result) { Animatable(0f) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    val durationMillis = if (result.playerWon) 1_300 else 1_050

    LaunchedEffect(result) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        )
        latestOnFinished()
    }

    val fraction = progress.value
    val veil = if (result.playerWon) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val accent = MaterialTheme.colorScheme.secondary

    Box(modifier.clearAndSetSemantics { }) {
        Box(
            Modifier
                .fillMaxSize()
                .background(veil.copy(alpha = veilAlpha(fraction, result.playerWon))),
        )
        Canvas(Modifier.fillMaxSize()) {
            if (result.playerWon) {
                drawVictoryFireworks(fraction, veil, accent)
            } else {
                drawDefeatCracks(fraction)
            }
        }
        FinishCallout(
            result = result,
            progress = fraction,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun FinishCallout(
    result: GameResultView,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val calloutAlpha = when {
        progress < 0.12f -> progress / 0.12f
        progress > 0.78f -> (1f - progress) / 0.22f
        else -> 1f
    }.coerceIn(0f, 1f)
    val entrance = (progress / 0.32f).coerceIn(0f, 1f)
    val container = if (result.playerWon) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (result.playerWon) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 320.dp)
            .graphicsLayer {
                alpha = calloutAlpha
                scaleX = 0.84f + (0.16f * entrance)
                scaleY = 0.84f + (0.16f * entrance)
                if (!result.playerWon) {
                    translationY = 18f * entrance
                    rotationZ = 4f * entrance
                }
            },
        color = container,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 12.dp,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChessPiece(
                side = result.playerSide,
                type = PieceType.KING,
                modifier = Modifier.size(74.dp),
            )
            Text(
                text = if (result.playerWon) "VICTORY" else "DEFEAT",
                color = content,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = if (result.playerWon) "You won!" else "Your opponent won.",
                color = content.copy(alpha = 0.86f),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun DrawScope.drawVictoryFireworks(
    progress: Float,
    primary: Color,
    accent: Color,
) {
    val reveal = (progress / 0.7f).coerceIn(0f, 1f)
    val fade = (1f - ((progress - 0.58f) / 0.42f)).coerceIn(0f, 1f)
    val palette = listOf(accent, Color(0xFFFFC857), Color(0xFFFF7A66), primary, Color.White)
    val bursts = listOf(
        Offset(size.width * 0.22f, size.height * 0.24f),
        Offset(size.width * 0.78f, size.height * 0.30f),
        Offset(size.width * 0.52f, size.height * 0.16f),
    )
    val baseRadius = size.minDimension * 0.20f * reveal

    bursts.forEachIndexed { burstIndex, center ->
        val localRadius = baseRadius * (0.78f + burstIndex * 0.12f)
        repeat(18) { ray ->
            val angle = ((ray / 18f) * 2f * PI + burstIndex * 0.31f).toFloat()
            val direction = Offset(cos(angle), sin(angle))
            val startRadius = localRadius * 0.32f
            val endRadius = localRadius * (0.78f + (ray % 3) * 0.10f)
            val color = palette[(ray + burstIndex) % palette.size].copy(alpha = fade)
            drawLine(
                color = color,
                start = center + Offset(direction.x * startRadius, direction.y * startRadius),
                end = center + Offset(direction.x * endRadius, direction.y * endRadius),
                strokeWidth = max(2f, size.minDimension * 0.006f),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color,
                radius = max(2.5f, size.minDimension * 0.007f),
                center = center + Offset(direction.x * endRadius, direction.y * endRadius),
            )
        }
        drawCircle(
            color = accent.copy(alpha = fade * 0.62f),
            radius = localRadius * 0.58f,
            center = center,
            style = Stroke(width = max(2f, size.minDimension * 0.005f)),
        )
    }

    repeat(30) { index ->
        val delay = (index % 6) * 0.035f
        val fall = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (fall <= 0f) return@repeat
        val seededX = ((index * 37) % 101) / 101f
        val x = size.width * (0.05f + seededX * 0.90f) +
            sin((fall * 5f + index) * 1.3f) * size.width * 0.018f
        val y = -size.height * 0.08f + fall * size.height * 1.05f
        val pieceWidth = max(5f, size.minDimension * 0.012f)
        val pieceHeight = pieceWidth * 1.8f
        val pieceAlpha = (1f - ((fall - 0.76f) / 0.24f)).coerceIn(0f, 1f)
        rotate(index * 29f + fall * 210f, pivot = Offset(x, y)) {
            drawRect(
                color = palette[index % palette.size].copy(alpha = pieceAlpha),
                topLeft = Offset(x - pieceWidth / 2f, y - pieceHeight / 2f),
                size = Size(pieceWidth, pieceHeight),
            )
        }
    }
}

private fun DrawScope.drawDefeatCracks(progress: Float) {
    val reveal = (progress / 0.34f).coerceIn(0f, 1f)
    val fade = (1f - ((progress - 0.62f) / 0.38f)).coerceIn(0f, 1f)
    val impact = Offset(size.width * 0.54f, size.height * 0.34f)
    val lineColor = Color.White.copy(alpha = fade * 0.88f)
    val shadowColor = Color(0xFF20303A).copy(alpha = fade * 0.52f)
    val angles = listOf(-2.74f, -2.18f, -1.58f, -1.02f, -0.38f, 0.18f, 0.84f, 1.42f, 2.06f, 2.58f)

    drawCircle(
        color = lineColor.copy(alpha = fade * 0.45f),
        radius = size.minDimension * 0.05f * reveal,
        center = impact,
        style = Stroke(width = max(2f, size.minDimension * 0.004f)),
    )

    angles.forEachIndexed { index, angle ->
        val direction = Offset(cos(angle), sin(angle))
        val length = size.minDimension * (0.24f + (index % 4) * 0.055f) * reveal
        val end = impact + Offset(direction.x * length, direction.y * length)
        val stroke = max(2.2f, size.minDimension * 0.005f)
        drawLine(shadowColor, impact + Offset(2f, 3f), end + Offset(2f, 3f), stroke + 2f)
        drawLine(lineColor, impact, end, stroke, cap = StrokeCap.Round)

        val branchStart = impact + Offset(direction.x * length * 0.54f, direction.y * length * 0.54f)
        val branchAngle = angle + if (index % 2 == 0) 0.52f else -0.48f
        val branchDirection = Offset(cos(branchAngle), sin(branchAngle))
        val branchLength = length * (0.30f + (index % 3) * 0.06f)
        val branchEnd = branchStart + Offset(
            branchDirection.x * branchLength,
            branchDirection.y * branchLength,
        )
        drawLine(lineColor.copy(alpha = fade * 0.78f), branchStart, branchEnd, stroke * 0.72f)
    }

    val shard = Path().apply {
        moveTo(impact.x - size.minDimension * 0.035f * reveal, impact.y)
        lineTo(impact.x + size.minDimension * 0.018f * reveal, impact.y - size.minDimension * 0.045f * reveal)
        lineTo(impact.x + size.minDimension * 0.042f * reveal, impact.y + size.minDimension * 0.028f * reveal)
        close()
    }
    drawPath(shard, Color.White.copy(alpha = fade * 0.20f))
}

private fun veilAlpha(progress: Float, won: Boolean): Float {
    val fade = (1f - ((progress - 0.58f) / 0.42f)).coerceIn(0f, 1f)
    return (if (won) 0.16f else 0.22f) * fade
}
