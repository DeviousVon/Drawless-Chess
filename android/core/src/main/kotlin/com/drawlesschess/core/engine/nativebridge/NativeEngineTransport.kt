package com.drawlesschess.core.engine.nativebridge

import com.drawlesschess.core.engine.UciTransport
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * The byte-oriented boundary an Android process or JNI implementation must provide.
 *
 * Implementations may complete [write] synchronously or asynchronously. They must preserve
 * stdout and stderr byte order, invoke each write completion exactly once, and make [close]
 * idempotent. Callbacks are allowed to be re-entrant; [SerializedNativeUciTransport] does not
 * invoke consumer callbacks while holding its state lock.
 */
interface NativeEnginePort {
    fun open(listener: NativeEnginePortListener)

    fun write(bytes: ByteArray, onComplete: (Result<Unit>) -> Unit)

    fun close()
}

interface NativeEnginePortListener {
    /** The native endpoint is ready to accept writes. */
    fun onStarted()

    fun onStdout(bytes: ByteArray)

    fun onStderr(bytes: ByteArray)

    /** Called when the endpoint exits without an adapter-requested close. */
    fun onExit(status: NativeExitStatus)
}

data class NativeExitStatus(
    val exitCode: Int? = null,
    val signal: Int? = null,
    val detail: String? = null,
) {
    init {
        require(exitCode != null || signal != null || !detail.isNullOrBlank()) {
            "An exit status needs an exit code, signal, or diagnostic detail"
        }
        require(signal == null || signal > 0) { "A signal number must be positive" }
        require(detail == null || detail.isNotBlank()) { "Exit detail must not be blank" }
    }
}

enum class NativeEngineTransportState {
    NEW,
    STARTING,
    RUNNING,
    CLOSING,
    CLOSED,
    CRASHED,
}

sealed interface NativeTransportTermination {
    /** The app deliberately closed the transport. */
    data object Requested : NativeTransportTermination

    /** The endpoint exited on its own, including an unexpected zero exit code. */
    data class Exited(val status: NativeExitStatus) : NativeTransportTermination

    /** Launch, framing, write, or close failed before a trustworthy exit status was available. */
    data class Failed(val cause: Throwable) : NativeTransportTermination
}

interface NativeUciTransportListener {
    fun onReady() = Unit

    fun onLine(line: String)

    /** Stderr is diagnostic-only and is never parsed as UCI. */
    fun onDiagnostic(line: String) = Unit

    fun onTerminated(termination: NativeTransportTermination)
}

data class NativeEngineTransportPolicy(
    val maxPendingCommands: Int = 64,
    val maxPendingBytes: Int = 256 * 1024,
    val maxIncomingLineBytes: Int = 64 * 1024,
) {
    init {
        require(maxPendingCommands > 0)
        require(maxPendingBytes > 0)
        require(maxIncomingLineBytes > 0)
    }
}

class NativeEngineStateException(message: String) : IllegalStateException(message)
class NativeEngineBackpressureException(message: String) : IllegalStateException(message)
class NativeEngineContractException(message: String) : IllegalStateException(message)
class NativeEngineFramingException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class NativeEngineTerminatedException(
    val termination: NativeTransportTermination,
) : IllegalStateException(termination.describe())

fun NativeTransportTermination.asException(): Throwable = when (this) {
    is NativeTransportTermination.Failed -> cause
    else -> NativeEngineTerminatedException(this)
}

private fun NativeTransportTermination.describe(): String = when (this) {
    NativeTransportTermination.Requested -> "Native engine transport was closed"
    is NativeTransportTermination.Failed -> "Native engine transport failed: ${cause.message ?: cause::class.simpleName}"
    is NativeTransportTermination.Exited -> buildString {
        append("Native engine exited unexpectedly")
        status.exitCode?.let { append(" with code $it") }
        status.signal?.let { append(" from signal $it") }
        status.detail?.let { append(": $it") }
    }
}

/**
 * Incrementally frames strict UTF-8 lines. LF and CRLF are accepted; bare CR and NUL are not.
 * The byte limit applies to line content and prevents an unhealthy native endpoint from growing
 * memory without bound.
 */
class Utf8LineFramer(
    val maxLineBytes: Int = 64 * 1024,
) {
    init { require(maxLineBytes > 0) }

    private var buffer = ByteArray(minOf(maxLineBytes + 1, 256))
    private var size = 0
    private var ended = false

    val bufferedBytes: Int get() = synchronized(this) { size }

    @Synchronized
    fun accept(bytes: ByteArray): List<String> {
        check(!ended) { "Line framer has reached end of input" }
        if (bytes.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            if (unsigned == 0) throw NativeEngineFramingException("NUL is not valid in engine text output")
            if (size > 0 && buffer[size - 1] == CARRIAGE_RETURN && unsigned != LINE_FEED) {
                throw NativeEngineFramingException("Bare carriage return in engine text output")
            }
            when (unsigned) {
                LINE_FEED -> {
                    val contentSize = if (size > 0 && buffer[size - 1] == CARRIAGE_RETURN) size - 1 else size
                    lines += decode(contentSize)
                    size = 0
                }
                CARRIAGE_RETURN.toInt() and 0xff -> append(byte, allowTerminatorByte = true)
                else -> append(byte, allowTerminatorByte = false)
            }
        }
        return lines
    }

    /** Returns a final unterminated line, if present. */
    @Synchronized
    fun endOfInput(): String? {
        check(!ended) { "Line framer has already reached end of input" }
        ended = true
        if (size == 0) return null
        if (buffer[size - 1] == CARRIAGE_RETURN) {
            throw NativeEngineFramingException("Input ended after a bare carriage return")
        }
        return decode(size).also { size = 0 }
    }

    @Synchronized
    fun reset() {
        size = 0
        ended = false
    }

    private fun append(byte: Byte, allowTerminatorByte: Boolean) {
        val contentSizeAfterAppend = size + if (allowTerminatorByte) 0 else 1
        if (contentSizeAfterAppend > maxLineBytes) {
            throw NativeEngineFramingException("Engine line exceeds $maxLineBytes bytes")
        }
        ensureCapacity(size + 1)
        buffer[size++] = byte
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        val maximum = maxLineBytes + 1
        val grown = maxOf(required, minOf(maximum, buffer.size * 2))
        buffer = buffer.copyOf(grown)
    }

    private fun decode(length: Int): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer, 0, length))
            .toString()
    } catch (error: Throwable) {
        throw NativeEngineFramingException("Malformed UTF-8 in engine text output", error)
    }

    private companion object {
        const val LINE_FEED = 10
        val CARRIAGE_RETURN: Byte = 13
    }
}

/**
 * Adapts asynchronous native byte I/O to the command-oriented UCI transport.
 *
 * Commands are newline-framed and written FIFO with at most one native write in flight. Commands
 * sent while the port is starting are bounded and queued. Backpressure is reported synchronously
 * to the caller. Incoming callbacks and termination are serialized before reaching [listener].
 */
class SerializedNativeUciTransport(
    private val port: NativeEnginePort,
    private val policy: NativeEngineTransportPolicy = NativeEngineTransportPolicy(),
) : UciTransport, AutoCloseable {
    private data class PendingWrite(val id: Long, val bytes: ByteArray)

    private val writes = ArrayDeque<PendingWrite>()
    private val callbackQueue = ArrayDeque<() -> Unit>()
    private val stdout = Utf8LineFramer(policy.maxIncomingLineBytes)
    private val stderr = Utf8LineFramer(policy.maxIncomingLineBytes)
    private var stateValue = NativeEngineTransportState.NEW
    private var listener: NativeUciTransportListener? = null
    private var nextWriteId = 0L
    private var inFlightWriteId: Long? = null
    private var pendingBytesValue = 0
    private var pumping = false
    private var dispatchingCallbacks = false

    val state: NativeEngineTransportState get() = synchronized(this) { stateValue }
    val pendingCommandCount: Int get() = synchronized(this) { writes.size }
    val pendingBytes: Int get() = synchronized(this) { pendingBytesValue }
    val hasWriteInFlight: Boolean get() = synchronized(this) { inFlightWriteId != null }

    fun start(listener: NativeUciTransportListener) {
        synchronized(this) {
            if (stateValue != NativeEngineTransportState.NEW) {
                throw NativeEngineStateException("Native engine transport can only be started once")
            }
            this.listener = listener
            stateValue = NativeEngineTransportState.STARTING
        }
        try {
            port.open(portListener)
        } catch (error: Throwable) {
            failAndClose(error)
            throw error
        }
    }

    override fun send(command: String) {
        require(command.isNotBlank()) { "UCI command must not be blank" }
        require(command.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "UCI command must be exactly one text line"
        }
        val frame = (command + "\n").toByteArray(StandardCharsets.UTF_8)
        synchronized(this) {
            if (stateValue !in setOf(NativeEngineTransportState.STARTING, NativeEngineTransportState.RUNNING)) {
                throw NativeEngineStateException("Cannot write while native engine transport is $stateValue")
            }
            if (writes.size >= policy.maxPendingCommands) {
                throw NativeEngineBackpressureException(
                    "Native engine queue is limited to ${policy.maxPendingCommands} commands",
                )
            }
            if (frame.size > policy.maxPendingBytes - pendingBytesValue) {
                throw NativeEngineBackpressureException(
                    "Native engine queue is limited to ${policy.maxPendingBytes} bytes",
                )
            }
            writes.addLast(PendingWrite(++nextWriteId, frame))
            pendingBytesValue += frame.size
        }
        pumpWrites()
    }

    override fun close() {
        val shouldClosePort = synchronized(this) {
            when (stateValue) {
                NativeEngineTransportState.NEW -> {
                    stateValue = NativeEngineTransportState.CLOSED
                    false
                }
                NativeEngineTransportState.STARTING,
                NativeEngineTransportState.RUNNING -> {
                    stateValue = NativeEngineTransportState.CLOSING
                    clearWritesLocked()
                    stdout.reset()
                    stderr.reset()
                    true
                }
                NativeEngineTransportState.CLOSING,
                NativeEngineTransportState.CLOSED,
                NativeEngineTransportState.CRASHED -> false
            }
        }
        if (!shouldClosePort) return

        var closeFailure: Throwable? = null
        try {
            port.close()
        } catch (error: Throwable) {
            closeFailure = error
        }

        val termination = synchronized(this) {
            if (stateValue != NativeEngineTransportState.CLOSING) return@synchronized null
            if (closeFailure == null) {
                stateValue = NativeEngineTransportState.CLOSED
                NativeTransportTermination.Requested
            } else {
                stateValue = NativeEngineTransportState.CRASHED
                NativeTransportTermination.Failed(closeFailure)
            }
        }
        termination?.let(::publishTermination)
    }

    private val portListener = object : NativeEnginePortListener {
        override fun onStarted() = handleStarted()
        override fun onStdout(bytes: ByteArray) = handleBytes(bytes, stdout, diagnostic = false)
        override fun onStderr(bytes: ByteArray) = handleBytes(bytes, stderr, diagnostic = true)
        override fun onExit(status: NativeExitStatus) = handleExit(status)
    }

    private fun handleStarted() {
        val contractFailure = synchronized(this) {
            when (stateValue) {
                NativeEngineTransportState.STARTING -> {
                    stateValue = NativeEngineTransportState.RUNNING
                    null
                }
                NativeEngineTransportState.CLOSING,
                NativeEngineTransportState.CLOSED,
                NativeEngineTransportState.CRASHED -> return
                else -> NativeEngineContractException("Unexpected native onStarted callback while $stateValue")
            }
        }
        if (contractFailure != null) {
            failAndClose(contractFailure)
            return
        }
        listenerSnapshot()?.let { target -> publish { target.onReady() } }
        pumpWrites()
    }

    private fun handleBytes(bytes: ByteArray, framer: Utf8LineFramer, diagnostic: Boolean) {
        val lines = try {
            synchronized(this) {
                if (stateValue !in setOf(NativeEngineTransportState.STARTING, NativeEngineTransportState.RUNNING)) {
                    return
                }
                framer.accept(bytes.copyOf())
            }
        } catch (error: Throwable) {
            failAndClose(error)
            return
        }
        val target = listenerSnapshot() ?: return
        publish(lines.map { line ->
            if (diagnostic) ({ target.onDiagnostic(line) }) else ({ target.onLine(line) })
        })
    }

    private fun handleExit(status: NativeExitStatus) {
        val termination = synchronized(this) {
            when (stateValue) {
                NativeEngineTransportState.STARTING,
                NativeEngineTransportState.RUNNING -> {
                    stateValue = NativeEngineTransportState.CRASHED
                    clearWritesLocked()
                    stdout.reset()
                    stderr.reset()
                    NativeTransportTermination.Exited(status)
                }
                NativeEngineTransportState.CLOSING -> {
                    stateValue = NativeEngineTransportState.CLOSED
                    clearWritesLocked()
                    NativeTransportTermination.Requested
                }
                NativeEngineTransportState.NEW ->
                    NativeTransportTermination.Failed(
                        NativeEngineContractException("Native endpoint exited before it was opened"),
                    )
                NativeEngineTransportState.CLOSED,
                NativeEngineTransportState.CRASHED -> null
            }
        }
        termination?.let(::publishTermination)
    }

    private fun pumpWrites() {
        synchronized(this) {
            if (pumping) return
            pumping = true
        }
        while (true) {
            val next = synchronized(this) {
                if (stateValue != NativeEngineTransportState.RUNNING || inFlightWriteId != null || writes.isEmpty()) {
                    pumping = false
                    return
                }
                writes.first.also { inFlightWriteId = it.id }
            }

            try {
                port.write(next.bytes.copyOf()) { result -> handleWriteCompletion(next.id, result) }
            } catch (error: Throwable) {
                handleWriteCompletion(next.id, Result.failure(error))
            }

            val completedSynchronously = synchronized(this) {
                when {
                    stateValue != NativeEngineTransportState.RUNNING -> {
                        pumping = false
                        false
                    }
                    inFlightWriteId == next.id -> {
                        pumping = false
                        false
                    }
                    else -> true
                }
            }
            if (!completedSynchronously) return
        }
    }

    private fun handleWriteCompletion(id: Long, result: Result<Unit>) {
        var failure: Throwable? = null
        synchronized(this) {
            if (stateValue != NativeEngineTransportState.RUNNING) return
            if (inFlightWriteId != id || writes.isEmpty() || writes.first.id != id) {
                failure = NativeEngineContractException("Native write $id completed out of order or more than once")
            } else if (result.isFailure) {
                failure = result.exceptionOrNull() ?: NativeEngineContractException("Native write failed without a cause")
                inFlightWriteId = null
            } else {
                val completed = writes.removeFirst()
                pendingBytesValue -= completed.bytes.size
                inFlightWriteId = null
            }
        }
        val completionFailure = failure
        if (completionFailure != null) {
            failAndClose(completionFailure)
        } else {
            pumpWrites()
        }
    }

    private fun failAndClose(error: Throwable) {
        val shouldClosePort = synchronized(this) {
            if (stateValue in setOf(
                    NativeEngineTransportState.CLOSED,
                    NativeEngineTransportState.CRASHED,
                )) return
            stateValue = NativeEngineTransportState.CRASHED
            clearWritesLocked()
            stdout.reset()
            stderr.reset()
            true
        }
        if (!shouldClosePort) return
        try {
            port.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        publishTermination(NativeTransportTermination.Failed(error))
    }

    private fun clearWritesLocked() {
        writes.clear()
        pendingBytesValue = 0
        inFlightWriteId = null
    }

    private fun listenerSnapshot(): NativeUciTransportListener? = synchronized(this) { listener }

    private fun publishTermination(termination: NativeTransportTermination) {
        val target = listenerSnapshot() ?: return
        publish { target.onTerminated(termination) }
    }

    private fun publish(action: () -> Unit) = publish(listOf(action))

    private fun publish(actions: List<() -> Unit>) {
        if (actions.isEmpty()) return
        val shouldDrain = synchronized(this) {
            actions.forEach(callbackQueue::addLast)
            if (dispatchingCallbacks) false else {
                dispatchingCallbacks = true
                true
            }
        }
        if (!shouldDrain) return

        while (true) {
            val callback = synchronized(this) {
                if (callbackQueue.isEmpty()) {
                    dispatchingCallbacks = false
                    return
                }
                callbackQueue.removeFirst()
            }
            // Consumer failures must not corrupt the native transport or prevent later callbacks.
            runCatching(callback)
        }
    }
}
