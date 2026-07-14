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
    val panelMoveHistoryHeightDp: Int,
)

object ResponsiveBoardLayout {
    fun calculate(widthDp: Int, heightDp: Int): BoardLayoutSpec {
        require(widthDp > 0 && heightDp > 0)
        val widthClass = when {
            widthDp < 600 -> WindowWidthClass.COMPACT
            widthDp < 840 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
        val landscape = widthDp > heightDp
        val shortLandscape = landscape && heightDp < 480
        val sidePadding = if (shortLandscape) 12 else 24
        val sidePanel = when {
            shortLandscape && widthClass == WindowWidthClass.COMPACT -> 200
            shortLandscape && widthClass == WindowWidthClass.MEDIUM -> 240
            shortLandscape -> 280
            widthClass == WindowWidthClass.MEDIUM -> 260
            else -> 320
        }
        val sideBoardSize = min(
            (widthDp - sidePanel - sidePadding * 3).coerceAtLeast(1),
            (heightDp - sidePadding * 2).coerceAtLeast(1),
        )
        val useSidePanel = when {
            landscape -> sideBoardSize >= MIN_LANDSCAPE_BOARD_DP
            widthClass == WindowWidthClass.EXPANDED -> sideBoardSize >= MIN_PORTRAIT_SIDE_BOARD_DP
            else -> false
        }

        return if (!useSidePanel) {
            val padding = 16
            val availableBoardSize = min(
                (widthDp - padding * 2).coerceAtLeast(1),
                (heightDp - padding * 2).coerceAtLeast(1),
            )
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BELOW_BOARD,
                boardSizeDp = if (landscape) {
                    availableBoardSize
                } else {
                    // The stacked screen scrolls as one document, so fixed estimates for
                    // clocks and controls must not collapse the board on short windows or
                    // when the user selects a large system font. The portrait cap meets the
                    // side-layout threshold exactly, avoiding a board-size cliff at the
                    // expanded-tablet transition.
                    min(availableBoardSize, MAX_STACKED_PORTRAIT_BOARD_DP)
                },
                outerPaddingDp = padding,
                panelWidthDp = widthDp - padding * 2,
                panelMoveHistoryHeightDp = STACKED_MOVE_HISTORY_HEIGHT_DP,
            )
        } else {
            BoardLayoutSpec(
                widthClass,
                ControlPlacement.BESIDE_BOARD,
                boardSizeDp = sideBoardSize,
                outerPaddingDp = sidePadding,
                panelWidthDp = sidePanel,
                panelMoveHistoryHeightDp = if (shortLandscape) {
                    SHORT_SIDE_MOVE_HISTORY_HEIGHT_DP
                } else {
                    SIDE_MOVE_HISTORY_HEIGHT_DP
                },
            )
        }
    }

    private const val STACKED_MOVE_HISTORY_HEIGHT_DP = 160
    private const val SHORT_SIDE_MOVE_HISTORY_HEIGHT_DP = 132
    private const val SIDE_MOVE_HISTORY_HEIGHT_DP = 240
    private const val MIN_LANDSCAPE_BOARD_DP = 120
    private const val MAX_STACKED_PORTRAIT_BOARD_DP = 640
    private const val MIN_PORTRAIT_SIDE_BOARD_DP = MAX_STACKED_PORTRAIT_BOARD_DP
}
