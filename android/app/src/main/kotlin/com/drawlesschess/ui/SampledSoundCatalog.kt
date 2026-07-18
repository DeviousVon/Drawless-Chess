// Generated resource catalog for the sampled foley pack.
package com.drawlesschess.ui

import com.drawlesschess.R

internal object SampledSoundCatalog {
    val moves = intArrayOf(
        R.raw.chess_move_wood_01,
        R.raw.chess_move_wood_02,
        R.raw.chess_move_wood_03,
        R.raw.chess_move_wood_04,
        R.raw.chess_move_wood_05,
        R.raw.chess_move_wood_06,
        R.raw.chess_move_wood_07,
        R.raw.chess_move_wood_08,
        R.raw.chess_move_wood_09,
        R.raw.chess_move_wood_10,
        R.raw.chess_move_wood_11,
        R.raw.chess_move_wood_12,
        R.raw.chess_move_wood_13,
        R.raw.chess_move_wood_14,
        R.raw.chess_move_wood_15,
        R.raw.chess_move_wood_16,
        R.raw.chess_move_wood_17,
        R.raw.chess_move_wood_18,
        R.raw.chess_move_wood_19,
        R.raw.chess_move_wood_20,
        R.raw.chess_move_wood_21,
        R.raw.chess_move_wood_22,
        R.raw.chess_move_wood_23,
        R.raw.chess_move_wood_24,
        R.raw.chess_move_wood_25,
        R.raw.chess_move_wood_26,
        R.raw.chess_move_wood_27,
        R.raw.chess_move_wood_28,
        R.raw.chess_move_wood_29,
        R.raw.chess_move_wood_30,
        R.raw.chess_move_wood_31,
        R.raw.chess_move_wood_32,
        R.raw.chess_move_wood_33,
        R.raw.chess_move_wood_34,
        R.raw.chess_move_wood_35,
        R.raw.chess_move_wood_36,
        R.raw.chess_move_wood_37,
        R.raw.chess_move_wood_38,
        R.raw.chess_move_wood_39,
        R.raw.chess_move_wood_40,
        R.raw.chess_move_wood_41,
        R.raw.chess_move_wood_42,
        R.raw.chess_move_wood_43,
        R.raw.chess_move_wood_44,
        R.raw.chess_move_wood_45,
        R.raw.chess_move_wood_46,
        R.raw.chess_move_wood_47,
        R.raw.chess_move_wood_48,
        R.raw.chess_move_wood_49,
        R.raw.chess_move_wood_50,
    )

    // FFmpeg volumedetect measured this at -22.3 dB mean: the midpoint of the move set.
    val volumePreview: Int
        get() = moves[16]

    val captures = intArrayOf(
        R.raw.chess_capture_wood_01,
        R.raw.chess_capture_wood_02,
        R.raw.chess_capture_wood_03,
        R.raw.chess_capture_wood_04,
        R.raw.chess_capture_wood_05,
        R.raw.chess_capture_wood_06,
        R.raw.chess_capture_wood_07,
        R.raw.chess_capture_wood_08,
        R.raw.chess_capture_wood_09,
        R.raw.chess_capture_wood_10,
        R.raw.chess_capture_wood_11,
        R.raw.chess_capture_wood_12,
    )

    val castles = intArrayOf(
        R.raw.chess_castle_wood_01,
        R.raw.chess_castle_wood_02,
        R.raw.chess_castle_wood_03,
        R.raw.chess_castle_wood_04,
        R.raw.chess_castle_wood_05,
        R.raw.chess_castle_wood_06,
    )

    val fireworkLow = intArrayOf(
        R.raw.chess_firework_low_01,
        R.raw.chess_firework_low_02,
    )

    val fireworkMid = intArrayOf(
        R.raw.chess_firework_mid_01,
        R.raw.chess_firework_mid_02,
    )

    val fireworkHigh = intArrayOf(
        R.raw.chess_firework_high_01,
        R.raw.chess_firework_high_02,
    )

    val glassImpact = intArrayOf(
        R.raw.chess_glass_impact_01,
        R.raw.chess_glass_impact_02,
        R.raw.chess_glass_impact_03,
    )

    val glassFracture = intArrayOf(
        R.raw.chess_glass_fracture_01,
        R.raw.chess_glass_fracture_02,
        R.raw.chess_glass_fracture_03,
    )

    val glassShards = intArrayOf(
        R.raw.chess_glass_shards_01,
        R.raw.chess_glass_shards_02,
        R.raw.chess_glass_shards_03,
    )

    val checkAccents = intArrayOf(
        R.raw.chess_check_crystal_01,
        R.raw.chess_check_crystal_02,
        R.raw.chess_check_crystal_03,
        R.raw.chess_check_crystal_04,
    )

    val promotions = intArrayOf(
        R.raw.chess_promotion_01,
        R.raw.chess_promotion_02,
        R.raw.chess_promotion_03,
        R.raw.chess_promotion_04,
    )

    val hints = intArrayOf(
        R.raw.chess_hint_01,
        R.raw.chess_hint_02,
        R.raw.chess_hint_03,
    )

    val lowTime = intArrayOf(
        R.raw.chess_low_time_01,
        R.raw.chess_low_time_02,
        R.raw.chess_low_time_03,
        R.raw.chess_low_time_04,
    )

    val gameStart = intArrayOf(
        R.raw.chess_game_start_01,
        R.raw.chess_game_start_02,
        R.raw.chess_game_start_03,
    )

    val undo = intArrayOf(
        R.raw.chess_undo_01,
        R.raw.chess_undo_02,
        R.raw.chess_undo_03,
    )

    val all = arrayOf(moves, captures, castles, fireworkLow, fireworkMid, fireworkHigh, glassImpact, glassFracture, glassShards, checkAccents, promotions, hints, lowTime, gameStart, undo).flatMap { it.asIterable() }.distinct().toIntArray()
}
