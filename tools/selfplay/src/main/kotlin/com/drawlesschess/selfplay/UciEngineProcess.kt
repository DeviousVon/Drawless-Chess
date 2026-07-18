package com.drawlesschess.selfplay

import com.drawlesschess.core.UciMove
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class UciSearchResult(
    val move: UciMove,
    val ponder: UciMove?,
    val elapsedMillis: Long,
    val depth: Int?,
    val nodes: Long?,
    val scoreType: String?,
    val scoreValue: Int?,
    val variations: List<UciVariation>,
)

data class UciVariation(
    val rank: Int,
    val depth: Int?,
    val nodes: Long?,
    val scoreType: String,
    val scoreValue: Int,
    val moves: List<UciMove>,
)

class UciProcessException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class UciSearchTimeoutException(message: String) : IllegalStateException(message)

/**
 * Owns exactly one standalone Fairy-Stockfish process and one side's private search state.
 * Stdout and stderr remain separate: only stdout is parsed as UCI.
 */
class UciEngineProcess private constructor(
    private val config: SelfPlayConfig,
    val role: String,
    val strength: UciStrength,
    private val multiPv: Int,
    private val analyseMode: Boolean,
) : AutoCloseable {
    private sealed interface OutputEvent {
        data class Line(val value: String) : OutputEvent
        data class Failed(val error: Throwable) : OutputEvent
        data object End : OutputEvent
    }

    private val process = ProcessBuilder(config.enginePath.toString())
        .directory(config.enginePath.parent.toFile())
        .redirectErrorStream(false)
        .also { builder ->
            builder.environment()["LC_ALL"] = "C"
            builder.environment()["LANG"] = "C"
        }
        .start()
    private val input: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val output = LinkedBlockingQueue<OutputEvent>()
    private val recentOutput = ArrayDeque<String>()
    private val recentErrors = ArrayDeque<String>()
    private val closed = AtomicBoolean(false)
    private val stdoutThread = thread(
        start = true,
        isDaemon = true,
        name = "selfplay-$role-stdout-${process.pid()}",
    ) {
        try {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line -> output.put(OutputEvent.Line(line.trimEnd())) }
            }
            output.put(OutputEvent.End)
        } catch (error: Throwable) {
            output.offer(OutputEvent.Failed(error))
        }
    }
    private val stderrThread = thread(
        start = true,
        isDaemon = true,
        name = "selfplay-$role-stderr-${process.pid()}",
    ) {
        try {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line -> synchronized(recentErrors) { recentErrors.addBounded(line) } }
            }
        } catch (error: Throwable) {
            synchronized(recentErrors) {
                recentErrors.addBounded("stderr reader failed: ${error.message ?: error::class.java.name}")
            }
        }
    }

    var engineName: String? = null
        private set

    init {
        require(multiPv in 1..500) { "MultiPV must be in 1..500" }
        try {
            initialize()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun search(initialFen: String, moves: List<UciMove>, limit: SearchLimit): UciSearchResult {
        check(!closed.get()) { "Engine process $role is closed" }
        val root = if (initialFen == START_FEN) "startpos" else "fen $initialFen"
        val position = buildString {
            append("position ").append(root)
            if (moves.isNotEmpty()) {
                append(" moves ")
                append(moves.joinToString(" ") { it.value })
            }
        }
        send(position)
        val started = System.nanoTime()
        send(limit.uciCommand)

        var depth: Int? = null
        var nodes: Long? = null
        var scoreType: String? = null
        var scoreValue: Int? = null
        val variations = mutableMapOf<Int, UciVariation>()
        val deadline = deadlineAfter(config.searchTimeoutMillis)
        try {
            while (true) {
                val line = nextLine(deadline, "bestmove")
                if (line.startsWith("info ")) {
                    val lineDepth = DEPTH.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val lineNodes = NODES.find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val rank = MULTIPV.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val score = SCORE.find(line)
                    val lineScoreType = score?.groupValues?.get(1)
                    val lineScoreValue = score?.groupValues?.get(2)?.toIntOrNull()
                    val pv = PV.find(line)?.groupValues?.get(1)
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.filter(String::isNotEmpty)
                        ?.map(::UciMove)
                        .orEmpty()
                    if (rank == 1) {
                        lineDepth?.let { depth = it }
                        lineNodes?.let { nodes = it }
                        if (lineScoreType != null && lineScoreValue != null) {
                            scoreType = lineScoreType
                            scoreValue = lineScoreValue
                        }
                    }
                    if (
                        rank in 1..multiPv &&
                        lineScoreType != null &&
                        lineScoreValue != null &&
                        pv.isNotEmpty()
                    ) {
                        variations[rank] = UciVariation(
                            rank = rank,
                            depth = lineDepth,
                            nodes = lineNodes,
                            scoreType = lineScoreType,
                            scoreValue = lineScoreValue,
                            moves = pv,
                        )
                    }
                    continue
                }
                if (!line.startsWith("bestmove ")) continue
                val fields = line.split(Regex("\\s+"))
                val encoded = fields.getOrNull(1)
                    ?: throw UciProcessException("$role returned a malformed bestmove: $line")
                if (encoded == "(none)" || encoded == "0000") {
                    throw UciProcessException(
                        "$role returned no move before app adjudication; ${diagnostics()}",
                    )
                }
                val ponder = fields.indexOf("ponder").takeIf { it >= 0 }
                    ?.let { index -> fields.getOrNull(index + 1) }
                    ?.let(::UciMove)
                return UciSearchResult(
                    move = UciMove(encoded),
                    ponder = ponder,
                    elapsedMillis = elapsedMillis(started),
                    depth = depth,
                    nodes = nodes,
                    scoreType = scoreType,
                    scoreValue = scoreValue,
                    variations = variations.values.sortedBy(UciVariation::rank),
                )
            }
        } catch (timeout: DeadlineExceeded) {
            runCatching { send("stop") }
            runCatching { drainAfterStop() }
            terminateForcibly()
            throw UciSearchTimeoutException(
                "$role search exceeded ${config.searchTimeoutMillis} ms; ${diagnostics()}",
            )
        }
    }

    @Synchronized
    fun resetGame() {
        check(!closed.get()) { "Engine process $role is closed" }
        send("ucinewgame")
        send("setoption name Clear Hash")
        send("isready")
        awaitExact("readyok", config.readyTimeoutMillis)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var interrupted = Thread.interrupted()

        fun waitForProcess(timeoutMillis: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        }

        fun joinReader(reader: Thread, timeoutMillis: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (reader.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return
                try {
                    val millis = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)
                    reader.join(millis)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }

        try {
            if (process.isAlive) {
                runCatching {
                    input.write("quit\n")
                    input.flush()
                }
                if (!waitForProcess(config.quitTimeoutMillis)) {
                    destroyProcessTree(forcibly = false)
                    if (!waitForProcess(250L)) {
                        destroyProcessTree(forcibly = true)
                        waitForProcess(1_000L)
                    }
                }
            }
        } finally {
            runCatching { input.close() }
            if (process.isAlive) {
                destroyProcessTree(forcibly = true)
                waitForProcess(1_000L)
            }
            joinReader(stdoutThread, 250L)
            joinReader(stderrThread, 250L)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun diagnostics(): String {
        val stdout = synchronized(recentOutput) { recentOutput.joinToString(" | ") }
        val stderr = synchronized(recentErrors) { recentErrors.joinToString(" | ") }
        val exit = if (process.isAlive) "alive" else "exit=${runCatching { process.exitValue() }.getOrNull()}"
        return "process=$exit, stdout=[$stdout], stderr=[$stderr]"
    }

    private fun initialize() {
        send("uci")
        val handshakeLines = mutableListOf<String>()
        val deadline = deadlineAfter(config.handshakeTimeoutMillis)
        while (true) {
            val line = nextLine(deadline, "uciok")
            handshakeLines += line
            if (line.startsWith("id name ")) engineName = line.removePrefix("id name ").trim()
            if (line == "uciok") break
        }
        require(handshakeLines.any { it.startsWith("option name VariantPath ") }) {
            "$role engine does not advertise VariantPath"
        }
        require(handshakeLines.any { it.startsWith("option name UCI_Variant ") }) {
            "$role engine does not advertise UCI_Variant"
        }
        require(handshakeLines.count { it == PATCH_OPTION } == 1) {
            "$role engine does not advertise exact Drawless patch v1 identity"
        }

        setOption("VariantPath", config.variantsPath.toString())
        setOption("UCI_Variant", config.variant.name.lowercase())
        setOption("Threads", "1")
        setOption("Hash", config.hashMb.toString())
        setOption("Ponder", "false")
        setOption("MultiPV", multiPv.toString())
        setOption("UCI_AnalyseMode", analyseMode.toString())
        setOption("Syzygy50MoveRule", "false")
        setOption("Use NNUE", "true")
        when (val selected = strength) {
            is UciStrength.Elo -> {
                setOption("UCI_LimitStrength", "true")
                setOption("UCI_Elo", selected.value.toString())
            }
            is UciStrength.Skill -> {
                setOption("UCI_LimitStrength", "false")
                setOption("Skill Level", selected.value.toString())
            }
        }
        send("isready")
        awaitExact("readyok", config.readyTimeoutMillis)
    }

    private fun setOption(name: String, value: String) {
        requireLineSafe(name)
        requireLineSafe(value)
        send("setoption name $name value $value")
    }

    private fun awaitExact(expected: String, timeoutMillis: Long) {
        val deadline = deadlineAfter(timeoutMillis)
        while (true) {
            if (nextLine(deadline, expected) == expected) return
        }
    }

    private fun drainAfterStop() {
        val deadline = deadlineAfter(config.stopGraceMillis)
        while (true) {
            if (nextLine(deadline, "bestmove after stop").startsWith("bestmove ")) return
        }
    }

    @Synchronized
    private fun send(command: String) {
        requireLineSafe(command)
        if (!process.isAlive) {
            throw UciProcessException("$role engine exited before '$command'; ${diagnostics()}")
        }
        try {
            input.write(command)
            input.newLine()
            input.flush()
        } catch (error: Throwable) {
            throw UciProcessException("Could not write '$command' to $role; ${diagnostics()}", error)
        }
    }

    private fun nextLine(deadlineNanos: Long, waitingFor: String): String {
        while (true) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) throw DeadlineExceeded(waitingFor)
            val event = output.poll(remaining, TimeUnit.NANOSECONDS)
                ?: throw DeadlineExceeded(waitingFor)
            when (event) {
                is OutputEvent.Line -> {
                    synchronized(recentOutput) { recentOutput.addBounded(event.value) }
                    return event.value
                }
                is OutputEvent.Failed -> throw UciProcessException(
                    "$role stdout reader failed while waiting for $waitingFor; ${diagnostics()}",
                    event.error,
                )
                OutputEvent.End -> throw UciProcessException(
                    "$role stdout ended while waiting for $waitingFor; ${diagnostics()}",
                )
            }
        }
    }

    private fun terminateForcibly() {
        destroyProcessTree(forcibly = false)
        if (!runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            destroyProcessTree(forcibly = true)
            runCatching { process.waitFor(1_000L, TimeUnit.MILLISECONDS) }
        }
    }

    private fun destroyProcessTree(forcibly: Boolean) {
        runCatching {
            process.descendants().use { descendants ->
                descendants.forEach { child ->
                    if (forcibly) child.destroyForcibly() else child.destroy()
                }
            }
        }
        if (forcibly) process.destroyForcibly() else process.destroy()
    }

    private fun requireLineSafe(text: String) {
        require(text.isNotBlank() && text.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "UCI text must be one non-empty line"
        }
    }

    private class DeadlineExceeded(waitingFor: String) :
        IllegalStateException("Timed out waiting for $waitingFor")

    private fun ArrayDeque<String>.addBounded(value: String) {
        if (size == DIAGNOSTIC_LINES) removeFirst()
        addLast(value)
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        const val DIAGNOSTIC_LINES = 40
        const val PATCH_OPTION =
            "option name Drawless Patch Version type spin default 1 min 1 max 1"
        val DEPTH = Regex("(?:^|\\s)depth (\\d+)(?:\\s|$)")
        val NODES = Regex("(?:^|\\s)nodes (\\d+)(?:\\s|$)")
        val SCORE = Regex("(?:^|\\s)score (cp|mate) (-?\\d+)(?:\\s|$)")
        val MULTIPV = Regex("(?:^|\\s)multipv (\\d+)(?:\\s|$)")
        val PV = Regex("(?:^|\\s)pv (.+)$")

        internal fun start(
            config: SelfPlayConfig,
            role: String,
            strength: UciStrength,
            multiPv: Int = 1,
            analyseMode: Boolean = false,
        ): UciEngineProcess =
            UciEngineProcess(config, role, strength, multiPv, analyseMode)

        fun deadlineAfter(timeoutMillis: Long): Long =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

        fun elapsedMillis(startedNanos: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    }
}
