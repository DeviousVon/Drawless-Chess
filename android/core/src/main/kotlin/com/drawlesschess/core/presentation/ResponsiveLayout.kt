package com.drawlesschess.core.presentation

import kotlin.math.min

enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }
enum class ControlPlacement { BELOW_BOARD, BESIDE_BOARD }

data class BoardLayoutSpec(
    val widthClass: WindowWidthClass,
    val controlPlacement: ControlPlacement,
    val boardSizeDp: Int,
    val outerPaddingDp: Int,
    val panelWidthDp: Int,
)

object ResponsiveBoardLayout {
    fun calculate(widthDp: Int, heightDp: Int): BoardLayoutSpec {
        require(widthDp > 0 && heightDp > 0)
        val widthClass = when {
            widthDp < 600 -> WindowWidthClass.COMPACT
            widthDp < 840 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
        return if (widthClass == WindowWidthClass.COMPACT) {
            val padding = 16
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BELOW_BOARD,
                boardSizeDp = min(
                    (widthDp - padding * 2).coerceAtLeast(1),
                    (heightDp - 220).coerceAtLeast(1),
                ),
                outerPaddingDp = padding,
                panelWidthDp = widthDp - padding * 2,
            )
        } else {
            val padding = 24
            val panel = if (widthClass == WindowWidthClass.MEDIUM) 260 else 320
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BESIDE_BOARD,
                boardSizeDp = min(
                    (widthDp - panel - padding * 3).coerceAtLeast(1),
                    (heightDp - padding * 2).coerceAtLeast(1),
                ),
                outerPaddingDp = padding,
                panelWidthDp = panel,
            )
        }
    }
}
