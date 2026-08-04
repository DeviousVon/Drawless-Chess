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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GameCoordinator private constructor(
    private val config: GameConfig,
    private val engine: ChessEngine,
    private val reviewEngine: ChessEngine,
    private val checkpointSink: CheckpointSink,
    private val timeSource: CoordinatorTimeSource,
    private val idSource: CoordinatorIdSource,
    private val botMovePresentationDelayMillis: Long,
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

    private val lock = Any()
    private val engineInvocationLock = ReentrantLock()
    private val reviewInvocationLock = ReentrantLock()
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
    private var engineError: String? = null

    init {
        rebuildReviewAdjacentCandidates()
    }

    fun start() {
        synchronized(lock) {
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
        val cancellation = synchronized(lock) {
            if (closed) return
            closed = true
            clearActiveEngineLocked()
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun snapshot(): CoordinatorSnapshot = synchronized(lock) {
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
        val cancellation = synchronized(lock) {
            if (closed) return
            reviewPrefetchEnabled = enabled
            if (!enabled && activeRequestPurpose == EnginePurpose.REVIEW) {
                // Disabling is an external interruption, not a failed search. Permit this one
                // queued adjacent fallback to retry when the same position is foregrounded.
                if (activeReviewPrefetchAdjacentRoot != null) adjacentPrefetchRevision = null
                clearActiveEngineLocked()
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

    /** Immutable exact roots completed during foreground play, for the post-game runner. */
    fun completedReviewPrefetchRoots(): List<SeededGameReviewRoot> = synchronized(lock) {
        reviewPrefetchRootsByKey.values.toList()
    }

    /** Immutable exact fallback searches completed during otherwise-idle foreground play. */
    fun completedReviewPrefetchAdjacentRoots(): List<SeededGameReviewAdjacentRoot> = synchronized(lock) {
        reviewPrefetchAdjacentRootsByKey.values.toList()
    }

    fun playHuman(move: UciMove) {
        val now = timeSource.now()
        var shouldLaunchBot = false
        var clockExpired = false
        var prefetchCancellation: EngineCancellation? = null
        try {
            synchronized(lock) {
                requireStartedLocked()
                require(session.outcome == null) { "Game is complete" }
                require(!clock.paused) { "Game is paused" }
                require(session.sideToMove == config.humanSide) { "It is not the human player's turn" }
                require(activeRequestPurpose != EnginePurpose.HINT) { "Hint analysis is in progress" }
                if (expireClockLocked(now)) {
                    if (activeRequestPurpose == EnginePurpose.REVIEW) {
                        prefetchCancellation = clearActiveEngineLocked()
                    }
                    clockExpired = true
                } else {
                    // Validate before releasing the speculative slot. An illegal UI move must not
                    // detach a live engine request without also obtaining its cancellation handle.
                    val transition = ChessAdapter.transition(position, move)
                    val after = ChessRules.apply(position, move)
                    if (activeRequestPurpose == EnginePurpose.REVIEW) {
                        prefetchCancellation = clearActiveEngineLocked()
                    }
                    commitMoveLocked(transition, after, now)
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
        val cancellation = synchronized(lock) {
            if (!started || closed || session.outcome != null || clock.paused) return
            if (expireClockLocked(timeSource.now())) {
                clockExpired = true
                clearActiveEngineLocked()
            } else {
                null
            }
        }
        if (clockExpired) cancelAndDrainAllEngineLaunches(cancellation) else cancelIgnoringFailure(cancellation)
    }

    fun pause() {
        val cancellation: EngineCancellation?
        synchronized(lock) {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot be paused" }
            require(session.outcome == null) { "Game is complete" }
            require(!clock.paused) { "Game is already paused" }
            clock = clock.pause(timeSource.now())
            assistance = assistance.copy(pauses = assistance.pauses + 1)
            engineError = null
            cancellation = clearActiveEngineLocked()
            revision++
            persistLocked()
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun resume() {
        synchronized(lock) {
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
        synchronized(lock) {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot use hints" }
            require(session.outcome == null) { "Game is complete" }
            cancellation = if (activeRequestPurpose == EnginePurpose.REVIEW) {
                clearActiveEngineLocked()
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
                    synchronized(lock) {
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
                            prefetchCancellation = clearActiveEngineLocked()
                        }
                        require(activeRequestId == null) { "Hint analysis is already in progress" }
                        require(!expireClockLocked(timeSource.now())) { "Game is complete" }

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
                    val shouldDeliver = synchronized(lock) {
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
                synchronized(lock) {
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
        synchronized(lock) {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot undo" }
            require(session.outcome == null) { "Game is complete" }
            val lastHumanIndex = session.moves.indexOfLast { it.mover == config.humanSide }
            require(lastHumanIndex >= 0) { "No human move is available to undo" }
            cancellation = clearActiveEngineLocked()
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
        synchronized(lock) {
            requireStartedLocked()
            require(session.outcome == null) { "Game is complete" }
            val outcome = GameOutcome(
                winner = config.humanSide.opposite(),
                reason = EndReason.RESIGNATION,
            )
            session = session.copy(outcome = outcome)
            clock = clock.stop(timeSource.now())
            engineError = null
            cancellation = clearActiveEngineLocked()
            revision++
            persistLocked()
        }
        cancelAndDrainAllEngineLaunches(cancellation)
    }

    fun retryBot() {
        synchronized(lock) {
            requireStartedLocked()
            require(session.outcome == null && session.sideToMove != config.humanSide)
            require(engineError != null) { "The bot has not failed" }
            engineError = null
            revision++
            persistLocked()
        }
        launchBotIfNeeded()
    }

    fun checkpoint(): CoordinatorCheckpoint = synchronized(lock) { checkpointLocked() }

    private fun launchBotIfNeeded() {
        engineInvocationLock.withLock engineLaunch@{
            val request = synchronized(lock) {
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
                synchronized(lock) {
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
            synchronized(lock) {
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
        synchronized(lock) {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.BOT_MOVE ||
                session.outcome != null || clock.paused) return@synchronized
            activeRequestId = null
            activeRequestPurpose = null
            activeCancellation = null
            val response = result.getOrElse { error ->
                engineError = error.message ?: error::class.simpleName ?: "Engine failure"
                revision++
                persistLocked()
                return@synchronized
            }
            if (!response.matches(request) || session.positionId != request.positionId) {
                engineError = "Engine response identity does not match the active position"
                revision++
                persistLocked()
                return@synchronized
            }
            val now = timeSource.now()
            if (expireClockLocked(now)) return@synchronized
            try {
                val transition = ChessAdapter.transition(position, response.bestMove)
                val after = ChessRules.apply(position, response.bestMove)
                commitMoveLocked(
                    transition = transition,
                    after = after,
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
        synchronized(lock) {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.HINT ||
                session.outcome != null || clock.paused || session.positionId != request.positionId) return@synchronized

            handled = true
            clearActiveEngineLocked()
            if (expireClockLocked(timeSource.now())) return@synchronized

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
        if (!invocationLock.tryLock()) return
        val retryAfterStaleLaunch: Boolean
        try {
            retryAfterStaleLaunch = launchPreparedReviewPrefetch()
        } finally {
            invocationLock.unlock()
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
        val callbackAccepted = AtomicBoolean(false)
        val continuationRequested = AtomicBoolean(false)
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
        } catch (_: Throwable) {
            synchronized(lock) {
                if (activeRequestId == request.requestId &&
                    activeRequestPurpose == EnginePurpose.REVIEW
                ) {
                    clearActiveEngineLocked()
                }
            }
            return false
        }

        val cancelImmediately = synchronized(lock) {
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
        return continuationRequested.get() ||
            (cancelImmediately && !callbackAccepted.get())
    }

    /**
     * Copies the current lightweight inputs under the coordinator lock, then builds FEN/request
     * structures outside it. Result callbacks and UI actions can therefore never queue behind
     * historical reconstruction.
     */
    private fun prepareReviewPrefetch(): PreparedReviewPrefetch? {
        val rootPreparation = synchronized(lock) {
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

        val adjacentPreparation = synchronized(lock) {
            if (!reviewPrefetchEligibleLocked() || revision != rootPreparation.expectedRevision) return null
            reviewPrefetchRootKeysByPly[currentRoot.ply] = currentRoot.key
            if (currentRoot.key !in reviewPrefetchRootsByKey) {
                reserveReviewPrefetchLocked(currentRoot, null, rootPreparation.expectedRevision)
                return PreparedReviewPrefetch(currentRoot, null, rootPreparation.expectedRevision)
            }
            if (adjacentPrefetchRevision == revision) return null
            val candidate = reviewAdjacentCandidates.firstOrNull() ?: return null
            Triple(candidate, idSource.nextId(), revision)
        }

        val (candidate, requestId, expectedRevision) = adjacentPreparation
        val adjacent = GameReviewPlanner.adjacentRoot(
            requestId = requestId,
            rootKey = candidate.rootKey,
            playedMove = candidate.playedMove,
        )
        return synchronized(lock) {
            if (!reviewPrefetchEligibleLocked() || revision != expectedRevision ||
                candidate !in reviewAdjacentCandidates
            ) {
                return null
            }
            if (adjacent.key in reviewPrefetchAdjacentRootsByKey) {
                reviewAdjacentCandidates.remove(candidate)
                return null
            }
            adjacentPrefetchRevision = revision
            reserveReviewPrefetchLocked(null, adjacent, expectedRevision)
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
    ) {
        val request = root?.request ?: requireNotNull(adjacent).request
        activeRequestId = request.requestId
        activeRequestPurpose = EnginePurpose.REVIEW
        activeReviewPrefetchRoot = root
        activeReviewPrefetchAdjacentRoot = adjacent
        activeReviewPrefetchRevision = expectedRevision
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
        val seededRoot = root?.let { value -> result.mapCatching(value::seed).getOrNull() }
        val seededAdjacent = adjacent?.let { value -> result.mapCatching(value::seed).getOrNull() }
        var continuePrefetch = false
        val request = root?.request ?: requireNotNull(adjacent).request
        synchronized(lock) {
            if (activeRequestPurpose != EnginePurpose.REVIEW ||
                activeRequestId != request.requestId || activeReviewPrefetchRoot !== root ||
                activeReviewPrefetchAdjacentRoot !== adjacent ||
                activeReviewPrefetchRevision != expectedRevision
            ) {
                return
            }
            callbackAccepted?.set(true)
            clearActiveEngineLocked()
            if (closed || !reviewPrefetchEnabled || revision != expectedRevision ||
                session.outcome != null || clock.paused || session.sideToMove != config.humanSide
            ) {
                return
            }
            if (root != null) {
                seededRoot?.let { seeded ->
                    reviewPrefetchRootsByKey[seeded.key] = seeded
                    revision++
                    persistLocked()
                    continuePrefetch = true
                }
            } else {
                seededAdjacent?.let { seeded ->
                    reviewPrefetchAdjacentRootsByKey[seeded.key] = seeded
                    reviewAdjacentCandidates.remove(
                        ReviewAdjacentCandidate(seeded.key.rootKey, seeded.key.playedMove),
                    )
                    revision++
                    // Persisting review evidence advances the durable checkpoint revision, but it
                    // does not create another idle player turn. Carry the one-fallback allowance
                    // forward so the continuation below cannot start a second historical search.
                    adjacentPrefetchRevision = revision
                    persistLocked()
                    continuePrefetch = true
                }
            }
        }
        // A completed current root uses only the first 350 ms of a long think. Continue with any
        // exact played-position fallback that remains from an earlier player move.
        if (continuePrefetch) {
            continuationRequested?.set(true)
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

    /** Recreates unfinished played-position fallback work from durable exact root evidence. */
    private fun rebuildReviewAdjacentCandidates() {
        reviewAdjacentCandidates.clear()
        reviewPrefetchRootsByKey.values
            .sortedBy { seed -> seed.key.ply }
            .forEach { seed ->
                val playedMove = session.moves.getOrNull(seed.key.ply - 1)?.move ?: return@forEach
                if (seed.response.variations.any { variation ->
                        variation.moves.firstOrNull() == playedMove
                    }
                ) {
                    return@forEach
                }
                val adjacentKey = GameReviewPlanner.adjacentRoot(
                    requestId = "restored-adjacent-${seed.key.ply}",
                    rootKey = seed.key,
                    playedMove = playedMove,
                ).key
                if (adjacentKey !in reviewPrefetchAdjacentRootsByKey) {
                    reviewAdjacentCandidates += ReviewAdjacentCandidate(seed.key, playedMove)
                }
            }
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

    private fun phaseLocked(): CoordinatorPhase = when {
        session.outcome != null -> CoordinatorPhase.COMPLETED
        clock.paused -> CoordinatorPhase.PAUSED
        engineError != null -> CoordinatorPhase.BOT_ERROR
        activeRequestPurpose == EnginePurpose.HINT -> CoordinatorPhase.HINT_THINKING
        session.sideToMove == config.humanSide -> CoordinatorPhase.HUMAN_TURN
        else -> CoordinatorPhase.BOT_THINKING
    }

    private fun clearActiveEngineLocked(): EngineCancellation? {
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
        ): GameCoordinator {
            require(config.mode != GameMode.RATED || !initialAssistance.wasUsed) {
                "Rated games cannot start with assistance"
            }
            val position = ChessPosition.fromFen(config.initialFen)
            val session = GameSession.newGame(
                config.gameId, config.rules, RepetitionKey.of(position), position.sideToMove,
            )
            return GameCoordinator(
                config, engine, reviewEngine, checkpointSink, timeSource, idSource, botMovePresentationDelayMillis,
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
                checkpoint.config, engine, reviewEngine, checkpointSink, timeSource, idSource, botMovePresentationDelayMillis,
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
                session = session.apply(ChessAdapter.transition(position, move))
                position = ChessRules.apply(position, move)
            }
            return session to position
        }
    }
}
