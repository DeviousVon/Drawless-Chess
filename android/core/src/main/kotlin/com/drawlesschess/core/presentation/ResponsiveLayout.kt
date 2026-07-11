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
        val sidePadding = 24
        val sidePanel = if (widthClass == WindowWidthClass.MEDIUM) 260 else 320
        val sideBoardSize = min(
            (widthDp - sidePanel - sidePadding * 3).coerceAtLeast(1),
            (heightDp - sidePadding * 2 - SIDE_CLOCK_RESERVE_DP).coerceAtLeast(1),
        )
        val useSidePanel = widthClass != WindowWidthClass.COMPACT &&
            (widthClass == WindowWidthClass.EXPANDED || widthDp > heightDp) &&
            sideBoardSize >= MIN_SIDE_BOARD_DP

        return if (!useSidePanel) {
            val padding = 16
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BELOW_BOARD,
                boardSizeDp = min(
                    (widthDp - padding * 2).coerceAtLeast(1),
                    (heightDp - STACKED_CONTENT_RESERVE_DP).coerceAtLeast(1),
                ),
                outerPaddingDp = padding,
                panelWidthDp = widthDp - padding * 2,
            )
        } else {
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BESIDE_BOARD,
                boardSizeDp = sideBoardSize,
                outerPaddingDp = sidePadding,
                panelWidthDp = sidePanel,
            )
        }
    }

    private const val STACKED_CONTENT_RESERVE_DP = 300
    private const val SIDE_CLOCK_RESERVE_DP = 76
    private const val MIN_SIDE_BOARD_DP = 360
}
