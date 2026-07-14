package com.drawlesschess.ui

import com.drawlesschess.core.Side
import kotlin.random.Random

enum class StartingColor {
    RANDOM,
    WHITE,
    BLACK;

    internal fun resolve(randomBoolean: () -> Boolean = { Random.nextBoolean() }): Side = when (this) {
        RANDOM -> if (randomBoolean()) Side.WHITE else Side.BLACK
        WHITE -> Side.WHITE
        BLACK -> Side.BLACK
    }

    companion object {
        internal fun fromResolvedSide(side: Side): StartingColor = when (side) {
            Side.WHITE -> WHITE
            Side.BLACK -> BLACK
        }
    }
}
