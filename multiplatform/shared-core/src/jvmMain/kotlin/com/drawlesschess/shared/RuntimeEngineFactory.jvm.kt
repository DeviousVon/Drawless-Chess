package com.drawlesschess.shared

import java.util.concurrent.Executors

internal actual fun createRuntimeEngine(): RuntimeChessEngine = DeterministicOfflineEngine()

private val runtimeReviewPreparationExecutor = Executors.newSingleThreadExecutor { action ->
    Thread(action, "drawless-review-preparation").apply { isDaemon = true }
}

internal actual fun scheduleRuntimeReviewPreparation(action: () -> Unit) {
    runtimeReviewPreparationExecutor.execute(action)
}

internal actual fun runtimeReviewPreparationActivityForTesting(): String = "DISABLED"
