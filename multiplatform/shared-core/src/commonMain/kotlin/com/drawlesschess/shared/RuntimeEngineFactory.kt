package com.drawlesschess.shared

import com.drawlesschess.core.ChessEngine

internal interface RuntimeChessEngine : ChessEngine {
    fun close()
}

internal expect fun createRuntimeEngine(): RuntimeChessEngine
