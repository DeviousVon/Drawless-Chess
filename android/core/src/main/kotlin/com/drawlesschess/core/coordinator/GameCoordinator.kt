package com.drawlesschess.core.coordinator

import com.drawlesschess.core.*
import com.drawlesschess.core.chess.ChessAdapter
import com.drawlesschess.core.chess.ChessPosition
import com.drawlesschess.core.chess.ChessRules
import com.drawlesschess.core.chess.RepetitionKey
import com.drawlesschess.core.engine.AnalysisRequests

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
    }

    fun close() {
        val cancellation = synchronized(lock) {
            if (closed) return
            closed = true
            clearActiveEngineLocked()
        }
        cancellation?.cancel()
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

    fun playHuman(move: UciMove) {
        val now = timeSource.now()
        var shouldLaunchBot = false
        synchronized(lock) {
            requireStartedLocked()
            require(session.outcome == null) { "Game is complete" }
            require(!clock.paused) { "Game is paused" }
            require(session.sideToMove == config.humanSide) { "It is not the human player's turn" }
            require(activeRequestPurpose != EnginePurpose.HINT) { "Hint analysis is in progress" }
            if (expireClockLocked(now)) return
            val transition = ChessAdapter.transition(position, move)
            val after = ChessRules.apply(position, move)
            commitMoveLocked(transition, after, now)
            shouldLaunchBot = session.outcome == null && session.sideToMove != config.humanSide
        }
        if (shouldLaunchBot) launchBotIfNeeded()
    }

    fun tick() {
        val cancellation = synchronized(lock) {
            if (!started || closed || session.outcome != null || clock.paused) return
            if (expireClockLocked(timeSource.now())) clearActiveEngineLocked() else null
        }
        cancellation?.cancel()
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
        cancellation?.cancel()
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
    }

    fun markHintUsed() {
        synchronized(lock) {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot use hints" }
            require(session.outcome == null) { "Game is complete" }
            assistance = assistance.copy(hints = assistance.hints + 1)
            revision++
            persistLocked()
        }
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
        val request = synchronized(lock) {
            requireStartedLocked()
            require(config.mode == GameMode.CASUAL) { "Rated games cannot use hints" }
            require(session.outcome == null) { "Game is complete" }
            require(!clock.paused) { "Game is paused" }
            require(session.sideToMove == config.humanSide) { "Hints are available only on your turn" }
            require(session.positionId == expectedPositionId) { "The position changed before hint analysis started" }
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
            if (shouldDeliver) runCatching { onResult(Result.failure(error)) }
            return
        }

        var cancelImmediately = false
        synchronized(lock) {
            if (activeRequestId == request.requestId && activeRequestPurpose == EnginePurpose.HINT) {
                activeCancellation = cancellation
            } else {
                cancelImmediately = true
            }
        }
        if (cancelImmediately) cancellation.cancel()
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
        cancellation?.cancel()
        launchBotIfNeeded()
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
        cancellation?.cancel()
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
        val request = synchronized(lock) {
            if (!started || closed || session.outcome != null || clock.paused ||
                session.sideToMove == config.humanSide || activeRequestId != null || engineError != null) {
                return
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
            return
        }
        var cancelImmediately = false
        synchronized(lock) {
            if (activeRequestId == request.requestId) activeCancellation = cancellation
            else cancelImmediately = true
        }
        if (cancelImmediately) cancellation.cancel()
    }

    private fun handleEngineResult(request: EngineRequest, result: Result<EngineResponse>) {
        synchronized(lock) {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.BOT_MOVE ||
                session.outcome != null || clock.paused) return
            activeRequestId = null
            activeRequestPurpose = null
            activeCancellation = null
            val response = result.getOrElse { error ->
                engineError = error.message ?: error::class.simpleName ?: "Engine failure"
                revision++
                persistLocked()
                return
            }
            if (!response.matches(request) || session.positionId != request.positionId) {
                engineError = "Engine response identity does not match the active position"
                revision++
                persistLocked()
                return
            }
            val now = timeSource.now()
            if (expireClockLocked(now)) return
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
    }

    private fun handleHintResult(
        request: EngineRequest,
        result: Result<EngineResponse>,
        onResult: (Result<EngineResponse>) -> Unit,
    ) {
        var delivery: Result<EngineResponse>? = null
        synchronized(lock) {
            if (closed || activeRequestId != request.requestId || activeRequestPurpose != EnginePurpose.HINT ||
                session.outcome != null || clock.paused || session.positionId != request.positionId) return

            clearActiveEngineLocked()
            if (expireClockLocked(timeSource.now())) return

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
        return cancellation
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
