package com.drawlesschess.shared

import com.drawlesschess.core.ChessEngine

internal interface RuntimeChessEngine : ChessEngine {
    val reviewEvidenceBuildId: String
    val reviewEvidencePatchVersion: Int
    fun close()
}

internal expect fun createRuntimeEngine(): RuntimeChessEngine
