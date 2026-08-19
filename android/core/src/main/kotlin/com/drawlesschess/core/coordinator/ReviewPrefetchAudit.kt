package com.drawlesschess.core.coordinator

import com.drawlesschess.core.UciMove
import com.drawlesschess.core.engine.GameReviewRootKey

/** Exact review work identity. [rootKey] retains every evaluation-affecting input. */
data class ReviewPrefetchAuditKey(
    val rootKey: GameReviewRootKey,
    val playedMove: UciMove? = null,
) {
    val kind: ReviewPrefetchWorkKind
        get() = if (playedMove == null) ReviewPrefetchWorkKind.ROOT else ReviewPrefetchWorkKind.ADJACENT

    val ply: Int get() = rootKey.ply

    /** Compact stable label for device logs; equality must always use this data class itself. */
    val diagnosticId: String
        get() = buildString {
            append(if (playedMove == null) "root" else "adjacent")
            append("|game=")
            append(rootKey.gameId)
            append("|ply=")
            append(rootKey.ply)
            append("|position=")
            append(rootKey.positionId)
            append("|schema=")
            append(rootKey.evidenceSchemaVersion)
            append("|analysis=")
            append(rootKey.analysisVersion)
            append("|movetime=")
            append(rootKey.limits.moveTimeMillis)
            append("|multipv=")
            append(rootKey.limits.multiPv)
            playedMove?.let {
                append("|played=")
                append(it.value)
            }
        }
}

enum class ReviewPrefetchWorkKind {
    ROOT,
    ADJACENT,
}

enum class ReviewPrefetchAuditStage {
    PLANNED,
    SUBMITTED,
    ACCEPTED,
    CHECKPOINTED,
    CANCELLED,
    REJECTED,
}

/** One immutable append-only fact from the coordinator's real review scheduling path. */
data class ReviewPrefetchAuditEvent(
    val sequence: Long,
    val coordinatorRevision: Long,
    val stage: ReviewPrefetchAuditStage,
    val key: ReviewPrefetchAuditKey,
    val requestId: String?,
    val reason: String,
) {
    init {
        require(sequence > 0L)
        require(reason.isNotBlank())
    }
}

/** Exact coverage of played player decisions at one coordinator revision. */
data class ReviewPrefetchCoverageSnapshot(
    val gameId: String,
    val coordinatorRevision: Long,
    val terminal: Boolean,
    val expectedPlayedRoots: List<ReviewPrefetchAuditKey>,
    val acceptedPlayedRoots: List<ReviewPrefetchAuditKey>,
    val missingPlayedRoots: List<ReviewPrefetchAuditKey>,
    val requiredAdjacent: List<ReviewPrefetchAuditKey>,
    val acceptedAdjacent: List<ReviewPrefetchAuditKey>,
    val missingAdjacent: List<ReviewPrefetchAuditKey>,
    val speculativeCurrentRoot: ReviewPrefetchAuditKey?,
    val activeWork: ReviewPrefetchAuditKey?,
    val pendingWork: List<ReviewPrefetchAuditKey>,
    val fullyCoveredPlayedMoves: Int,
    val latestEventSequence: Long,
) {
    val expectedPlayedMoveCount: Int get() = expectedPlayedRoots.size
    val pendingWorkCount: Int get() = pendingWork.size
}
