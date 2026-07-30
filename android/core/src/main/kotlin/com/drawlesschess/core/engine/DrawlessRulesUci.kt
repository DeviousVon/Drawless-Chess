package com.drawlesschess.core.engine

import com.drawlesschess.core.BareKingPolicy
import com.drawlesschess.core.DeadPositionPolicy
import com.drawlesschess.core.FiftyMovePolicy
import com.drawlesschess.core.MaterialValues
import com.drawlesschess.core.RulesContractV1
import com.drawlesschess.core.StalematePolicy

/**
 * The rules interface implemented by Drawless native patch v2.
 *
 * Patch v2 fixes the RulesContractV1 schema, repetition law, and material values in native
 * search. The four values below are the complete per-request policy surface: [variant] also
 * carries the preset's stalemate law, while the other policies are explicit UCI combo options.
 */
internal data class DrawlessRulesUciV1(
    val variant: String,
    val deadPosition: String,
    val fiftyMove: String,
    val bareKing: String,
) {
    companion object {
        const val REQUIRED_PATCH_VERSION = 2
        const val PATCH_VERSION_OPTION = "Drawless Patch Version"
        const val DEAD_POSITION_OPTION = "Drawless Dead Position"
        const val FIFTY_MOVE_OPTION = "Drawless Fifty Move"
        const val BARE_KING_OPTION = "Drawless Bare King"

        val DEAD_POSITION_CHOICES = listOf("material-victory", "final-capture-victory")
        val FIFTY_MOVE_CHOICES = listOf(
            "disabled",
            "completing-player-loses",
            "forced-move-exception",
            "material-victory",
        )
        val BARE_KING_CHOICES = listOf("continue", "bare-king-loses")

        fun from(rules: RulesContractV1): DrawlessRulesUciV1 {
            require(rules.schemaVersion == 1) { "Native Drawless rules support schema v1 only" }
            require(rules.repetitionThreshold == 3) {
                "Native Drawless rules require threefold repetition"
            }
            require(rules.completingPlayerLosesRepetition) {
                "Native Drawless rules require completing-player repetition loss"
            }
            require(rules.forcedRepetitionException) {
                "Native Drawless rules require the forced-repetition exception"
            }
            require(rules.materialValues == MaterialValues()) {
                "Native Drawless rules require standard material values"
            }

            val variant = when (rules.preset) {
                RulesContractV1.Preset.DRAWLESS -> {
                    require(rules.stalemate == StalematePolicy.TRAPPED_PLAYER_LOSES) {
                        "Drawless preset requires trapped-player stalemate loss"
                    }
                    "drawless"
                }
                RulesContractV1.Preset.ESCAPE -> {
                    require(rules.stalemate == StalematePolicy.TRAPPED_PLAYER_WINS) {
                        "Escape preset requires trapped-player stalemate victory"
                    }
                    "escape"
                }
            }
            return DrawlessRulesUciV1(
                variant = variant,
                deadPosition = when (rules.deadPosition) {
                    DeadPositionPolicy.MATERIAL_VICTORY -> "material-victory"
                    DeadPositionPolicy.FINAL_CAPTURE_VICTORY -> "final-capture-victory"
                },
                fiftyMove = when (rules.fiftyMove) {
                    FiftyMovePolicy.DISABLED -> "disabled"
                    FiftyMovePolicy.COMPLETING_PLAYER_LOSES -> "completing-player-loses"
                    FiftyMovePolicy.FORCED_MOVE_EXCEPTION -> "forced-move-exception"
                    FiftyMovePolicy.MATERIAL_VICTORY -> "material-victory"
                },
                bareKing = when (rules.bareKing) {
                    BareKingPolicy.CONTINUE -> "continue"
                    BareKingPolicy.BARE_KING_LOSES -> "bare-king-loses"
                },
            )
        }
    }
}
