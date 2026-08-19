package com.drawlesschess.core.coordinator

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.engine.AnalysisRequests
import com.drawlesschess.core.engine.GameReviewAdjacentKey
import com.drawlesschess.core.engine.GameReviewAdjacentRoot
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewRoot
import com.drawlesschess.core.engine.GameReviewRootKey
import com.drawlesschess.core.engine.SeededGameReviewAdjacentRoot
import com.drawlesschess.core.engine.SeededGameReviewRoot
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class GameCoordinator private constructor(
    private val config: GameConfig,
    private val engine: ChessEngine,
    private val reviewEngine: ChessEngine,
    private val checkpointSink: CheckpointSink,
    private val timeSource: CoordinatorTimeSource,
    private val idSource: CoordinatorIdSource,
    private val botMovePresentationDelayMillis: Long,
    private val drainReviewPrefetchBacklog: Boolean,
    initialSession: GameSession,
    initialPosition: ChessPosition,
    initialClock: CoordinatorClock,
    initialMoveClocks: List<MoveClockSnapshot>,
    initialAssistance: AssistanceCounts,
    initialRevision: Long,
    initialReviewPrefetchRoots: Collection<SeededGameReviewRoot> = emptyList(),
    initialReviewPrefetchAdjacentRoots: Collection<SeededGameReviewAdjacentRoot> = emptyList(),
) {
    private data class ReviewAdjacentCandidate(
        val rootKey: GameReviewRootKey,
        val playedMove: UciMove,
    )

    private data class ReviewRootPreparation(
        val requestId: String,
        val expectedRevision: Long,
        val moves: List<UciMove>,
        val position: ChessPosition,
    )

    private data class PreparedReviewPrefetch(
        val root: GameReviewRoot?,
        val adjacent: GameReviewAdjacentRoot?,
        val expectedRevision: Long,
    )

    init {
        require(botMovePresentationDelayMillis >= 0) { "Bot move presentation delay must not be negative" }
    }

    private val lock = ConcurrentLock()
    private val engineInvocationLock = ConcurrentLock()
    private val reviewInvocationLock = ConcurrentLock()
    private val reviewSharesGameplayEngine = reviewEngine === engine
    private var session = initialSession
    private var position = initialPosition
    private var clock = initialClock
    private var moveClocks = initialMoveClocks
    private var assistance = initialAssistance
    private var revision = initialRevision
    private var started = false
    private var closed = false
    private var activeRequestId: String? = null
    private var activeRequestPurpose: EnginePurpose? = null
    private var activeCancellation: EngineCancellation? = null
    private var activeReviewPrefetchRoot: GameReviewRoot? = null
    private var activeReviewPrefetchAdjacentRoot: GameReviewAdjacentRoot? = null
    private var activeReviewPrefetchRevision: Long? = null
    private var reviewPrefetchEnabled = false
    private var reviewPrefetchEnableTransitions = 0L
    private var reviewPrefetchDisableTransitions = 0L
    private val reviewPrefetchRootsByKey =
        linkedMapOf<GameReviewRootKey, SeededGameReviewRoot>().apply {
            initialReviewPrefetchRoots.forEach { seed -> put(seed.key, seed) }
        }
    private val reviewPrefetchAdjacentRootsByKey =
        linkedMapOf<GameReviewAdjacentKey, SeededGameReviewAdjacentRoot>().apply {
            initialReviewPrefetchAdjacentRoots.forEach { seed -> put(seed.key, seed) }
        }
    private val reviewPrefetchRootKeysByPly = linkedMapOf<Int, GameReviewRootKey>().apply {
        initialReviewPrefetchRoots.forEach { seed -> put(seed.key.ply, seed.key) }
    }
    private val reviewAdjacentCandidates = linkedSetOf<ReviewAdjacentCandidate>()
    private var adjacentPrefetchRevision: Long? = null
    private var reviewBackfillMoves: List<UciMove>? = null
    private var reviewBackfillRoots: List<GameReviewRoot> = emptyList()
    private var engineError: String? = null
    private val reviewPrefetchAuditEvents = mutableListOf<ReviewPrefetchAuditEvent>()
    private var reviewPrefetchAuditSequence = 0L
    private var reviewPrefetchAuditDrainSequence = 0L
    private var terminalReviewPrefetchCoverage: ReviewPrefetchCoverageSnapshot? = null

    init {
        rebuildReviewAdjacentCandidates()
        reviewPrefetchRootsByKey.values.forEach { seed ->
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.ACCEPTED,
                key = seed.key.auditKey(),
                requestId = seed.response.requestId,
                reason = "restored_checkpoint",
            )
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.CHECKPOINTED,
                key = seed.key.auditKey(),
                requestId = seed.response.requestId,
                reason = "restored_checkpoint",
            )
        }
        reviewPrefetchAdjacentRootsByKey.values.forEach { seed ->
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.ACCEPTED,
                key = seed.key.auditKey(),
                requestId = seed.response.requestId,
                reason = "restored_checkpoint",
            )
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.CHECKPOINTED,
                key = seed.key.auditKey(),
                requestId = seed.response.requestId,
                reason = "restored_checkpoint",
            )
        }
        if (session.outcome != null) captureTerminalReviewPrefetchCoverageLocked()
    }

    init {
        rebuildReviewAdjacentCandidates()
    }

    fun start() {
        lock.withLock {
            check(!closed) { "Coordinator is closed" }
            if (started) return
            started = true
            persistLocked()
        }
        tick()
        launchBotIfNeeded()
        launchReviewPrefetchIfNeeded()
    }

    fun close() {
        val cancellation = lock.withLock {
            if (closed) return
            closed = true
            clearActiveEngineLocked(
                reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                reviewAuditReason = "coordinator_closed",
            )
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun snapshot(): CoordinatorSnapshot = lock.withLock {
        val phase = phaseLocked()
        CoordinatorSnapshot(
            revision = revision,
            session = session,
            currentFen = position.fen(),
            phase = phase,
            clock = clock.snapshot(timeSource.now()),
            assistance = assistance,
            engineError = engineError,
        )
    }

    /**
     * Uses the isolated review engine to warm the exact full-strength review root while
     * the visible game is waiting for the player. Disabling this,
     * starting a hint, moving, pausing, undoing, resigning, timing out, or closing cancels the
     * speculative request. Completed roots are returned only after exact request/revision checks.
     */
    fun setReviewPrefetchEnabled(enabled: Boolean) {
        val cancellation = lock.withLock {
            if (closed) return
            if (reviewPrefetchEnabled != enabled) {
                reviewPrefetchEnabled = enabled
                if (enabled) {
                    reviewPrefetchEnableTransitions++
                } else {
                    reviewPrefetchDisableTransitions++
                }
            }
            if (!enabled && activeRequestPurpose == EnginePurpose.REVIEW) {
                // Disabling is an external interruption, not a failed search. Permit this one
                // queued adjacent fallback to retry when the same position is foregrounded.
                if (activeReviewPrefetchAdjacentRoot != null) adjacentPrefetchRevision = null
                clearActiveEngineLocked(
                    reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                    reviewAuditReason = "foreground_prefetch_disabled",
                )
            } else {
                null
            }
        }
        if (enabled) {
            cancelIgnoringFailure(cancellation)
        } else {
            cancelAndDrainReviewLaunch(cancellation)
        }
        // If a concurrent enable lost tryLock while this call drained the prior launch, this
        // final recheck observes the latest flag and restores the eligible speculative request.
        launchReviewPrefetchIfNeeded()
    }

    /** Lock-safe lifecycle telemetry for physical Apple foreground-prefetch verification. */
    fun reviewPrefetchLifecycleDiagnosticsForTesting(): String = lock.withLock {
        listOf(
            "prefetchEnabled=$reviewPrefetchEnabled",
            "prefetchEnableTransitions=$reviewPrefetchEnableTransitions",
            "prefetchDisableTransitions=$reviewPrefetchDisableTransitions",
        ).joinToString(";")
    }

    /** Immutable exact roots completed during foreground play, for the post-game runner. */
    fun completedReviewPrefetchRoots(): List<SeededGameReviewRoot> = lock.withLock {
        reviewPrefetchRootsByKey.values.toList()
    }

    /** Immutable exact fallback searches completed during otherwise-idle foreground play. */
    fun completedReviewPrefetchAdjacentRoots(): List<SeededGameReviewAdjacentRoot> = lock.withLock {
        reviewPrefetchAdjacentRootsByKey.values.toList()
    }

    /** Non-destructive lock-safe read of immutable audit facts after [afterSequenceExclusive]. */
    fun reviewPrefetchAuditEvents(
        afterSequenceExclusive: Long = 0L,
    ): List<ReviewPrefetchAuditEvent> = lock.withLock {
        reviewPrefetchAuditEvents.filter { event -> event.sequence > afterSequenceExclusive }
    }

    /**
     * Returns audit facts not returned by an earlier drain. The append-only history remains
     * available through [reviewPrefetchAuditEvents]; draining advances only this reader cursor.
     */
    fun drainReviewPrefetchAuditEvents(): List<ReviewPrefetchAuditEvent> = lock.withLock {
        reviewPrefetchAuditEvents
            .filter { event -> event.sequence > reviewPrefetchAuditDrainSequence }
            .also { drained ->
                drained.lastOrNull()?.let { event -> reviewPrefetchAuditDrainSequence = event.sequence }
            }
    }

    /** Exact played-decision coverage; unlike queue activity, missing roots can never look idle. */
    fun reviewPrefetchCoverageSnapshot(): ReviewPrefetchCoverageSnapshot = lock.withLock {
        buildReviewPrefetchCoverageLocked()
    }

    /** Immutable coverage captured at the transition to a terminal outcome. */
    fun terminalReviewPrefetchCoverageSnapshot(): ReviewPrefetchCoverageSnapshot? = lock.withLock {
        terminalReviewPrefetchCoverage
    }

    /** Test telemetry for proving that the iOS catch-up queue is actually complete before game end. */
    fun pendingReviewPrefetchWorkForTesting(): Int = lock.withLock {
        // Terminal commit freezes exact coverage before the engine handoff. Rebuilding it replays
        // planning and legal transitions on every Debug accessibility poll even though the result
        // cannot change after game end.
        terminalReviewPrefetchCoverage
            ?.takeIf { coverage ->
                session.outcome != null && coverage.coordinatorRevision == revision
            }
            ?.pendingWorkCount
            ?: buildReviewPrefetchCoverageLocked().pendingWorkCount
    }

    fun playHuman(move: UciMove) {
        val now = timeSource.now()
        var shouldLaunchBot = false
        var clockExpired = false
        var prefetchCancellation: EngineCancellation? = null
        try {
            lock.withLock {
                requireStartedLocked()
                require(session.outcome == null) { "Game is complete" }
                require(!clock.paused) { "Game is paused" }
                require(session.sideToMove == config.humanSide) { "It is not the human player's turn" }
                require(activeRequestPurpose != EnginePurpose.HINT) { "Hint analysis is in progress" }
                if (expireClockLocked(now)) {
                    if (activeRequestPurpose == EnginePurpose.REVIEW) {
                        prefetchCancellation = clearActiveEngineLocked(
                            reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                            reviewAuditReason = "human_clock_expired",
                        )
                    }
                    captureTerminalReviewPrefetchCoverageLocked()
                    clockExpired = true
                } else {
                    // Validate before releasing the speculative slot. An illegal UI move must not
                    // detach a live engine request without also obtaining its cancellation handle.
                    val preparedTransition = ChessAdapter.prepareTransition(position, move)
                    if (activeRequestPurpose == EnginePurpose.REVIEW) {
                        prefetchCancellation = clearActiveEngineLocked(
                            reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                            reviewAuditReason = "human_move_committed",
                        )
                    }
                    commitMoveLocked(
                        preparedTransition.transition,
                        preparedTransition.positionAfter,
                        now,
                    )
                    shouldLaunchBot = session.outcome == null && session.sideToMove != config.humanSide
                }
            }
        } catch (error: Throwable) {
            cancelAndDrainReviewLaunch(prefetchCancellation)
            throw error
        }
        cancelIgnoringFailure(prefetchCancellation)
        if (shouldLaunchBot) {
            launchBotIfNeeded()
        } else {
            // A terminal move or timeout hands the engine to post-game review immediately.
            // Do not return while a speculative analyze call is still publishing its handle.
            cancelAndDrainAllEngineLaunches(null)
        }
        if (clockExpired) return
    }

    fun tick() {
        var clockExpired = false
        val cancellation = lock.withLock {
            if (!started || closed || session.outcome != null || clock.paused) return
            if (expireClockLocked(timeSource.now())) {
                clockExpired = true
                clearActiveEngineLocked(
                    reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                    reviewAuditReason = "clock_expired",
                ).also { captureTerminalReviewPrefetchCoverageLocked() }
            } else {
                null
            }
        }
        if (clockExpired) cancelAndDrainAllEngineLaunches(cancellation) else cancelIgnoringFailure(cancellation)
    }

    fun pause() {
        val cancellation: EngineCancellation?
        lock.withLock {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot be paused" }
            require(session.outcome == null) { "Game is complete" }
            require(!clock.paused) { "Game is already paused" }
            clock = clock.pause(timeSource.now())
            assistance = assistance.copy(pauses = assistance.pauses + 1)
            engineError = null
            cancellation = clearActiveEngineLocked(
                reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                reviewAuditReason = "game_paused",
            )
            revision++
            persistLocked()
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun resume() {
        lock.withLock {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot be paused" }
            require(clock.paused) { "Game is not paused" }
            clock = clock.resume(session.sideToMove, timeSource.now())
            revision++
            persistLocked()
        }
        launchBotIfNeeded()
        launchReviewPrefetchIfNeeded()
    }

    fun markHintUsed() {
        val cancellation: EngineCancellation?
        lock.withLock {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot use hints" }
            require(session.outcome == null) { "Game is complete" }
            cancellation = if (activeRequestPurpose == EnginePurpose.REVIEW) {
                clearActiveEngineLocked(
                    reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                    reviewAuditReason = "hint_marked_used",
                )
            } else {
                null
            }
            assistance = assistance.copy(hints = assistance.hints + 1)
            revision++
            persistLocked()
        }
        cancelAndDrainReviewLaunch(cancellation)
        launchReviewPrefetchIfNeeded()
    }

    /**
     * Runs a full-strength hint through the same serialized engine session used by the bot.
     * The expected position marker rejects effects emitted for a position that has already
     * changed. Starting a hint owns the coordinator's sole engine request slot until the
     * result completes or a game action cancels it.
     */
    fun requestHint(
        expectedPositionId: String,
        onResult: (Result<EngineResponse>) -> Unit,
    ) {
        var launchFailure: Throwable? = null
        try {
            engineInvocationLock.withLock hintLaunch@{
                var prefetchCancellation: EngineCancellation? = null
                val request = try {
                    lock.withLock {
                        requireStartedLocked()
                        require(config.mode == GameMode.CASUAL) { "Rated games cannot use hints" }
                        require(session.outcome == null) { "Game is complete" }
                        require(!clock.paused) { "Game is paused" }
                        require(session.sideToMove == config.humanSide) { "Hints are available only on your turn" }
                        require(session.positionId == expectedPositionId) {
                            "The position changed before hint analysis started"
                        }
                        if (activeRequestPurpose == EnginePurpose.REVIEW) {
                            if (activeReviewPrefetchAdjacentRoot != null) {
                                adjacentPrefetchRevision = null
                            }
                            prefetchCancellation = clearActiveEngineLocked(
                                reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                                reviewAuditReason = "hint_requested",
                            )
                        }
                        require(activeRequestId == null) { "Hint analysis is already in progress" }
                        val clockExpired = expireClockLocked(timeSource.now())
                        if (clockExpired) captureTerminalReviewPrefetchCoverageLocked()
                        require(!clockExpired) { "Game is complete" }

                        val requestId = idSource.nextId()
                        AnalysisRequests.hint(
                            requestId = requestId,
                            gameId = config.gameId,
                            positionId = session.positionId,
                            initialFen = config.initialFen,
                            moves = session.moves.map { it.move },
                            rules = config.rules,
                            mode = config.mode,
                        ).also {
                            activeRequestId = requestId
                            activeRequestPurpose = EnginePurpose.HINT
                        }
                    }
                } catch (error: Throwable) {
                    cancelIgnoringFailure(prefetchCancellation)
                    throw error
                }
                cancelIgnoringFailure(prefetchCancellation)

                val cancellation = try {
                    engine.analyze(request) { result -> handleHintResult(request, result, onResult) }
                } catch (error: Throwable) {
                    val shouldDeliver = lock.withLock {
                        if (activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.HINT) {
                            false
                        } else {
                            clearActiveEngineLocked()
                            revision++
                            persistLocked()
                            true
                        }
                    }
                    if (shouldDeliver) launchFailure = error
                    return@hintLaunch
                }

                var cancelImmediately = false
                lock.withLock {
                    if (activeRequestId == request.requestId && activeRequestPurpose == EnginePurpose.HINT) {
                        activeCancellation = cancellation
                    } else {
                        cancelImmediately = true
                    }
                }
                if (cancelImmediately) cancelIgnoringFailure(cancellation)
            }
        } catch (error: Throwable) {
            // A callback may have skipped speculative work while this foreground attempt owned
            // the gate. If validation rejected the attempt, restore any still-eligible prefetch.
            launchReviewPrefetchIfNeeded()
            throw error
        }
        launchFailure?.let { error ->
            runCatching { onResult(Result.failure(error)) }
            launchReviewPrefetchIfNeeded()
        }
    }

    fun undoLastHumanTurn() {
        val cancellation: EngineCancellation?
        lock.withLock {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot undo" }
            require(session.outcome == null) { "Game is complete" }
            val lastHumanIndex = session.moves.indexOfLast { it.mover == config.humanSide }
            require(lastHumanIndex >= 0) { "No human move is available to undo" }
            cancellation = clearActiveEngineLocked(
                reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                reviewAuditReason = "history_undo",
            )
            val retained = session.moves.take(lastHumanIndex).map { it.move }
            val rebuilt = rebuild(config, retained)
            session = rebuilt.first
            position = rebuilt.second
            trimReviewPrefetchToCurrentHistoryLocked()
            moveClocks = moveClocks.take(retained.size)
            clock = restoredClockAfterUndo(retained.size, timeSource.now())
            assistance = assistance.copy(undos = assistance.undos + 1)
            engineError = null
            revision++
            persistLocked()
        }
        cancelIgnoringFailure(cancellation)
        launchBotIfNeeded()
        launchReviewPrefetchIfNeeded()
    }

    fun resignHuman() {
        val cancellation: EngineCancellation?
        lock.withLock {
            requireStartedLocked()
            require(session.outcome == null) { "Game is complete" }
            val outcome = GameOutcome(
                winner = config.humanSide.opposite(),
                reason = EndReason.RESIGNATION,
            )
            session = session.copy(outcome = outcome)
            clock = clock.stop(timeSource.now())
            engineError = null
            cancellation = clearActiveEngineLocked(
                reviewAuditStage = ReviewPrefetchAuditStage.CANCELLED,
                reviewAuditReason = "human_resigned",
            )
            revision++
            persistLocked()
            captureTerminalReviewPrefetchCoverageLocked()
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun retryBot() {
        lock.withLock {
            requireStartedLocked()
            require(session.outcome == null && session.sideToMove != config.humanSide)
            require(engineError != null) { "The bot has not failed" }
            engineError = null
            revision++
            persistLocked()
        }
        launchBotIfNeeded()
    }

    fun checkpoint(): CoordinatorCheckpoint = lock.withLock { checkpointLocked() }

    private fun launchBotIfNeeded() {
        engineInvocationLock.withLock engineLaunch@{
            val request = lock.withLock {
                if (!started || closed || session.outcome != null || clock.paused ||
                    session.sideToMove == config.humanSide || activeRequestId != null || engineError != null) {
                    return@engineLaunch
                }
                val requestId = idSource.nextId()
                activeRequestId = requestId
                activeRequestPurpose = EnginePurpose.BOT_MOVE
                EngineRequest(
                    requestId = requestId,
                    gameId = config.gameId,
                    positionId = session.positionId,
                    initialFen = config.initialFen,
                    moves = session.moves.map { it.move },
                    rules = config.rules,
                    strength = config.engineStrength,
                    limits = config.engineLimits,
                )
            }
            val cancellation = try {
                engine.analyze(request) { result -> handleEngineResult(request, result) }
            } catch (error: Throwable) {
                lock.withLock {
                    if (activeRequestId == request.requestId) {
                        activeRequestId = null
                        activeRequestPurpose = null
                        activeCancellation = null
                        engineError = error.message ?: error::class.simpleName ?: "Engine launch failure"
                        revision++
                        persistLocked()
                    }
                }
                return@engineLaunch
            }
            var cancelImmediately = false
            lock.withLock {
                if (activeRequestId == request.requestId) {
                    activeCancellation = cancellation
                } else {
                    cancelImmediately = true
                }
            }
            if (cancelImmediately) cancelIgnoringFailure(cancellation)
        }
    }

    private fun handleEngineResult(request: EngineRequest, result: Result<EngineResponse>) {
        lock.withLock {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.BOT_MOVE ||
                session.outcome != null || clock.paused) return@withLock
            activeRequestId = null
            activeRequestPurpose = null
            activeCancellation = null
            val response = result.getOrElse { error ->
                engineError = error.message ?: error::class.simpleName ?: "Engine failure"
                revision++
                persistLocked()
                return@withLock
            }
            if (!response.matches(request) || session.positionId != request.positionId) {
                engineError = "Engine response identity does not match the active position"
                revision++
                persistLocked()
                return@withLock
            }
            val now = timeSource.now()
            if (expireClockLocked(now)) {
                captureTerminalReviewPrefetchCoverageLocked()
                return@withLock
            }
            try {
                val preparedTransition = ChessAdapter.prepareTransition(position, response.bestMove)
                commitMoveLocked(
                    transition = preparedTransition.transition,
                    after = preparedTransition.positionAfter,
                    now = now,
                    nextSideStartDelayMillis = botMovePresentationDelayMillis,
                )
            } catch (error: IllegalArgumentException) {
                engineError = "Engine returned illegal move ${response.bestMove.value}: ${error.message}"
                revision++
                persistLocked()
            }
        }
        launchReviewPrefetchIfNeeded()
    }

    private fun handleHintResult(
        request: EngineRequest,
        result: Result<EngineResponse>,
        onResult: (Result<EngineResponse>) -> Unit,
    ) {
        var delivery: Result<EngineResponse>? = null
        var handled = false
        lock.withLock {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.HINT ||
                session.outcome != null || clock.paused || session.positionId != request.positionId) return@withLock

            handled = true
            clearActiveEngineLocked()
            if (expireClockLocked(timeSource.now())) {
                captureTerminalReviewPrefetchCoverageLocked()
                return@withLock
            }

            val validatedResult = result.fold(
                onSuccess = { response ->
                    when {
                        !response.matches(request) -> Result.failure(
                            IllegalStateException("Hint response identity does not match the active position"),
                        )
                        runCatching { ChessAdapter.transition(position, response.bestMove) }.isFailure -> Result.failure(
                            IllegalStateException("Engine returned illegal hint ${response.bestMove.value}"),
                        )
                        else -> Result.success(response)
                    }
                },
                onFailure = { Result.failure(it) },
            )
            delivery = validatedResult
            if (validatedResult.isSuccess) {
                assistance = assistance.copy(hints = assistance.hints + 1)
                revision++
                persistLocked()
            }
        }
        delivery?.let { value -> runCatching { onResult(value) } }
        if (handled) launchReviewPrefetchIfNeeded()
    }

    private fun launchReviewPrefetchIfNeeded() {
        // Production review owns a different process and launch gate. A slow bind, serialization,
        // or test double must never hold the gameplay gate needed by a move, hint, bot, or undo.
        // The shared gate remains only for callers which explicitly supply one engine for both.
        val invocationLock = if (reviewSharesGameplayEngine) engineInvocationLock else reviewInvocationLock
        if (!invocationLock.tryAcquire()) return
        val retryAfterStaleLaunch: Boolean
        try {
            retryAfterStaleLaunch = launchPreparedReviewPrefetch()
        } finally {
            invocationLock.release()
        }
        // A separate review engine can finish publishing its cancellation after gameplay has
        // already reached the next player position. Restore that newest eligible root now.
        if (retryAfterStaleLaunch) launchReviewPrefetchIfNeeded()
    }

    private fun launchPreparedReviewPrefetch(): Boolean {
        val prepared = prepareReviewPrefetch() ?: return false
        val root = prepared.root
        val adjacent = prepared.adjacent
        val request = root?.request ?: requireNotNull(adjacent).request
        val auditKey = root?.key?.auditKey() ?: requireNotNull(adjacent).key.auditKey()
        val callbackAccepted = AtomicBoolean(false)
        val continuationRequested = AtomicBoolean(false)
        lock.withLock {
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.SUBMITTED,
                key = auditKey,
                requestId = request.requestId,
                reason = "engine_analyze",
            )
        }
        val cancellation = try {
            reviewEngine.analyze(request) { result ->
                handleReviewPrefetchResult(
                    root = root,
                    adjacent = adjacent,
                    expectedRevision = prepared.expectedRevision,
                    result = result,
                    callbackAccepted = callbackAccepted,
                    continuationRequested = continuationRequested,
                )
            }
        } catch (error: Throwable) {
            lock.withLock {
                if (activeRequestId == request.requestId &&
                    activeRequestPurpose == EnginePurpose.REVIEW
                ) {
                    clearActiveEngineLocked(
                        reviewAuditStage = ReviewPrefetchAuditStage.REJECTED,
                        reviewAuditReason = error.reviewAuditReason("engine_analyze_threw"),
                    )
                } else {
                    appendReviewPrefetchAuditLocked(
                        stage = ReviewPrefetchAuditStage.REJECTED,
                        key = auditKey,
                        requestId = request.requestId,
                        reason = error.reviewAuditReason("engine_analyze_threw_after_invalidation"),
                    )
                }
            }
            return false
        }

        val cancelImmediately = lock.withLock {
            if (activeRequestId == request.requestId &&
                activeRequestPurpose == EnginePurpose.REVIEW &&
                activeReviewPrefetchRoot === root && activeReviewPrefetchAdjacentRoot === adjacent &&
                activeReviewPrefetchRevision == prepared.expectedRevision
            ) {
                activeCancellation = cancellation
                false
            } else {
                true
            }
        }
        if (cancelImmediately) cancelIgnoringFailure(cancellation)
        // Synchronous completion legitimately clears the active slot before analyze returns.
        // Retry only for a continuation which lost the gate, or when some *other* action made
        // this launch stale while its handle was pending. A synchronous failure must not loop.
        return continuationRequested.load() ||
            (cancelImmediately && !callbackAccepted.load())
    }

    /**
     * Copies the current lightweight inputs under the coordinator lock, then builds FEN/request
     * structures outside it. Result callbacks and UI actions can therefore never queue behind
     * historical reconstruction.
     */
    private fun prepareReviewPrefetch(): PreparedReviewPrefetch? {
        val rootPreparation = lock.withLock {
            if (!reviewPrefetchEligibleLocked()) return null
            ReviewRootPreparation(
                requestId = idSource.nextId(),
                expectedRevision = revision,
                moves = session.moves.map { it.move },
                position = position,
            )
        }
        val currentRoot = GameReviewPlanner.playerRootAtPosition(
            requestId = rootPreparation.requestId,
            gameId = config.gameId,
            initialFen = config.initialFen,
            moves = rootPreparation.moves,
            rules = config.rules,
            position = rootPreparation.position,
        )

        // Apple has one process-global native engine rather than Android's isolated review
        // process. While its shared FIFO is otherwise idle, use the rest of a long player think
        // to recover any exact roots that were cancelled by earlier quick moves. Cache the
        // linear replay plan by move history so draining several roots never becomes quadratic.
        val historicalRoots = if (drainReviewPrefetchBacklog) {
            val cached = lock.withLock {
                reviewBackfillRoots.takeIf { reviewBackfillMoves == rootPreparation.moves }
            }
            cached ?: GameReviewPlanner.playerPlan(
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = rootPreparation.moves,
                rules = config.rules,
                playerSide = config.humanSide,
            ).roots
        } else {
            emptyList()
        }

        val adjacentPreparation = lock.withLock {
            if (!reviewPrefetchEligibleLocked() || revision != rootPreparation.expectedRevision) return null
            if (drainReviewPrefetchBacklog && reviewBackfillMoves != rootPreparation.moves) {
                reviewBackfillMoves = rootPreparation.moves
                reviewBackfillRoots = historicalRoots
            }
            reviewPrefetchRootKeysByPly[currentRoot.ply] = currentRoot.key
            if (drainReviewPrefetchBacklog) {
                val missingHistoricalRoot = historicalRoots.firstOrNull { root ->
                    root.key !in reviewPrefetchRootsByKey
                }
                val oldestAdjacentCandidate = reviewAdjacentCandidates.minByOrNull { candidate ->
                    candidate.rootKey.ply
                }
                // Finish already-played decisions before speculating about the move the player
                // has not made yet. A normal sub-700 ms turn often has time for only one 350 ms
                // search; putting the current root first on every turn could therefore starve an
                // early off-MultiPV helper forever and make final Review visibly start at move 1.
                if (missingHistoricalRoot != null &&
                    (oldestAdjacentCandidate == null ||
                        missingHistoricalRoot.ply <= oldestAdjacentCandidate.rootKey.ply)
                ) {
                    reviewPrefetchRootKeysByPly[missingHistoricalRoot.ply] = missingHistoricalRoot.key
                    reserveReviewPrefetchLocked(
                        missingHistoricalRoot,
                        null,
                        rootPreparation.expectedRevision,
                        planReason = "historical_root_backfill",
                    )
                    return PreparedReviewPrefetch(
                        missingHistoricalRoot,
                        null,
                        rootPreparation.expectedRevision,
                    )
                }
                if (oldestAdjacentCandidate != null) {
                    return@withLock Triple(oldestAdjacentCandidate, idSource.nextId(), revision)
                }
                if (currentRoot.key !in reviewPrefetchRootsByKey) {
                    reserveReviewPrefetchLocked(
                        currentRoot,
                        null,
                        rootPreparation.expectedRevision,
                        planReason = "current_player_root",
                    )
                    return PreparedReviewPrefetch(currentRoot, null, rootPreparation.expectedRevision)
                }
                return null
            }
            if (currentRoot.key !in reviewPrefetchRootsByKey) {
                reserveReviewPrefetchLocked(
                    currentRoot,
                    null,
                    rootPreparation.expectedRevision,
                    planReason = "current_player_root",
                )
                return PreparedReviewPrefetch(currentRoot, null, rootPreparation.expectedRevision)
            }
            if (!drainReviewPrefetchBacklog && adjacentPrefetchRevision == revision) return null
            val candidate = reviewAdjacentCandidates.firstOrNull() ?: return null
            Triple(candidate, idSource.nextId(), revision)
        }

        val (candidate, requestId, expectedRevision) = adjacentPreparation
        val adjacent = GameReviewPlanner.adjacentRoot(
            requestId = requestId,
            rootKey = candidate.rootKey,
            playedMove = candidate.playedMove,
        )
        return lock.withLock {
            if (!reviewPrefetchEligibleLocked() || revision != expectedRevision ||
                candidate !in reviewAdjacentCandidates
            ) {
                return null
            }
            if (adjacent.key in reviewPrefetchAdjacentRootsByKey) {
                reviewAdjacentCandidates.remove(candidate)
                return null
            }
            if (!drainReviewPrefetchBacklog) adjacentPrefetchRevision = revision
            reserveReviewPrefetchLocked(
                null,
                adjacent,
                expectedRevision,
                planReason = "played_move_adjacent_fallback",
            )
            PreparedReviewPrefetch(null, adjacent, expectedRevision)
        }
    }

    private fun reviewPrefetchEligibleLocked(): Boolean =
        started && !closed && reviewPrefetchEnabled && session.outcome == null &&
            !clock.paused && session.sideToMove == config.humanSide && activeRequestId == null &&
            engineError == null

    private fun reserveReviewPrefetchLocked(
        root: GameReviewRoot?,
        adjacent: GameReviewAdjacentRoot?,
        expectedRevision: Long,
        planReason: String,
    ) {
        val request = root?.request ?: requireNotNull(adjacent).request
        activeRequestId = request.requestId
        activeRequestPurpose = EnginePurpose.REVIEW
        activeReviewPrefetchRoot = root
        activeReviewPrefetchAdjacentRoot = adjacent
        activeReviewPrefetchRevision = expectedRevision
        appendReviewPrefetchAuditLocked(
            stage = ReviewPrefetchAuditStage.PLANNED,
            key = root?.key?.auditKey() ?: requireNotNull(adjacent).key.auditKey(),
            requestId = request.requestId,
            reason = planReason,
        )
    }

    private fun handleReviewPrefetchResult(
        root: GameReviewRoot?,
        adjacent: GameReviewAdjacentRoot?,
        expectedRevision: Long,
        result: Result<EngineResponse>,
        callbackAccepted: AtomicBoolean? = null,
        continuationRequested: AtomicBoolean? = null,
    ) {
        // Identity/evidence conversion is deliberately outside the coordinator monitor.
        val seededRootResult = root?.let { value -> result.mapCatching(value::seed) }
        val seededAdjacentResult = adjacent?.let { value -> result.mapCatching(value::seed) }
        var continuePrefetch = false
        val request = root?.request ?: requireNotNull(adjacent).request
        val auditKey = root?.key?.auditKey() ?: requireNotNull(adjacent).key.auditKey()
        lock.withLock {
            if (activeRequestPurpose != EnginePurpose.REVIEW ||
                activeRequestId != request.requestId || activeReviewPrefetchRoot !== root ||
                activeReviewPrefetchAdjacentRoot !== adjacent ||
                activeReviewPrefetchRevision != expectedRevision
            ) {
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.REJECTED,
                    key = auditKey,
                    requestId = request.requestId,
                    reason = "callback_no_longer_matches_active_attempt",
                )
                return
            }
            callbackAccepted?.store(true)
            clearActiveEngineLocked()
            val lifecycleRejection = reviewPrefetchResultRejectionReasonLocked(expectedRevision)
            if (lifecycleRejection != null) {
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.REJECTED,
                    key = auditKey,
                    requestId = request.requestId,
                    reason = lifecycleRejection,
                )
                return
            }
            if (root != null) {
                val seeded = seededRootResult?.getOrNull()
                if (seeded == null) {
                    appendReviewPrefetchAuditLocked(
                        stage = ReviewPrefetchAuditStage.REJECTED,
                        key = auditKey,
                        requestId = request.requestId,
                        reason = seededRootResult?.exceptionOrNull()
                            ?.reviewAuditReason("root_evidence_rejected")
                            ?: "root_evidence_missing",
                    )
                    return
                }
                reviewPrefetchRootsByKey[seeded.key] = seeded
                enqueueReviewAdjacentCandidateLocked(seeded)
                revision++
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.ACCEPTED,
                    key = auditKey,
                    requestId = request.requestId,
                    reason = "exact_root_response_validated",
                )
                persistReviewPrefetchEvidenceLocked(auditKey, request.requestId)
                continuePrefetch = true
            } else {
                val seeded = seededAdjacentResult?.getOrNull()
                if (seeded == null) {
                    appendReviewPrefetchAuditLocked(
                        stage = ReviewPrefetchAuditStage.REJECTED,
                        key = auditKey,
                        requestId = request.requestId,
                        reason = seededAdjacentResult?.exceptionOrNull()
                            ?.reviewAuditReason("adjacent_evidence_rejected")
                            ?: "adjacent_evidence_missing",
                    )
                    return
                }
                reviewPrefetchAdjacentRootsByKey[seeded.key] = seeded
                reviewAdjacentCandidates.remove(
                    ReviewAdjacentCandidate(seeded.key.rootKey, seeded.key.playedMove),
                )
                revision++
                // Persisting review evidence advances the durable checkpoint revision, but it
                // does not create another idle player turn. Carry the one-fallback allowance
                // forward so the continuation below cannot start a second historical search.
                adjacentPrefetchRevision = if (drainReviewPrefetchBacklog) null else revision
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.ACCEPTED,
                    key = auditKey,
                    requestId = request.requestId,
                    reason = "exact_adjacent_response_validated",
                )
                persistReviewPrefetchEvidenceLocked(auditKey, request.requestId)
                continuePrefetch = true
            }
        }
        // A completed current root uses only the first 350 ms of a long think. Continue with any
        // exact played-position fallback that remains from an earlier player move.
        if (continuePrefetch) {
            continuationRequested?.store(true)
            launchReviewPrefetchIfNeeded()
        }
    }

    private fun commitMoveLocked(
        transition: MoveTransition,
        after: ChessPosition,
        now: TimeReading,
        nextSideStartDelayMillis: Long = 0,
    ) {
        enqueueReviewAdjacentCandidateLocked(transition)
        val increment = (config.timeControl as? TimeControl.Clock)?.incrementMillis ?: 0
        session = session.apply(transition)
        position = after
        clock = clock.completeMove(
            mover = transition.mover,
            nextSide = session.sideToMove,
            incrementMillis = increment,
            now = now,
            nextSideStartDelayMillis = nextSideStartDelayMillis,
        )
        if (session.outcome != null) clock = clock.stop(now)
        moveClocks = moveClocks + MoveClockSnapshot(
            ply = session.moves.size,
            whiteRemainingMillis = clock.whiteRemainingMillis,
            blackRemainingMillis = clock.blackRemainingMillis,
        )
        engineError = null
        revision++
        persistLocked()
        if (session.outcome != null) captureTerminalReviewPrefetchCoverageLocked()
    }

    private fun enqueueReviewAdjacentCandidateLocked(transition: MoveTransition) {
        if (transition.mover != config.humanSide) return
        val ply = session.moves.size + 1
        val rootKey = reviewPrefetchRootKeysByPly[ply] ?: return
        if (rootKey.positionFen != position.fen()) return
        val seededRoot = reviewPrefetchRootsByKey[rootKey] ?: return
        if (seededRoot.response.variations.any { variation ->
                variation.moves.firstOrNull() == transition.move
            }
        ) {
            return
        }
        reviewAdjacentCandidates += ReviewAdjacentCandidate(rootKey, transition.move)
    }

    /** Adds fallback work when a formerly missed exact root is recovered after its move. */
    private fun enqueueReviewAdjacentCandidateLocked(seed: SeededGameReviewRoot) {
        val playedMove = session.moves.getOrNull(seed.key.ply - 1)?.move ?: return
        if (seed.response.variations.any { variation ->
                variation.moves.firstOrNull() == playedMove
            }
        ) {
            return
        }
        val adjacentKey = GameReviewPlanner.adjacentRoot(
            requestId = "backfill-adjacent-${seed.key.ply}",
            rootKey = seed.key,
            playedMove = playedMove,
        ).key
        if (adjacentKey !in reviewPrefetchAdjacentRootsByKey) {
            reviewAdjacentCandidates += ReviewAdjacentCandidate(seed.key, playedMove)
        }
    }

    /** Recreates unfinished played-position fallback work from durable exact root evidence. */
    private fun rebuildReviewAdjacentCandidates() {
        reviewAdjacentCandidates.clear()
        reviewPrefetchRootsByKey.values
            .sortedBy { seed -> seed.key.ply }
            .forEach(::enqueueReviewAdjacentCandidateLocked)
    }

    private fun trimReviewPrefetchToCurrentHistoryLocked() {
        val moves = session.moves.map { recorded -> recorded.move }
        val expectedRoots = GameReviewPlanner.playerPlan(
            gameId = config.gameId,
            initialFen = config.initialFen,
            moves = moves,
            rules = config.rules,
            playerSide = config.humanSide,
        ).roots.toMutableList()
        if (session.outcome == null && position.sideToMove == config.humanSide) {
            expectedRoots += GameReviewPlanner.playerRootAtPosition(
                requestId = "${config.gameId}-undo-current-review",
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                position = position,
            )
        }
        val expectedKeys = expectedRoots.mapTo(linkedSetOf()) { root -> root.key }
        reviewPrefetchRootsByKey.keys
            .filter { key -> key !in expectedKeys }
            .forEach { key ->
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.CANCELLED,
                    key = key.auditKey(),
                    requestId = reviewPrefetchRootsByKey[key]?.response?.requestId,
                    reason = "history_removed_by_undo",
                )
            }
        reviewPrefetchAdjacentRootsByKey.entries
            .filter { (key, _) ->
                val playedMove = moves.getOrNull(key.rootKey.ply - 1)
                key.rootKey !in expectedKeys || playedMove != key.playedMove
            }
            .forEach { (key, seed) ->
                appendReviewPrefetchAuditLocked(
                    stage = ReviewPrefetchAuditStage.CANCELLED,
                    key = key.auditKey(),
                    requestId = seed.response.requestId,
                    reason = "history_removed_by_undo",
                )
            }
        reviewPrefetchRootsByKey.keys.retainAll(expectedKeys)
        reviewPrefetchAdjacentRootsByKey.entries.removeAll { (key, _) ->
            val playedMove = moves.getOrNull(key.rootKey.ply - 1)
            key.rootKey !in expectedKeys || playedMove != key.playedMove
        }
        reviewPrefetchRootKeysByPly.clear()
        reviewPrefetchRootsByKey.keys.forEach { key -> reviewPrefetchRootKeysByPly[key.ply] = key }
        adjacentPrefetchRevision = null
        rebuildReviewAdjacentCandidates()
    }

    private fun expireClockLocked(now: TimeReading): Boolean {
        if (!clock.timed || clock.runningSide == null) return false
        clock = clock.projected(now)
        val loser = clock.runningSide!!
        if (clock.remaining(loser)!! > 0) return false
        session = session.copy(outcome = GameOutcome(
            winner = loser.opposite(),
            reason = EndReason.TIMEOUT,
        ))
        clock = clock.stop(now)
        engineError = null
        revision++
        persistLocked()
        return true
    }

    private fun restoredClockAfterUndo(retainedPlyCount: Int, now: TimeReading): CoordinatorClock {
        val timed = config.timeControl as? TimeControl.Clock ?: return CoordinatorClock.initial(
            TimeControl.Untimed, session.sideToMove, now,
        )
        val snapshot = moveClocks.lastOrNull()
        val white = snapshot?.whiteRemainingMillis ?: timed.initialMillis
        val black = snapshot?.blackRemainingMillis ?: timed.initialMillis
        return CoordinatorClock(white, black, null, null, null).start(session.sideToMove, now)
    }

    private fun appendReviewPrefetchAuditLocked(
        stage: ReviewPrefetchAuditStage,
        key: ReviewPrefetchAuditKey,
        requestId: String?,
        reason: String,
    ) {
        reviewPrefetchAuditSequence++
        reviewPrefetchAuditEvents += ReviewPrefetchAuditEvent(
            sequence = reviewPrefetchAuditSequence,
            coordinatorRevision = revision,
            stage = stage,
            key = key,
            requestId = requestId,
            reason = reason,
        )
    }

    private fun persistReviewPrefetchEvidenceLocked(
        key: ReviewPrefetchAuditKey,
        requestId: String,
    ) {
        try {
            persistLocked()
        } catch (error: Throwable) {
            appendReviewPrefetchAuditLocked(
                stage = ReviewPrefetchAuditStage.REJECTED,
                key = key,
                requestId = requestId,
                reason = error.reviewAuditReason("checkpoint_persist_failed"),
            )
            throw error
        }
        appendReviewPrefetchAuditLocked(
            stage = ReviewPrefetchAuditStage.CHECKPOINTED,
            key = key,
            requestId = requestId,
            reason = "checkpoint_sink_persisted",
        )
    }

    private fun reviewPrefetchResultRejectionReasonLocked(expectedRevision: Long): String? = when {
        closed -> "coordinator_closed_before_callback"
        !reviewPrefetchEnabled -> "foreground_prefetch_disabled_before_callback"
        revision != expectedRevision -> "coordinator_revision_changed_before_callback"
        session.outcome != null -> "game_completed_before_callback"
        clock.paused -> "game_paused_before_callback"
        session.sideToMove != config.humanSide -> "player_turn_ended_before_callback"
        else -> null
    }

    private fun activeReviewPrefetchAuditKeyLocked(): ReviewPrefetchAuditKey? =
        activeReviewPrefetchRoot?.key?.auditKey()
            ?: activeReviewPrefetchAdjacentRoot?.key?.auditKey()

    private fun buildReviewPrefetchCoverageLocked(): ReviewPrefetchCoverageSnapshot {
        val moves = session.moves.map { recorded -> recorded.move }
        val expectedRootObjects = GameReviewPlanner.playerPlan(
            gameId = config.gameId,
            initialFen = config.initialFen,
            moves = moves,
            rules = config.rules,
            playerSide = config.humanSide,
        ).roots
        val expectedRoots = expectedRootObjects.map { root -> root.key.auditKey() }
        val acceptedRootKeySet = reviewPrefetchRootsByKey.keys
        val acceptedRoots = expectedRootObjects
            .filter { root -> root.key in acceptedRootKeySet }
            .map { root -> root.key.auditKey() }
        val missingRoots = expectedRootObjects
            .filter { root -> root.key !in acceptedRootKeySet }
            .map { root -> root.key.auditKey() }

        val requiredAdjacentKeys = expectedRootObjects.mapNotNull { root ->
            val seed = reviewPrefetchRootsByKey[root.key] ?: return@mapNotNull null
            val playedMove = moves.getOrNull(root.ply - 1) ?: return@mapNotNull null
            if (seed.response.variations.any { variation ->
                    variation.moves.firstOrNull() == playedMove
                }
            ) {
                return@mapNotNull null
            }
            GameReviewPlanner.adjacentRoot(
                requestId = "${config.gameId}-audit-adjacent-${root.ply}",
                rootKey = root.key,
                playedMove = playedMove,
            ).key
        }
        val acceptedAdjacentKeySet = reviewPrefetchAdjacentRootsByKey.keys
        val requiredAdjacent = requiredAdjacentKeys.map { key -> key.auditKey() }
        val acceptedAdjacent = requiredAdjacentKeys
            .filter { key -> key in acceptedAdjacentKeySet }
            .map { key -> key.auditKey() }
        val missingAdjacent = requiredAdjacentKeys
            .filter { key -> key !in acceptedAdjacentKeySet }
            .map { key -> key.auditKey() }

        val speculativeCurrent = if (session.outcome == null && position.sideToMove == config.humanSide) {
            GameReviewPlanner.playerRootAtPosition(
                requestId = "${config.gameId}-audit-current-$revision",
                gameId = config.gameId,
                initialFen = config.initialFen,
                moves = moves,
                rules = config.rules,
                position = position,
            ).key.auditKey()
        } else {
            null
        }
        val active = activeReviewPrefetchAuditKeyLocked()
        val pending = linkedSetOf<ReviewPrefetchAuditKey>().apply {
            addAll(missingRoots)
            addAll(missingAdjacent)
            speculativeCurrent
                ?.takeIf { current -> current.rootKey !in acceptedRootKeySet }
                ?.let(::add)
            active?.let(::add)
        }.sortedWith(compareBy<ReviewPrefetchAuditKey> { it.ply }.thenBy { it.kind.ordinal })
        val missingRootKeySet = missingRoots.mapTo(linkedSetOf()) { key -> key.rootKey }
        val missingAdjacentRootKeySet = missingAdjacent.mapTo(linkedSetOf()) { key -> key.rootKey }
        val fullyCovered = expectedRootObjects.count { root ->
            root.key !in missingRootKeySet && root.key !in missingAdjacentRootKeySet
        }
        return ReviewPrefetchCoverageSnapshot(
            gameId = config.gameId,
            coordinatorRevision = revision,
            terminal = session.outcome != null,
            expectedPlayedRoots = expectedRoots,
            acceptedPlayedRoots = acceptedRoots,
            missingPlayedRoots = missingRoots,
            requiredAdjacent = requiredAdjacent,
            acceptedAdjacent = acceptedAdjacent,
            missingAdjacent = missingAdjacent,
            speculativeCurrentRoot = speculativeCurrent,
            activeWork = active,
            pendingWork = pending,
            fullyCoveredPlayedMoves = fullyCovered,
            latestEventSequence = reviewPrefetchAuditSequence,
        )
    }

    private fun captureTerminalReviewPrefetchCoverageLocked() {
        if (session.outcome != null) {
            terminalReviewPrefetchCoverage = buildReviewPrefetchCoverageLocked()
        }
    }

    private fun phaseLocked(): CoordinatorPhase = when {
        session.outcome != null -> CoordinatorPhase.COMPLETED
        clock.paused -> CoordinatorPhase.PAUSED
        engineError != null -> CoordinatorPhase.BOT_ERROR
        activeRequestPurpose == EnginePurpose.HINT -> CoordinatorPhase.HINT_THINKING
        session.sideToMove == config.humanSide -> CoordinatorPhase.HUMAN_TURN
        else -> CoordinatorPhase.BOT_THINKING
    }

    private fun clearActiveEngineLocked(
        reviewAuditStage: ReviewPrefetchAuditStage? = null,
        reviewAuditReason: String? = null,
    ): EngineCancellation? {
        if (activeRequestPurpose == EnginePurpose.REVIEW && reviewAuditStage != null) {
            activeReviewPrefetchAuditKeyLocked()?.let { key ->
                appendReviewPrefetchAuditLocked(
                    stage = reviewAuditStage,
                    key = key,
                    requestId = activeRequestId,
                    reason = requireNotNull(reviewAuditReason),
                )
            }
        }
        val cancellation = activeCancellation
        activeRequestId = null
        activeRequestPurpose = null
        activeCancellation = null
        activeReviewPrefetchRoot = null
        activeReviewPrefetchAdjacentRoot = null
        activeReviewPrefetchRevision = null
        return cancellation
    }

    /** Cancels a published gameplay handle and drains its in-flight analyze call. */
    private fun cancelAndDrainEngineLaunch(cancellation: EngineCancellation?) {
        cancelIgnoringFailure(cancellation)
        engineInvocationLock.withLock { /* An in-flight analyze launch has drained. */ }
    }

    /** Drains the gate used by review, without making ordinary moves or undo wait on it. */
    private fun cancelAndDrainReviewLaunch(cancellation: EngineCancellation?) {
        cancelIgnoringFailure(cancellation)
        val invocationLock = if (reviewSharesGameplayEngine) engineInvocationLock else reviewInvocationLock
        invocationLock.withLock { /* An in-flight review analyze launch has drained. */ }
    }

    /** Lifecycle and terminal transitions leave neither engine with an unpublished launch. */
    private fun cancelAndDrainAllEngineLaunches(cancellation: EngineCancellation?) {
        cancelIgnoringFailure(cancellation)
        engineInvocationLock.withLock { /* An in-flight gameplay analyze launch has drained. */ }
        if (!reviewSharesGameplayEngine) {
            reviewInvocationLock.withLock { /* An in-flight review analyze launch has drained. */ }
        }
    }

    private fun cancelIgnoringFailure(cancellation: EngineCancellation?) {
        runCatching { cancellation?.cancel() }
    }

    private fun requireStartedLocked() {
        check(started) { "Coordinator has not started" }
        check(!closed) { "Coordinator is closed" }
    }

    private fun persistLocked() = checkpointSink.persist(checkpointLocked())

    private fun checkpointLocked(): CoordinatorCheckpoint = CoordinatorCheckpoint(
        revision = revision,
        config = config,
        moves = session.moves.map { it.move },
        currentFen = position.fen(),
        outcome = session.outcome,
        clock = clock,
        moveClocks = moveClocks,
        assistance = assistance,
        reviewPrefetchRoots = reviewPrefetchRootsByKey.values.toList(),
        reviewPrefetchAdjacentRoots = reviewPrefetchAdjacentRootsByKey.values.toList(),
    )

    companion object {
        fun newGame(
            config: GameConfig,
            engine: ChessEngine,
            checkpointSink: CheckpointSink,
            timeSource: CoordinatorTimeSource,
            idSource: CoordinatorIdSource,
            botMovePresentationDelayMillis: Long = 0,
            initialAssistance: AssistanceCounts = AssistanceCounts(),
            reviewEngine: ChessEngine = engine,
            drainReviewPrefetchBacklog: Boolean = false,
        ): GameCoordinator {
            require(config.mode != GameMode.RATED || !initialAssistance.wasUsed) {
                "Rated games cannot start with assistance"
            }
            val position = ChessPosition.fromFen(config.initialFen)
            val session = GameSession.newGame(
                config.gameId, config.rules, RepetitionKey.of(position), position.sideToMove,
            )
            return GameCoordinator(
                config, engine, reviewEngine, checkpointSink, timeSource, idSource,
                botMovePresentationDelayMillis, drainReviewPrefetchBacklog,
                session, position, CoordinatorClock.initial(config.timeControl, position.sideToMove, timeSource.now()),
                emptyList(), initialAssistance, 0,
            )
        }

        fun restore(
            checkpoint: CoordinatorCheckpoint,
            engine: ChessEngine,
            checkpointSink: CheckpointSink,
            timeSource: CoordinatorTimeSource,
            idSource: CoordinatorIdSource,
            botMovePresentationDelayMillis: Long = 0,
            reviewEngine: ChessEngine = engine,
            drainReviewPrefetchBacklog: Boolean = false,
        ): GameCoordinator {
            require(
                (checkpoint.config.timeControl == TimeControl.Untimed && !checkpoint.clock.timed) ||
                    (checkpoint.config.timeControl is TimeControl.Clock && checkpoint.clock.timed),
            ) { "Checkpoint clock does not match its time control" }
            require(checkpoint.config.mode != GameMode.RATED || !checkpoint.assistance.wasUsed) {
                "Rated checkpoint contains assistance"
            }
            val (rebuiltSession, rebuiltPosition) = rebuild(checkpoint.config, checkpoint.moves)
            require(rebuiltPosition.fen() == checkpoint.currentFen) { "Checkpoint FEN does not match replay" }
            val session = when {
                checkpoint.outcome == null -> {
                    require(rebuiltSession.outcome == null) { "Checkpoint omitted a rules-derived result" }
                    rebuiltSession
                }
                rebuiltSession.outcome == null -> {
                    require(checkpoint.outcome.reason in setOf(EndReason.TIMEOUT, EndReason.RESIGNATION)) {
                        "Checkpoint contains a non-replayable result"
                    }
                    if (checkpoint.outcome.reason == EndReason.RESIGNATION) {
                        require(checkpoint.outcome.winner == checkpoint.config.humanSide.opposite()) {
                            "Resignation winner does not match the human player"
                        }
                    }
                    if (checkpoint.outcome.reason == EndReason.TIMEOUT) {
                        require(checkpoint.clock.remaining(checkpoint.outcome.loser) == 0L) {
                            "Timeout checkpoint does not contain an expired losing clock"
                        }
                    }
                    rebuiltSession.copy(outcome = checkpoint.outcome)
                }
                else -> {
                    require(rebuiltSession.outcome == checkpoint.outcome) { "Checkpoint result does not match replay" }
                    rebuiltSession
                }
            }
            require(checkpoint.moveClocks.size == checkpoint.moves.size) { "Clock history length mismatch" }
            require(checkpoint.moveClocks.map { it.ply } == (1..checkpoint.moves.size).toList()) {
                "Clock history ply sequence is invalid"
            }
            val restoredClock = if (session.outcome == null) checkpoint.clock else checkpoint.clock.stop(timeSource.now())
            val (reviewRoots, reviewAdjacentRoots) = compatibleReviewPrefetch(
                checkpoint,
                rebuiltPosition,
            )
            return GameCoordinator(
                checkpoint.config, engine, reviewEngine, checkpointSink, timeSource, idSource,
                botMovePresentationDelayMillis, drainReviewPrefetchBacklog,
                session, rebuiltPosition, restoredClock, checkpoint.moveClocks,
                checkpoint.assistance, checkpoint.revision,
                reviewRoots, reviewAdjacentRoots,
            )
        }

        /**
         * Durable review work is advisory: an exact key match restores it, while stale evidence is
         * ignored without making the playable checkpoint unavailable.
         */
        private fun compatibleReviewPrefetch(
            checkpoint: CoordinatorCheckpoint,
            rebuiltPosition: ChessPosition,
        ): Pair<List<SeededGameReviewRoot>, List<SeededGameReviewAdjacentRoot>> {
            val expectedRootsByPly = GameReviewPlanner.playerPlan(
                gameId = checkpoint.config.gameId,
                initialFen = checkpoint.config.initialFen,
                moves = checkpoint.moves,
                rules = checkpoint.config.rules,
                playerSide = checkpoint.config.humanSide,
            ).roots.associateByTo(linkedMapOf()) { root -> root.ply }
            if (checkpoint.outcome == null && rebuiltPosition.sideToMove == checkpoint.config.humanSide) {
                val current = GameReviewPlanner.playerRootAtPosition(
                    requestId = "${checkpoint.config.gameId}-restored-current-review",
                    gameId = checkpoint.config.gameId,
                    initialFen = checkpoint.config.initialFen,
                    moves = checkpoint.moves,
                    rules = checkpoint.config.rules,
                    position = rebuiltPosition,
                )
                expectedRootsByPly[current.ply] = current
            }

            val roots = checkpoint.reviewPrefetchRoots.filter { seed ->
                expectedRootsByPly[seed.key.ply]?.key == seed.key
            }
            val expectedRootsByKey = expectedRootsByPly.values.associateBy { root -> root.key }
            val adjacentRoots = checkpoint.reviewPrefetchAdjacentRoots.filter { seed ->
                val root = expectedRootsByKey[seed.key.rootKey] ?: return@filter false
                val playedMove = checkpoint.moves.getOrNull(root.ply - 1) ?: return@filter false
                if (playedMove != seed.key.playedMove) return@filter false
                GameReviewPlanner.adjacentRoot(
                    requestId = seed.response.requestId,
                    root = root,
                    playedMove = playedMove,
                ).key == seed.key
            }
            val identities = (roots.map { it.response.engine } +
                adjacentRoots.map { it.response.engine }).distinct()
            return if (identities.size <= 1) roots to adjacentRoots else emptyList<SeededGameReviewRoot>() to emptyList()
        }

        private fun rebuild(config: GameConfig, moves: List<UciMove>): Pair<GameSession, ChessPosition> {
            var position = ChessPosition.fromFen(config.initialFen)
            var session = GameSession.newGame(
                config.gameId, config.rules, RepetitionKey.of(position), position.sideToMove,
            )
            for (move in moves) {
                check(session.outcome == null) { "Moves continue after a rules-derived result" }
                val preparedTransition = ChessAdapter.prepareTransition(position, move)
                session = session.apply(preparedTransition.transition)
                position = preparedTransition.positionAfter
            }
            return session to position
        }
    }
}

private fun GameReviewRootKey.auditKey(): ReviewPrefetchAuditKey =
    ReviewPrefetchAuditKey(rootKey = this)

private fun GameReviewAdjacentKey.auditKey(): ReviewPrefetchAuditKey =
    ReviewPrefetchAuditKey(rootKey = rootKey, playedMove = playedMove)

private fun Throwable.reviewAuditReason(prefix: String): String {
    val type = this::class.simpleName ?: "Throwable"
    val detail = message
        ?.replace(';', ',')
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(160)
        ?.takeIf { value -> value.isNotBlank() }
    return if (detail == null) "$prefix:$type" else "$prefix:$type:$detail"
}
