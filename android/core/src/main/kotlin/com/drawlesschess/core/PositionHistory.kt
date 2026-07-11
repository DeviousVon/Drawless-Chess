package com.drawlesschess.core

class PositionHistory private constructor(
    private val ordered: List<PositionKey>,
    private val counts: Map<PositionKey, Int>,
) {
    val size: Int get() = ordered.size
    val current: PositionKey get() = ordered.last()

    fun occurrences(key: PositionKey): Int = counts[key] ?: 0

    fun record(key: PositionKey): PositionHistory = PositionHistory(
        ordered = ordered + key,
        counts = counts + (key to occurrences(key) + 1),
    )

    fun keys(): List<PositionKey> = ordered.toList()

    companion object {
        fun startingAt(initial: PositionKey): PositionHistory = PositionHistory(
            ordered = listOf(initial),
            counts = mapOf(initial to 1),
        )
    }
}
