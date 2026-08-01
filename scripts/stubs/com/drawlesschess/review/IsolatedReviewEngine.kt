package com.drawlesschess.review

import android.content.Context
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse

/** Host-structure stub for the Android service-backed review engine. */
class IsolatedReviewEngine(context: Context) : ChessEngine, AutoCloseable {
    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = EngineCancellation {}

    override fun close() = Unit
}
