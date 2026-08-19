package com.drawlesschess.shared

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.ConcurrentLock
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EnginePurpose
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse

internal interface RuntimeChessEngine : ChessEngine {
    val reviewEvidenceBuildId: String
    val reviewEvidencePatchVersion: Int
    fun testingActivity(): String = "UNAVAILABLE"
    fun close()
}

/**
 * Counts actual review submissions at the shared-runtime boundary.
 *
 * A seeded GameReviewRunner response never reaches [analyze], so the delta captured when final
 * review starts is direct proof that foreground evidence was reused instead of re-requested.
 * The counter is surfaced only through test diagnostics and does not alter engine scheduling.
 */
internal class CountingRuntimeChessEngine(
    private val delegate: RuntimeChessEngine,
) : RuntimeChessEngine {
    private val countLock = ConcurrentLock()
    private var reviewRequests = 0

    override val reviewEvidenceBuildId: String = delegate.reviewEvidenceBuildId
    override val reviewEvidencePatchVersion: Int = delegate.reviewEvidencePatchVersion

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        if (request.purpose == EnginePurpose.REVIEW) {
            countLock.withLock { reviewRequests++ }
        }
        return delegate.analyze(request, onResult)
    }

    fun reviewRequestCountForTesting(): Int = countLock.withLock { reviewRequests }

    override fun testingActivity(): String = delegate.testingActivity()

    override fun close() = delegate.close()
}

internal expect fun createRuntimeEngine(): RuntimeChessEngine

/**
 * Runs final-review planning away from the platform UI thread.
 *
 * Building a review plan replays the complete game and materializes every player-decision
 * prefix. That work is intentionally separate from the asynchronous engine requests themselves:
 * doing it inline would still block SwiftUI at the exact moment the result is published.
 */
internal expect fun scheduleRuntimeReviewPreparation(action: () -> Unit)

internal expect fun runtimeReviewPreparationActivityForTesting(): String
