package com.drawlesschess.shared

internal actual fun createRuntimeEngine(): RuntimeChessEngine = DeterministicOfflineEngine()
