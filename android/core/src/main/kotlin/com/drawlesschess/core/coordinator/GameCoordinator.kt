package com.drawlesschess.core.coordinator

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.engine.AnalysisRequests
import com.drawlesschess.core.engine.GameReviewPlanner
import com.drawlesschess.core.engine.GameReviewRoot
import com.drawlesschess.core.engine.GameReviewRootKey
import com.drawlesschess.core.engine.SeededGameReviewRoot
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GameCoordinator private constructor(
    private val config: GameConfig,
    private val engine: ChessEngine,
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
) {
    init {
        require(botMovePresentationDelayMillis >= 0) { "Bot move presentation delay must not be negative" }
    }

    private val lock = Any()
    private val engineInvocationLock = ReentrantLock()
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
    private var activeReviewPrefetchRevision: Long? = null
    private var reviewPrefetchEnabled = false
    private val reviewPrefetchRootsByKey = linkedMapOf<GameReviewRootKey, SeededGameReviewRoot>()
    private var engineError: String? = null

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
        cancelAndDrainEngineLaunch(cancellation)
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
     * Uses the coordinator's sole engine slot to warm the exact full-strength review root while
     * the visible game is waiting for the player. Gameplay always owns priority: disabling this,
     * starting a hint, moving, pausing, undoing, resigning, timing out, or closing cancels the
     * speculative request. Completed roots are returned only after exact request/revision checks.
     */
    fun setReviewPrefetchEnabled(enabled: Boolean) {
        val cancellation = synchronized(lock) {
            if (closed) return
            reviewPrefetchEnabled = enabled
            if (!enabled && activeRequestPurpose == EnginePurpose.REVIEW) {
                clearActiveEngineLocked()
            } else {
                null
            }
        }
        if (enabled) {
            cancelIgnoringFailure(cancellation)
        } else {
            cancelAndDrainEngineLaunch(cancellation)
        }
        // If a concurrent enable lost tryLock while this call drained the prior launch, this
        // final recheck observes the latest flag and restores the eligible speculative request.
        launchReviewPrefetchIfNeeded()
    }

    /** Immutable exact roots completed during foreground play, for the post-game runner. */
    fun completedReviewPrefetchRoots(): List<SeededGameReviewRoot> = synchronized(lock) {
        reviewPrefetchRootsByKey.values.toList()
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
            cancelAndDrainEngineLaunch(prefetchCancellation)
            throw error
        }
        cancelIgnoringFailure(prefetchCancellation)
        if (shouldLaunchBot) {
            launchBotIfNeeded()
        } else {
            // A terminal move or timeout hands the engine to post-game review immediately.
            // Do not return while a speculative analyze call is still publishing its handle.
            cancelAndDrainEngineLaunch(null)
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
        if (clockExpired) cancelAndDrainEngineLaunch(cancellation) else cancelIgnoringFailure(cancellation)
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
        cancelAndDrainEngineLaunch(cancellation)
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
        cancelAndDrainEngineLaunch(cancellation)
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
        cancelAndDrainEngineLaunch(cancellation)
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
        // This can run from inside a Fairy result callback. Never wait behind foreground work:
        // doing so could invert the coordinator gate and the engine's own callback monitor.
        if (!engineInvocationLock.tryLock()) return
        try {
            val rootAndRevision = synchronized(lock) {
                if (!started || closed || !reviewPrefetchEnabled || session.outcome != null ||
                    clock.paused || session.sideToMove != config.humanSide || activeRequestId != null ||
                    engineError != null
                ) {
                    return
                }
                val root = GameReviewPlanner.playerRoot(
                    requestId = idSource.nextId(),
                    gameId = config.gameId,
                    initialFen = config.initialFen,
                    moves = session.moves.map { it.move },
                    rules = config.rules,
                )
                if (root.key in reviewPrefetchRootsByKey) return
                activeRequestId = root.request.requestId
                activeRequestPurpose = EnginePurpose.REVIEW
                activeReviewPrefetchRoot = root
                activeReviewPrefetchRevision = revision
                root to revision
            }
            val (root, expectedRevision) = rootAndRevision
            val cancellation = try {
                engine.analyze(root.request) { result ->
                    handleReviewPrefetchResult(root, expectedRevision, result)
                }
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (activeRequestId == root.request.requestId &&
                        activeRequestPurpose == EnginePurpose.REVIEW
                    ) {
                        clearActiveEngineLocked()
                    }
                }
                return
            }

            var cancelImmediately = false
            synchronized(lock) {
                if (activeRequestId == root.request.requestId &&
                    activeRequestPurpose == EnginePurpose.REVIEW &&
                    activeReviewPrefetchRoot === root &&
                    activeReviewPrefetchRevision == expectedRevision
                ) {
                    activeCancellation = cancellation
                } else {
                    cancelImmediately = true
                }
            }
            if (cancelImmediately) cancelIgnoringFailure(cancellation)
        } finally {
            engineInvocationLock.unlock()
        }
    }

    private fun handleReviewPrefetchResult(
        root: GameReviewRoot,
        expectedRevision: Long,
        result: Result<EngineResponse>,
    ) {
        synchronized(lock) {
            if (activeRequestPurpose != EnginePurpose.REVIEW ||
                activeRequestId != root.request.requestId || activeReviewPrefetchRoot !== root ||
                activeReviewPrefetchRevision != expectedRevision
            ) {
                return
            }
            clearActiveEngineLocked()
            if (closed || !reviewPrefetchEnabled || revision != expectedRevision ||
                session.outcome != null || clock.paused || session.sideToMove != config.humanSide
            ) {
                return
            }
            result.mapCatching(root::seed).getOrNull()?.let { seeded ->
                reviewPrefetchRootsByKey[seeded.key] = seeded
            }
        }
    }

    private fun commitMoveLocked(
        transition: MoveTransition,
        after: ChessPosition,
        now: TimeReading,
        nextSideStartDelayMillis: Long = 0,
    ) {
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
        activeReviewPrefetchRevision = null
        return cancellation
    }

    /**
     * Cancels a published handle and also waits out the narrow interval between reserving the
     * coordinator slot and publishing that handle. This is used whenever no replacement request
     * will acquire [engineInvocationLock] itself, so lifecycle and terminal actions return with
     * no speculative engine launch left behind.
     */
    private fun cancelAndDrainEngineLaunch(cancellation: EngineCancellation?) {
        cancelIgnoringFailure(cancellation)
        engineInvocationLock.withLock { /* An in-flight analyze launch has drained. */ }
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
        ): GameCoordinator {
            require(config.mode != GameMode.RATED || !initialAssistance.wasUsed) {
                "Rated games cannot start with assistance"
            }
            val position = ChessPosition.fromFen(config.initialFen)
            val session = GameSession.newGame(
                config.gameId, config.rules, RepetitionKey.of(position), position.sideToMove,
            )
            return GameCoordinator(
                config, engine, checkpointSink, timeSource, idSource, botMovePresentationDelayMillis,
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
            return GameCoordinator(
                checkpoint.config, engine, checkpointSink, timeSource, idSource, botMovePresentationDelayMillis,
                session, rebuiltPosition, restoredClock, checkpoint.moveClocks,
                checkpoint.assistance, checkpoint.revision,
            )
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
