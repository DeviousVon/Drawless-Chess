package com.drawlesschess.engine

import com.drawlesschess.core.engine.nativebridge.NativeEnginePort
import com.drawlesschess.core.engine.nativebridge.NativeEnginePortListener
import com.drawlesschess.core.engine.nativebridge.NativeEngineStateException
import com.drawlesschess.core.engine.nativebridge.NativeExitStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Limits native I/O allocation independently from the protocol transport limits. */
data class JniFairyEnginePortPolicy(
    val readBufferBytes: Int = 16 * 1024,
) {
    init {
        require(readBufferBytes in 1..(256 * 1024))
    }
}

/**
 * In-process JNI implementation of [NativeEnginePort].
 *
 * Startup and FIFO writes use one managed executor. Stdout and stderr each get one blocking
 * reader. [close] calls the native close primitive directly so it can unblock any of those
 * workers; no executor is ever expected to interrupt a native blocking call by itself.
 */
class JniFairyEnginePort private constructor(
    variantConfigPath: String,
    private val policy: JniFairyEnginePortPolicy,
    private val nativeApi: FairyNativeApi,
) : NativeEnginePort {
    constructor(
        variantConfigPath: String,
        policy: JniFairyEnginePortPolicy = JniFairyEnginePortPolicy(),
    ) : this(variantConfigPath, policy, JniFairyNativeApi)

    internal constructor(
        variantConfigPath: String,
        nativeApi: FairyNativeApi,
        policy: JniFairyEnginePortPolicy = JniFairyEnginePortPolicy(),
    ) : this(variantConfigPath, policy, nativeApi)

    private enum class State {
        NEW,
        OPENING,
        RUNNING,
        CLOSING,
        CLOSED,
        FAILED,
    }

    private val variantConfigPath = File(variantConfigPath).canonicalFile.absolutePath
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        EngineThreadFactory("drawless-fairy-command"),
    )
    private val readerExecutor: ExecutorService = Executors.newFixedThreadPool(
        2,
        EngineThreadFactory("drawless-fairy-reader"),
    )
    private var state = State.NEW
    private var listener: NativeEnginePortListener? = null
    private var handle = NO_HANDLE
    private val launchFinished = CountDownLatch(1)
    @Volatile private var launchWorkerThread: Thread? = null

    init {
        require(this.variantConfigPath.isNotBlank()) { "Variant configuration path is blank" }
    }

    override fun open(listener: NativeEnginePortListener) {
        synchronized(this) {
            if (state != State.NEW) {
                throw NativeEngineStateException("JNI engine port can only be opened once")
            }
            this.listener = listener
            state = State.OPENING
        }

        try {
            commandExecutor.execute(::launchNativeWorker)
        } catch (error: RejectedExecutionException) {
            fail("Native engine startup executor rejected the launch", error)
            throw error
        }
    }

    override fun write(bytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        require(bytes.isNotEmpty()) { "Native engine write must not be empty" }
        val acceptedHandle = synchronized(this) {
            if (state != State.RUNNING || handle == NO_HANDLE) {
                throw NativeEngineStateException("Cannot write while JNI engine port is $state")
            }
            handle
        }
        val ownedBytes = bytes.copyOf()

        try {
            commandExecutor.execute {
                val result = runCatching {
                    writeFully(acceptedHandle, ownedBytes)
                }
                onComplete(result)
            }
        } catch (error: RejectedExecutionException) {
            onComplete(Result.failure(error))
        }
    }

    /**
     * Idempotent and safe from any callback thread. Native close wakes blocking native calls.
     * If close wins before nativeCreate publishes a handle, it waits for that worker to create and
     * immediately close the handle so a subsequent process-global session cannot race it.
     */
    override fun close() {
        val transition = synchronized(this) {
            when (state) {
                State.NEW -> {
                    state = State.CLOSED
                    CloseTransition(NO_HANDLE, awaitLaunch = false)
                }
                State.OPENING,
                State.RUNNING -> {
                    val wasOpening = state == State.OPENING
                    state = State.CLOSING
                    val acceptedHandle = takeHandleLocked()
                    CloseTransition(
                        acceptedHandle,
                        awaitLaunch = wasOpening && acceptedHandle == NO_HANDLE,
                    )
                }
                State.CLOSING,
                State.CLOSED,
                State.FAILED -> return
            }
        }

        var closeFailure: Throwable? = null
        if (transition.handle != NO_HANDLE) {
            try {
                closeNative(transition.handle)
            } catch (error: Throwable) {
                closeFailure = error
            }
        }

        synchronized(this) {
            state = if (closeFailure == null) State.CLOSED else State.FAILED
        }
        stopExecutors()
        if (transition.awaitLaunch && Thread.currentThread() !== launchWorkerThread) {
            launchFinished.await()
        }
        closeFailure?.let { throw it }
    }

    private fun launchNativeWorker() {
        launchWorkerThread = Thread.currentThread()
        var createdHandle = NO_HANDLE
        try {
            createdHandle = nativeApi.create(variantConfigPath)
            check(createdHandle > NO_HANDLE) { "Native engine returned an invalid handle" }

            val shouldStart = synchronized(this) {
                if (state == State.OPENING) {
                    handle = createdHandle
                    true
                } else {
                    false
                }
            }
            if (!shouldStart) {
                closeNative(createdHandle)
                return
            }

            nativeApi.start(createdHandle)

            val becameRunning = synchronized(this) {
                if (state == State.OPENING && handle == createdHandle) {
                    state = State.RUNNING
                    true
                } else {
                    false
                }
            }
            if (!becameRunning) return

            val target = synchronized(this) {
                listener.takeIf { state == State.RUNNING && handle == createdHandle }
            }
            target?.onStarted()

            val mayRead = synchronized(this) {
                state == State.RUNNING && handle == createdHandle
            }
            if (!mayRead) return
            readerExecutor.execute { readLoop(createdHandle, Stream.STDOUT) }
            readerExecutor.execute { readLoop(createdHandle, Stream.STDERR) }
        } catch (error: Throwable) {
            fail("Native engine failed during startup", error)
        } finally {
            launchFinished.countDown()
            launchWorkerThread = null
        }
    }

    private fun writeFully(acceptedHandle: Long, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val isCurrent = synchronized(this) {
                state == State.RUNNING && handle == acceptedHandle
            }
            if (!isCurrent) throw NativeEngineStateException("JNI engine closed during write")

            val count = nativeApi.write(
                acceptedHandle,
                bytes,
                offset,
                bytes.size - offset,
            )
            if (count <= 0 || count > bytes.size - offset) {
                throw IllegalStateException(
                    "Native write violated its contract: $count for ${bytes.size - offset} bytes",
                )
            }
            offset += count
        }
    }

    private fun readLoop(acceptedHandle: Long, stream: Stream) {
        val buffer = ByteArray(policy.readBufferBytes)
        try {
            while (true) {
                val isCurrent = synchronized(this) {
                    state == State.RUNNING && handle == acceptedHandle
                }
                if (!isCurrent) return

                val count = when (stream) {
                    Stream.STDOUT -> nativeApi.read(
                        acceptedHandle,
                        buffer,
                        0,
                        buffer.size,
                    )
                    Stream.STDERR -> nativeApi.readError(
                        acceptedHandle,
                        buffer,
                        0,
                        buffer.size,
                    )
                }
                when {
                    count == END_OF_STREAM -> {
                        fail("Native ${stream.label} reached end of stream unexpectedly")
                        return
                    }
                    count <= 0 || count > buffer.size -> throw IllegalStateException(
                        "Native ${stream.label} read violated its contract: $count",
                    )
                    else -> publishBytes(acceptedHandle, stream, buffer.copyOf(count))
                }
            }
        } catch (error: Throwable) {
            val stillRunning = synchronized(this) {
                state == State.RUNNING && handle == acceptedHandle
            }
            if (stillRunning) fail("Native ${stream.label} reader failed", error)
        }
    }

    private fun publishBytes(acceptedHandle: Long, stream: Stream, bytes: ByteArray) {
        val target = synchronized(this) {
            listener.takeIf { state == State.RUNNING && handle == acceptedHandle }
        } ?: return
        when (stream) {
            Stream.STDOUT -> target.onStdout(bytes)
            Stream.STDERR -> target.onStderr(bytes)
        }
    }

    private fun fail(message: String, cause: Throwable? = null) {
        val failure = buildString {
            append(message)
            cause?.let {
                append(": ")
                append(it.message?.takeIf(String::isNotBlank) ?: it::class.java.simpleName)
            }
        }
        val transition = synchronized(this) {
            if (state !in setOf(State.OPENING, State.RUNNING)) return
            state = State.FAILED
            FailureTransition(takeHandleLocked(), listener)
        }

        var closeDiagnostic: String? = null
        if (transition.handle != NO_HANDLE) {
            try {
                closeNative(transition.handle)
            } catch (closeError: Throwable) {
                closeDiagnostic = closeError.message ?: closeError::class.java.simpleName
            }
        }
        stopExecutors()
        val detail = closeDiagnostic?.let { "$failure; native close also failed: $it" } ?: failure
        transition.listener?.onExit(NativeExitStatus(detail = detail))
    }

    private fun takeHandleLocked(): Long = handle.also { handle = NO_HANDLE }

    private fun closeNative(handle: Long) {
        nativeApi.close(handle)
    }

    private fun stopExecutors() {
        // Accepted writes must still run and complete exactly once; after the state transition
        // they fail quickly without entering JNI. Native close, not interruption, wakes in-flight I/O.
        commandExecutor.shutdown()
        readerExecutor.shutdownNow()
    }

    private data class FailureTransition(
        val handle: Long,
        val listener: NativeEnginePortListener?,
    )

    private data class CloseTransition(
        val handle: Long,
        val awaitLaunch: Boolean,
    )

    private enum class Stream(val label: String) {
        STDOUT("stdout"),
        STDERR("stderr"),
    }

    private companion object {
        const val NO_HANDLE = 0L
        const val END_OF_STREAM = -1
    }
}

private class EngineThreadFactory(
    private val prefix: String,
) : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread = Thread(
        task,
        "$prefix-${sequence.incrementAndGet()}",
    ).apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
    }
}
