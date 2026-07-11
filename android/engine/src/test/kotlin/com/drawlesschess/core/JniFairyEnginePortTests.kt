package com.drawlesschess.core

import com.drawlesschess.core.engine.nativebridge.NativeEnginePortListener
import com.drawlesschess.core.engine.nativebridge.NativeExitStatus
import com.drawlesschess.core.engine.nativebridge.NativeTransportTermination
import com.drawlesschess.core.engine.nativebridge.NativeUciTransportListener
import com.drawlesschess.core.engine.nativebridge.SerializedNativeUciTransport
import com.drawlesschess.engine.FairyNativeApi
import com.drawlesschess.engine.JniFairyEnginePort
import java.io.File
import java.nio.charset.StandardCharsets
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val PORT_CLOSE_TIMEOUT_SECONDS = 3L

internal fun registerJniFairyEnginePortTests(suite: TestSuite) {
    suite.test("Kotlin binding exposes the exact static native ABI registered by JNI") {
        val binding = Class.forName(
            "com.drawlesschess.engine.FairyNativeBindings",
            false,
            Thread.currentThread().contextClassLoader,
        )
        val expected = mapOf(
            "nativeCreate" to listOf(String::class.java),
            "nativeStart" to listOf(Long::class.javaPrimitiveType!!),
            "nativeWrite" to listOf(
                Long::class.javaPrimitiveType!!,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
            "nativeRead" to listOf(
                Long::class.javaPrimitiveType!!,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
            "nativeReadError" to listOf(
                Long::class.javaPrimitiveType!!,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
            "nativeClose" to listOf(Long::class.javaPrimitiveType!!),
        )
        val nativeMethods = binding.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        assertThat(nativeMethods.map { it.name }.toSet() == expected.keys)
        expected.forEach { (name, parameters) ->
            val method = binding.getDeclaredMethod(name, *parameters.toTypedArray())
            assertThat(Modifier.isNative(method.modifiers) && Modifier.isStatic(method.modifiers))
        }
        assertThat(binding.getDeclaredMethod("nativeCreate", String::class.java).returnType == Long::class.javaPrimitiveType)
        listOf("nativeWrite", "nativeRead", "nativeReadError").forEach { name ->
            assertThat(binding.declaredMethods.single { it.name == name }.returnType == Int::class.javaPrimitiveType)
        }
    }

    suite.test("JNI port opens and starts with the canonical variant path") {
        val native = FakeFairyNativeApi()
        val listener = RecordingPortListener()
        val port = JniFairyEnginePort("/private/variants.ini", native)
        try {
            port.open(listener)
            listener.awaitStarted()
            assertThat(native.createdPath == File("/private/variants.ini").canonicalPath)
            assertThat(native.startCalls.get() == 1)
        } finally {
            port.close()
        }
    }

    suite.test("serialized transport queues a write until JNI startup completes") {
        val native = FakeFairyNativeApi(startBlocked = true)
        val port = JniFairyEnginePort("/private/variants.ini", native)
        val transport = SerializedNativeUciTransport(port)
        val listener = RecordingTransportListener()
        try {
            transport.start(listener)
            transport.send("uci")
            assertThat(native.writeSnapshot().isEmpty(), "write escaped before native start")
            native.releaseStart()
            listener.awaitReady()
            native.awaitWrite()
            assertThat(native.writeSnapshot() == listOf("uci\n"))
        } finally {
            transport.close()
        }
    }

    suite.test("JNI port forwards stdout and stderr from separate blocking readers") {
        val native = FakeFairyNativeApi()
        val listener = RecordingPortListener(expectedStdout = 1, expectedStderr = 1)
        val port = JniFairyEnginePort("/private/variants.ini", native)
        try {
            port.open(listener)
            listener.awaitStarted()
            native.stdout.offer(ReadEvent.Bytes("uciok\n".toByteArray()))
            native.stderr.offer(ReadEvent.Bytes("diagnostic\n".toByteArray()))
            listener.awaitOutput()
            assertThat(listener.stdoutSnapshot() == listOf("uciok\n"))
            assertThat(listener.stderrSnapshot() == listOf("diagnostic\n"))
        } finally {
            port.close()
        }
    }

    suite.test("explicit JNI port close is idempotent and never reports an exit") {
        val native = FakeFairyNativeApi()
        val listener = RecordingPortListener()
        val port = JniFairyEnginePort("/private/variants.ini", native)
        port.open(listener)
        listener.awaitStarted()
        port.close()
        port.close()
        assertThat(native.closeCalls.get() == 1)
        assertThat(listener.exitCalls.get() == 0)
    }

    suite.test("explicit close interrupts JNI startup without publishing an exit") {
        val native = FakeFairyNativeApi(startBlocked = true)
        val listener = RecordingPortListener()
        val port = JniFairyEnginePort("/private/variants.ini", native)
        port.open(listener)
        native.awaitStartEntered()
        port.close()
        assertThat(native.closeCalls.get() == 1)
        assertThat(listener.startedCalls.get() == 0)
        assertThat(listener.exitCalls.get() == 0)
    }

    suite.test("explicit close waits for an in-flight native create to release its session") {
        val native = FakeFairyNativeApi(createBlocked = true)
        val listener = RecordingPortListener()
        val port = JniFairyEnginePort("/private/variants.ini", native)
        val closeFinished = CountDownLatch(1)
        val closeFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        port.open(listener)
        native.awaitCreateEntered()

        Thread {
            try {
                port.close()
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeFinished.countDown()
            }
        }.start()

        val returnedBeforeCreate = closeFinished.await(100, TimeUnit.MILLISECONDS)
        native.releaseCreate()
        assertThat(
            closeFinished.await(PORT_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "port close timed out",
        )
        closeFailure.get()?.let { throw it }
        assertThat(!returnedBeforeCreate, "port close returned before native create released")
        assertThat(native.startCalls.get() == 0)
        assertThat(native.closeCalls.get() == 1)
        assertThat(listener.startedCalls.get() == 0)
        assertThat(listener.exitCalls.get() == 0)
    }

    suite.test("JNI startup failure closes the native handle and reports one exit") {
        val native = FakeFairyNativeApi(startFailure = IllegalStateException("start rejected"))
        val listener = RecordingPortListener(expectedExits = 1)
        val port = JniFairyEnginePort("/private/variants.ini", native)
        port.open(listener)
        listener.awaitExit()
        assertThat(listener.startedCalls.get() == 0)
        assertThat(listener.exitCalls.get() == 1)
        assertThat(listener.exitSnapshot().single().detail?.contains("start rejected") == true)
        assertThat(native.closeCalls.get() == 1)
        port.close()
    }

    suite.test("unexpected JNI EOF closes the worker and reports one exit") {
        val native = FakeFairyNativeApi()
        val listener = RecordingPortListener(expectedExits = 1)
        val port = JniFairyEnginePort("/private/variants.ini", native)
        port.open(listener)
        listener.awaitStarted()
        native.awaitReaders()
        native.stdout.offer(ReadEvent.End)
        listener.awaitExit()
        assertThat(listener.exitCalls.get() == 1)
        assertThat(listener.exitSnapshot().single().detail?.contains("stdout") == true)
        assertThat(native.closeCalls.get() == 1)
    }

    suite.test("simultaneous JNI stream EOF does not duplicate termination") {
        val native = FakeFairyNativeApi()
        val listener = RecordingPortListener(expectedExits = 1)
        val port = JniFairyEnginePort("/private/variants.ini", native)
        port.open(listener)
        listener.awaitStarted()
        native.awaitReaders()
        native.stdout.offer(ReadEvent.End)
        native.stderr.offer(ReadEvent.End)
        native.awaitReaderEnds()
        listener.awaitExit()
        assertThat(listener.exitCalls.get() == 1)
        assertThat(native.closeCalls.get() == 1)
    }
}

private sealed interface ReadEvent {
    data class Bytes(val value: ByteArray) : ReadEvent
    data object End : ReadEvent
}

private class FakeFairyNativeApi(
    createBlocked: Boolean = false,
    startBlocked: Boolean = false,
    private val startFailure: Throwable? = null,
) : FairyNativeApi {
    val stdout = LinkedBlockingQueue<ReadEvent>()
    val stderr = LinkedBlockingQueue<ReadEvent>()
    val startCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    var createdPath: String? = null
    private val writes = mutableListOf<String>()
    private val createGate = CountDownLatch(if (createBlocked) 1 else 0)
    private val createEntered = CountDownLatch(1)
    private val startGate = CountDownLatch(if (startBlocked) 1 else 0)
    private val startEntered = CountDownLatch(1)
    private val writeLatch = CountDownLatch(1)
    private val readersEntered = CountDownLatch(2)
    private val readersEnded = CountDownLatch(2)

    override fun create(variantConfigPath: String): Long {
        createdPath = variantConfigPath
        createEntered.countDown()
        assertThat(createGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native create gate timed out")
        return HANDLE
    }

    override fun start(handle: Long) {
        check(handle == HANDLE)
        startCalls.incrementAndGet()
        startEntered.countDown()
        assertThat(startGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native start gate timed out")
        startFailure?.let { throw it }
    }

    override fun write(
        handle: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(handle == HANDLE)
        synchronized(writes) {
            writes += String(bytes, offset, length, StandardCharsets.UTF_8)
        }
        writeLatch.countDown()
        return length
    }

    override fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        readFrom(handle, stdout, bytes, offset, length)

    override fun readError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        readFrom(handle, stderr, bytes, offset, length)

    override fun close(handle: Long) {
        check(handle == HANDLE)
        if (closeCalls.incrementAndGet() == 1) {
            stdout.offer(ReadEvent.End)
            stderr.offer(ReadEvent.End)
            startGate.countDown()
        }
    }

    fun releaseStart() = startGate.countDown()

    fun releaseCreate() = createGate.countDown()

    fun awaitCreateEntered() {
        assertThat(createEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native create did not begin")
    }

    fun awaitStartEntered() {
        assertThat(startEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native start did not begin")
    }

    fun writeSnapshot(): List<String> = synchronized(writes) { writes.toList() }

    fun awaitWrite() {
        assertThat(writeLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native write timed out")
    }

    fun awaitReaders() {
        assertThat(readersEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native readers did not start")
    }

    fun awaitReaderEnds() {
        assertThat(readersEnded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "native readers did not end")
    }

    private fun readFrom(
        handle: Long,
        source: LinkedBlockingQueue<ReadEvent>,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(handle == HANDLE)
        readersEntered.countDown()
        return when (val event = source.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            is ReadEvent.Bytes -> {
                check(event.value.size <= length)
                event.value.copyInto(bytes, offset)
                event.value.size
            }
            ReadEvent.End -> {
                readersEnded.countDown()
                -1
            }
            null -> throw IllegalStateException("fake native read timed out")
        }
    }

    private companion object {
        const val HANDLE = 41L
        const val TIMEOUT_SECONDS = 3L
    }
}

private class RecordingPortListener(
    expectedStdout: Int = 0,
    expectedStderr: Int = 0,
    expectedExits: Int = 0,
) : NativeEnginePortListener {
    val startedCalls = AtomicInteger()
    val exitCalls = AtomicInteger()
    private val stdout = mutableListOf<String>()
    private val stderr = mutableListOf<String>()
    private val exits = mutableListOf<NativeExitStatus>()
    private val started = CountDownLatch(1)
    private val stdoutLatch = CountDownLatch(expectedStdout)
    private val stderrLatch = CountDownLatch(expectedStderr)
    private val exitLatch = CountDownLatch(expectedExits)

    override fun onStarted() {
        startedCalls.incrementAndGet()
        started.countDown()
    }

    override fun onStdout(bytes: ByteArray) {
        synchronized(stdout) { stdout += String(bytes, StandardCharsets.UTF_8) }
        stdoutLatch.countDown()
    }

    override fun onStderr(bytes: ByteArray) {
        synchronized(stderr) { stderr += String(bytes, StandardCharsets.UTF_8) }
        stderrLatch.countDown()
    }

    override fun onExit(status: NativeExitStatus) {
        synchronized(exits) { exits += status }
        exitCalls.incrementAndGet()
        exitLatch.countDown()
    }

    fun stdoutSnapshot(): List<String> = synchronized(stdout) { stdout.toList() }
    fun stderrSnapshot(): List<String> = synchronized(stderr) { stderr.toList() }
    fun exitSnapshot(): List<NativeExitStatus> = synchronized(exits) { exits.toList() }

    fun awaitStarted() {
        assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "port startup timed out")
    }

    fun awaitOutput() {
        assertThat(stdoutLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "stdout timed out")
        assertThat(stderrLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "stderr timed out")
    }

    fun awaitExit() {
        assertThat(exitLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "port exit timed out")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}

private class RecordingTransportListener : NativeUciTransportListener {
    private val ready = CountDownLatch(1)

    override fun onReady() = ready.countDown()
    override fun onLine(line: String) = Unit
    override fun onTerminated(termination: NativeTransportTermination) = Unit

    fun awaitReady() {
        assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "transport ready timed out")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}
