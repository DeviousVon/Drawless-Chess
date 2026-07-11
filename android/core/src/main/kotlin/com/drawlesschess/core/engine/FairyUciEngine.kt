package com.drawlesschess.core.engine

import com.drawlesschess.core.*

fun interface UciTransport {
    fun send(command: String)
}

fun interface UciTimeoutScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation
}

data class FairyEngineBuild(
    val buildId: String,
    val drawlessPatchVersion: Int,
) {
    init {
        require(buildId.isNotBlank())
        require(drawlessPatchVersion >= 0)
    }
}

data class UciSessionPolicy(
    val handshakeTimeoutMillis: Long = 5_000,
    val synchronizationTimeoutMillis: Long = 5_000,
    val searchGraceMillis: Long = 2_000,
    val requiredDrawlessPatchVersion: Int = 1,
) {
    init {
        require(handshakeTimeoutMillis > 0)
        require(synchronizationTimeoutMillis > 0)
        require(searchGraceMillis >= 0)
        require(requiredDrawlessPatchVersion >= 0)
    }
}

enum class UciSessionState {
    NEW,
    UCI_HANDSHAKE,
    STARTUP_READY,
    IDLE,
    PREPARING,
    SEARCHING,
    DRAINING_READY,
    DRAINING_SEARCH,
    FAILED,
    CLOSED,
}

class UciEngineTimeoutException(message: String) : IllegalStateException(message)
class UciEngineStateException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
class UciEngineCompatibilityException(message: String) : IllegalStateException(message)

/**
 * Platform-neutral UCI lifecycle controller. Android owns the process/JNI transport and
 * feeds complete stdout lines into [onLine]; this class owns ordering, correlation,
 * cancellation draining, timeouts, and conversion to the public engine contract.
 */
class FairyUciEngine(
    private val transport: UciTransport,
    private val timeoutScheduler: UciTimeoutScheduler,
    private val build: FairyEngineBuild,
    private val policy: UciSessionPolicy = UciSessionPolicy(),
    private val closeTransport: () -> Unit = {},
) : ChessEngine, AutoCloseable {
    private data class Work(
        val request: EngineRequest,
        val callback: (Result<EngineResponse>) -> Unit,
        val analysis: AnalysisAccumulator = AnalysisAccumulator(),
        var cancelled: Boolean = false,
    )

    private val options = linkedMapOf<String, UciOption>()
    private var engineName: String? = null
    private var engineAuthor: String? = null
    private var stateValue = UciSessionState.NEW
    private var active: Work? = null
    private var queued: Work? = null
    private var currentGameId: String? = null
    private var timer: EngineCancellation? = null
    private var timerGeneration = 0L
    private var terminalFailure: Throwable? = null

    val state: UciSessionState get() = synchronized(this) { stateValue }
    val reportedName: String? get() = synchronized(this) { engineName }
    val reportedAuthor: String? get() = synchronized(this) { engineAuthor }
    val advertisedOptions: List<UciOption> get() = synchronized(this) { options.values.toList() }

    @Synchronized
    fun start() {
        when (stateValue) {
            UciSessionState.NEW -> {
                stateValue = UciSessionState.UCI_HANDSHAKE
                send("uci")
                armTimeout(policy.handshakeTimeoutMillis, "UCI handshake")
            }
            UciSessionState.UCI_HANDSHAKE,
            UciSessionState.STARTUP_READY,
            UciSessionState.IDLE,
            UciSessionState.PREPARING,
            UciSessionState.SEARCHING,
            UciSessionState.DRAINING_READY,
            UciSessionState.DRAINING_SEARCH -> Unit
            UciSessionState.FAILED -> throw failedStateException()
            UciSessionState.CLOSED -> throw UciEngineStateException("Engine session is closed")
        }
    }

    @Synchronized
    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation {
        if (stateValue == UciSessionState.FAILED) throw failedStateException()
        if (stateValue == UciSessionState.CLOSED) throw UciEngineStateException("Engine session is closed")
        val work = Work(request, onResult)
        when {
            active == null -> active = work
            stateValue in setOf(UciSessionState.DRAINING_READY, UciSessionState.DRAINING_SEARCH) && queued == null -> {
                queued = work
            }
            else -> throw UciEngineStateException("Engine already has an active or queued request")
        }
        if (stateValue == UciSessionState.NEW) start()
        if (stateValue == UciSessionState.IDLE && active === work) prepareActive()
        return EngineCancellation { cancel(request.requestId) }
    }

    @Synchronized
    fun onLine(line: String) {
        if (stateValue == UciSessionState.CLOSED || stateValue == UciSessionState.FAILED) return
        val message = try {
            UciProtocol.parse(line)
        } catch (error: Throwable) {
            failSession(error)
            return
        }
        try {
            when (message) {
                is UciMessage.IdName -> if (stateValue == UciSessionState.UCI_HANDSHAKE) engineName = message.value
                is UciMessage.IdAuthor -> if (stateValue == UciSessionState.UCI_HANDSHAKE) engineAuthor = message.value
                is UciMessage.Option -> if (stateValue == UciSessionState.UCI_HANDSHAKE) {
                    options[normalize(message.value.name)] = message.value
                }
                UciMessage.UciOk -> onUciOk()
                UciMessage.ReadyOk -> onReadyOk()
                is UciMessage.Info -> if (stateValue == UciSessionState.SEARCHING) {
                    active?.analysis?.accept(message.value)
                }
                is UciMessage.BestMove -> onBestMove(message)
                is UciMessage.Unknown -> Unit
            }
        } catch (error: Throwable) {
            failSession(error)
        }
    }

    @Synchronized
    fun onTransportFailure(error: Throwable) {
        if (stateValue != UciSessionState.CLOSED && stateValue != UciSessionState.FAILED) {
            failSession(error)
        }
    }

    @Synchronized
    override fun close() {
        if (stateValue == UciSessionState.CLOSED) return
        cancelTimer()
        val pending = listOfNotNull(active, queued).filterNot { it.cancelled }
        active = null
        queued = null
        if (stateValue != UciSessionState.FAILED) runCatching { send("quit") }
        stateValue = UciSessionState.CLOSED
        closeTransport()
        val error = UciEngineStateException("Engine session closed before completing analysis")
        pending.forEach { deliver(it, Result.failure(error)) }
    }

    @Synchronized
    private fun cancel(requestId: String) {
        if (queued?.request?.requestId == requestId) {
            queued!!.cancelled = true
            queued = null
            return
        }
        val work = active ?: return
        if (work.request.requestId != requestId || work.cancelled) return
        work.cancelled = true
        when (stateValue) {
            UciSessionState.NEW,
            UciSessionState.UCI_HANDSHAKE,
            UciSessionState.STARTUP_READY -> active = null
            UciSessionState.PREPARING -> {
                stateValue = UciSessionState.DRAINING_READY
                armTimeout(policy.synchronizationTimeoutMillis, "cancelled request synchronization")
            }
            UciSessionState.SEARCHING -> {
                send("stop")
                stateValue = UciSessionState.DRAINING_SEARCH
                armTimeout(policy.synchronizationTimeoutMillis, "cancelled search drain")
            }
            UciSessionState.DRAINING_READY,
            UciSessionState.DRAINING_SEARCH,
            UciSessionState.IDLE,
            UciSessionState.FAILED,
            UciSessionState.CLOSED -> Unit
        }
    }

    private fun onUciOk() {
        if (stateValue != UciSessionState.UCI_HANDSHAKE) {
            throw UciEngineStateException("Unexpected uciok while $stateValue")
        }
        if (engineName.isNullOrBlank()) throw UciEngineCompatibilityException("Engine did not report its name")
        cancelTimer()
        stateValue = UciSessionState.STARTUP_READY
        send("isready")
        armTimeout(policy.synchronizationTimeoutMillis, "engine initialization")
    }

    private fun onReadyOk() {
        when (stateValue) {
            UciSessionState.STARTUP_READY -> {
                cancelTimer()
                stateValue = UciSessionState.IDLE
                if (active != null) prepareActive()
            }
            UciSessionState.PREPARING -> launchSearch()
            UciSessionState.DRAINING_READY -> {
                cancelTimer()
                active = null
                stateValue = UciSessionState.IDLE
                beginQueuedIfAny()
            }
            UciSessionState.SEARCHING -> Unit // UCI permits readiness probes during search.
            else -> throw UciEngineStateException("Unexpected readyok while $stateValue")
        }
    }

    private fun onBestMove(message: UciMessage.BestMove) {
        when (stateValue) {
            UciSessionState.SEARCHING -> {
                cancelTimer()
                val work = active ?: throw UciEngineStateException("Search has no active request")
                active = null
                stateValue = UciSessionState.IDLE
                if (!work.cancelled) {
                    val result = runCatching { work.analysis.response(work.request, message, identity()) }
                    deliver(work, result)
                }
                beginQueuedIfAny()
            }
            UciSessionState.DRAINING_SEARCH -> {
                cancelTimer()
                active = null
                stateValue = UciSessionState.IDLE
                beginQueuedIfAny()
            }
            else -> throw UciEngineStateException("Unexpected bestmove while $stateValue")
        }
    }

    private fun prepareActive() {
        val work = active ?: return
        if (work.cancelled) {
            active = null
            return
        }
        val commands = try {
            configurationCommands(work.request)
        } catch (error: Throwable) {
            active = null
            stateValue = UciSessionState.IDLE
            deliver(work, Result.failure(error))
            beginQueuedIfAny()
            return
        }
        try {
            commands.forEach(::send)
            if (currentGameId != work.request.gameId) {
                send("ucinewgame")
                currentGameId = work.request.gameId
            }
            send("isready")
            stateValue = UciSessionState.PREPARING
            armTimeout(policy.synchronizationTimeoutMillis, "analysis configuration")
        } catch (error: Throwable) {
            failSession(error)
        }
    }

    private fun launchSearch() {
        val work = active ?: throw UciEngineStateException("Prepared search has no request")
        cancelTimer()
        send(UciCommands.position(work.request.initialFen, work.request.moves))
        send(UciCommands.goMoveTime(work.request.limits.moveTimeMillis))
        stateValue = UciSessionState.SEARCHING
        armTimeout(work.request.limits.moveTimeMillis + policy.searchGraceMillis, "analysis search")
    }

    private fun beginQueuedIfAny() {
        if (active != null || stateValue != UciSessionState.IDLE) return
        val next = queued
        queued = null
        if (next != null && !next.cancelled) {
            active = next
            prepareActive()
        }
    }

    private fun configurationCommands(request: EngineRequest): List<String> {
        if (build.drawlessPatchVersion < policy.requiredDrawlessPatchVersion) {
            throw UciEngineCompatibilityException(
                "Drawless engine patch ${build.drawlessPatchVersion} is older than required patch " +
                    policy.requiredDrawlessPatchVersion,
            )
        }
        if (policy.requiredDrawlessPatchVersion > 0) {
            val advertised = option("Drawless Patch Version")
                ?: throw UciEngineCompatibilityException("Engine does not advertise its Drawless patch version")
            val reported = advertised.defaultValue?.toIntOrNull()
            if (advertised.type != UciOptionType.SPIN || reported != build.drawlessPatchVersion ||
                advertised.minimum != build.drawlessPatchVersion || advertised.maximum != build.drawlessPatchVersion) {
                throw UciEngineCompatibilityException(
                    "Engine Drawless patch identity does not match build metadata ${build.drawlessPatchVersion}",
                )
            }
        }
        val variant = request.rules.preset.name.lowercase()
        requireComboChoice("UCI_Variant", variant)
        requireSpinRange("MultiPV", request.limits.multiPv)
        val commands = mutableListOf(
            UciCommands.setOption("UCI_Variant", variant),
            UciCommands.setOption("MultiPV", request.limits.multiPv.toString()),
        )
        option("UCI_AnalyseMode")?.let {
            require(it.type == UciOptionType.CHECK)
            commands += UciCommands.setOption(
                "UCI_AnalyseMode",
                (request.purpose != EnginePurpose.BOT_MOVE).toString(),
            )
        }
        option("Syzygy50MoveRule")?.let {
            require(it.type == UciOptionType.CHECK)
            commands += UciCommands.setOption("Syzygy50MoveRule", "false")
        }
        when (val strength = request.strength) {
            is EngineStrength.ApproximateElo -> {
                requireCheck("UCI_LimitStrength")
                requireSpinRange("UCI_Elo", strength.elo)
                commands += UciCommands.setOption("UCI_LimitStrength", "true")
                commands += UciCommands.setOption("UCI_Elo", strength.elo.toString())
            }
            is EngineStrength.SkillLevel -> {
                requireCheck("UCI_LimitStrength")
                requireSpinRange("Skill Level", strength.level)
                commands += UciCommands.setOption("UCI_LimitStrength", "false")
                commands += UciCommands.setOption("Skill Level", strength.level.toString())
            }
        }
        return commands
    }

    private fun requireComboChoice(name: String, value: String) {
        val option = option(name) ?: throw UciEngineCompatibilityException("Engine is missing $name")
        if (option.type != UciOptionType.COMBO || value !in option.choices) {
            throw UciEngineCompatibilityException("Engine option $name does not support '$value'")
        }
    }

    private fun requireSpinRange(name: String, value: Int) {
        val option = option(name) ?: throw UciEngineCompatibilityException("Engine is missing $name")
        if (option.type != UciOptionType.SPIN || option.minimum == null || option.maximum == null ||
            value !in option.minimum..option.maximum) {
            throw UciEngineCompatibilityException("Engine option $name does not support $value")
        }
    }

    private fun requireCheck(name: String) {
        val option = option(name) ?: throw UciEngineCompatibilityException("Engine is missing $name")
        if (option.type != UciOptionType.CHECK) {
            throw UciEngineCompatibilityException("Engine option $name is not a check option")
        }
    }

    private fun option(name: String): UciOption? = options[normalize(name)]

    private fun identity() = EngineIdentity(
        id = engineName ?: "Fairy-Stockfish",
        build = build.buildId,
        drawlessPatch = build.drawlessPatchVersion,
    )

    private fun armTimeout(delayMillis: Long, operation: String) {
        cancelTimer()
        val generation = ++timerGeneration
        timer = timeoutScheduler.schedule(delayMillis) {
            synchronized(this) {
                if (generation == timerGeneration && stateValue !in setOf(
                        UciSessionState.IDLE, UciSessionState.FAILED, UciSessionState.CLOSED,
                    )) {
                    failSession(UciEngineTimeoutException("Timed out during $operation"))
                }
            }
        }
    }

    private fun cancelTimer() {
        timerGeneration++
        timer?.cancel()
        timer = null
    }

    private fun failSession(error: Throwable) {
        if (stateValue == UciSessionState.FAILED || stateValue == UciSessionState.CLOSED) return
        cancelTimer()
        val pending = listOfNotNull(active, queued).filterNot { it.cancelled }
        active = null
        queued = null
        terminalFailure = error
        stateValue = UciSessionState.FAILED
        closeTransport()
        pending.forEach { deliver(it, Result.failure(error)) }
    }

    private fun failedStateException(): UciEngineStateException {
        val cause = terminalFailure
        val detail = cause?.message?.takeIf(String::isNotBlank)
            ?: cause?.let { it::class.simpleName }
        val message = detail?.let { "Engine session has failed: $it" }
            ?: "Engine session has failed"
        return UciEngineStateException(message, cause)
    }

    private fun send(command: String) {
        require('\n' !in command && '\r' !in command)
        transport.send(command)
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun deliver(work: Work, result: Result<EngineResponse>) {
        // Consumer exceptions must not corrupt or terminate the reusable engine session.
        runCatching { work.callback(result) }
    }

    private class AnalysisAccumulator {
        private val variations = linkedMapOf<Int, UciInfo>()
        private var maximumDepth = 0
        private var maximumNodes = 0L

        fun accept(info: UciInfo) {
            maximumDepth = maxOf(maximumDepth, info.depth ?: 0)
            maximumNodes = maxOf(maximumNodes, info.nodes ?: 0)
            if (info.score == null || info.principalVariation.isEmpty()) return
            val rank = info.multiPv ?: 1
            val previous = variations[rank]
            if (previous == null || (info.depth ?: 0) >= (previous.depth ?: 0)) variations[rank] = info
        }

        fun response(
            request: EngineRequest,
            best: UciMessage.BestMove,
            identity: EngineIdentity,
        ): EngineResponse {
            val bestMove = best.move ?: throw UciEngineStateException("Engine returned no move for a live position")
            val converted = variations.entries
                .sortedBy { it.key }
                .take(request.limits.multiPv)
                .map { (rank, info) ->
                    when (val score = info.score!!) {
                        is UciScore.Centipawns -> PrincipalVariation(
                            scoreCentipawns = score.value,
                            mateIn = null,
                            moves = info.principalVariation,
                            rank = rank,
                            bound = score.bound,
                        )
                        is UciScore.Mate -> PrincipalVariation(
                            scoreCentipawns = null,
                            mateIn = score.value,
                            moves = info.principalVariation,
                            rank = rank,
                            bound = score.bound,
                        )
                    }
                }
                .ifEmpty { listOf(PrincipalVariation(0, null, listOf(bestMove))) }
            return EngineResponse(
                requestId = request.requestId,
                gameId = request.gameId,
                positionId = request.positionId,
                bestMove = bestMove,
                ponderMove = best.ponder,
                depth = maximumDepth,
                nodes = maximumNodes,
                variations = converted,
                engine = identity,
            )
        }
    }
}
