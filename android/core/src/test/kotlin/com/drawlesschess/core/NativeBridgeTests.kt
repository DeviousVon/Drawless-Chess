package com.drawlesschess.core

import com.drawlesschess.core.engine.nativebridge.*
import com.drawlesschess.core.engine.FairyEngineBuild
import com.drawlesschess.core.engine.UciSessionPolicy
import com.drawlesschess.core.engine.UciTimeoutScheduler
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

private class FakeNativeEnginePort : NativeEnginePort {
    data class PendingWrite(
        val bytes: ByteArray,
        val completion: (Result<Unit>) -> Unit,
    )

    var listener: NativeEnginePortListener? = null
    var openFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var completeWritesSynchronously = false
    var exitDuringClose: NativeExitStatus? = null
    var openCount = 0
    var closeCount = 0
    val writes = mutableListOf<String>()
    val pendingWrites = ArrayDeque<PendingWrite>()

    override fun open(listener: NativeEnginePortListener) {
        openCount++
        this.listener = listener
        openFailure?.let { throw it }
    }

    override fun write(bytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        val owned = bytes.copyOf()
        writes += owned.toString(StandardCharsets.UTF_8)
        if (completeWritesSynchronously) {
            onComplete(Result.success(Unit))
        } else {
            pendingWrites.addLast(PendingWrite(owned, onComplete))
        }
    }

    override fun close() {
        closeCount++
        exitDuringClose?.let { listener?.onExit(it) }
        closeFailure?.let { throw it }
    }

    fun started() = requireNotNull(listener).onStarted()
    fun stdout(value: ByteArray) = requireNotNull(listener).onStdout(value.copyOf())
    fun stderr(value: ByteArray) = requireNotNull(listener).onStderr(value.copyOf())
    fun exit(status: NativeExitStatus) = requireNotNull(listener).onExit(status)

    fun completeNext(result: Result<Unit> = Result.success(Unit)): PendingWrite {
        val pending = pendingWrites.removeFirst()
        pending.completion(result)
        return pending
    }
}

private class RecordingNativeListener : NativeUciTransportListener {
    var readyCount = 0
    val lines = mutableListOf<String>()
    val diagnostics = mutableListOf<String>()
    val terminations = mutableListOf<NativeTransportTermination>()
    var throwOnLine: String? = null

    override fun onReady() { readyCount++ }

    override fun onLine(line: String) {
        lines += line
        if (line == throwOnLine) error("consumer failure")
    }

    override fun onDiagnostic(line: String) { diagnostics += line }

    override fun onTerminated(termination: NativeTransportTermination) {
        terminations += termination
    }
}

private data class NativeTransportFixture(
    val transport: SerializedNativeUciTransport,
    val port: FakeNativeEnginePort,
    val listener: RecordingNativeListener,
)

private fun nativeTransportFixture(
    policy: NativeEngineTransportPolicy = NativeEngineTransportPolicy(),
    startEndpoint: Boolean = true,
): NativeTransportFixture {
    val port = FakeNativeEnginePort()
    val listener = RecordingNativeListener()
    val transport = SerializedNativeUciTransport(port, policy)
    transport.start(listener)
    if (startEndpoint) port.started()
    return NativeTransportFixture(transport, port, listener)
}

internal fun registerNativeBridgeTests(suite: TestSuite) {
    suite.test("native line framer handles split UTF-8 and CRLF") {
        val framer = Utf8LineFramer()
        val encoded = "id name Fäiry\r\nready".toByteArray()
        val splitInsideUtf8 = encoded.indexOfFirst { (it.toInt() and 0xff) == 0xc3 } + 1
        assertThat(framer.accept(encoded.copyOfRange(0, splitInsideUtf8)).isEmpty())
        assertThat(framer.accept(encoded.copyOfRange(splitInsideUtf8, encoded.size)) == listOf("id name Fäiry"))
        assertThat(framer.endOfInput() == "ready")
    }
    suite.test("native line framer keeps empty lines and split terminators") {
        val framer = Utf8LineFramer()
        assertThat(framer.accept("\nfirst\r".toByteArray()) == listOf(""))
        assertThat(framer.accept("\nsecond\n".toByteArray()) == listOf("first", "second"))
        assertThat(framer.endOfInput() == null)
    }
    suite.test("native line framer rejects malformed UTF-8") {
        val framer = Utf8LineFramer()
        assertThrows<NativeEngineFramingException> {
            framer.accept(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()))
        }
    }
    suite.test("native line framer rejects bare CR and NUL") {
        assertThrows<NativeEngineFramingException> {
            Utf8LineFramer().accept("broken\rtext\n".toByteArray())
        }
        assertThrows<NativeEngineFramingException> {
            Utf8LineFramer().accept(byteArrayOf('o'.code.toByte(), 0, '\n'.code.toByte()))
        }
    }
    suite.test("native line framer enforces byte limit across chunks") {
        val framer = Utf8LineFramer(maxLineBytes = 4)
        assertThat(framer.accept("1234".toByteArray()).isEmpty())
        assertThrows<NativeEngineFramingException> { framer.accept("5".toByteArray()) }
    }
    suite.test("native transport queues startup commands and serializes writes") {
        val fixture = nativeTransportFixture(startEndpoint = false)
        fixture.transport.send("uci")
        fixture.transport.send("isready")
        assertThat(fixture.port.writes.isEmpty())
        assertThat(fixture.transport.pendingCommandCount == 2)
        fixture.port.started()
        assertThat(fixture.listener.readyCount == 1)
        assertThat(fixture.port.writes == listOf("uci\n"))
        assertThat(fixture.transport.hasWriteInFlight)
        fixture.port.completeNext()
        assertThat(fixture.port.writes == listOf("uci\n", "isready\n"))
        fixture.port.completeNext()
        assertThat(fixture.transport.pendingCommandCount == 0 && fixture.transport.pendingBytes == 0)
    }
    suite.test("native transport handles synchronous write completions without recursion") {
        val fixture = nativeTransportFixture(
            policy = NativeEngineTransportPolicy(maxPendingCommands = 1_000),
            startEndpoint = false,
        )
        repeat(1_000) { fixture.transport.send("command $it") }
        fixture.port.completeWritesSynchronously = true
        fixture.port.started()
        assertThat(fixture.port.writes.size == 1_000)
        assertThat(fixture.transport.pendingCommandCount == 0)
        assertThat(fixture.transport.state == NativeEngineTransportState.RUNNING)
    }
    suite.test("native transport reports command-count backpressure without crashing") {
        val fixture = nativeTransportFixture(
            policy = NativeEngineTransportPolicy(maxPendingCommands = 2, maxPendingBytes = 100),
            startEndpoint = false,
        )
        fixture.transport.send("one")
        fixture.transport.send("two")
        assertThrows<NativeEngineBackpressureException> { fixture.transport.send("three") }
        assertThat(fixture.transport.state == NativeEngineTransportState.STARTING)
        assertThat(fixture.transport.pendingCommandCount == 2)
    }
    suite.test("native transport reports byte backpressure without partial enqueue") {
        val fixture = nativeTransportFixture(
            policy = NativeEngineTransportPolicy(maxPendingCommands = 10, maxPendingBytes = 8),
            startEndpoint = false,
        )
        fixture.transport.send("1234") // five framed bytes
        assertThrows<NativeEngineBackpressureException> { fixture.transport.send("5678") }
        assertThat(fixture.transport.pendingBytes == 5 && fixture.transport.pendingCommandCount == 1)
    }
    suite.test("native transport frames stdout and keeps stderr diagnostic-only") {
        val fixture = nativeTransportFixture()
        fixture.port.stdout("id name Fairy\nready".toByteArray())
        fixture.port.stdout("ok\r\n".toByteArray())
        fixture.port.stderr("NNUE loaded\n".toByteArray())
        assertThat(fixture.listener.lines == listOf("id name Fairy", "readyok"))
        assertThat(fixture.listener.diagnostics == listOf("NNUE loaded"))
    }
    suite.test("consumer callback failure does not stop later native lines") {
        val fixture = nativeTransportFixture()
        fixture.listener.throwOnLine = "bad consumer"
        fixture.port.stdout("bad consumer\nreadyok\n".toByteArray())
        assertThat(fixture.listener.lines == listOf("bad consumer", "readyok"))
        assertThat(fixture.transport.state == NativeEngineTransportState.RUNNING)
    }
    suite.test("native framing failure crashes and closes transport once") {
        val fixture = nativeTransportFixture()
        fixture.port.stdout(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()))
        assertThat(fixture.transport.state == NativeEngineTransportState.CRASHED)
        assertThat(fixture.port.closeCount == 1)
        assertThat((fixture.listener.terminations.single() as NativeTransportTermination.Failed).cause is NativeEngineFramingException)
        fixture.transport.close()
        assertThat(fixture.port.closeCount == 1)
    }
    suite.test("native write failure clears queue and terminates transport") {
        val fixture = nativeTransportFixture()
        fixture.transport.send("uci")
        fixture.transport.send("isready")
        fixture.port.completeNext(Result.failure(IllegalStateException("broken pipe")))
        assertThat(fixture.transport.state == NativeEngineTransportState.CRASHED)
        assertThat(fixture.transport.pendingCommandCount == 0 && fixture.transport.pendingBytes == 0)
        assertThat((fixture.listener.terminations.single() as NativeTransportTermination.Failed).cause.message == "broken pipe")
    }
    suite.test("duplicate native write completion is a contract failure") {
        val fixture = nativeTransportFixture()
        fixture.transport.send("uci")
        val write = fixture.port.completeNext()
        write.completion(Result.success(Unit))
        assertThat(fixture.transport.state == NativeEngineTransportState.CRASHED)
        assertThat((fixture.listener.terminations.single() as NativeTransportTermination.Failed).cause is NativeEngineContractException)
    }
    suite.test("unexpected zero exit is still a transport crash") {
        val fixture = nativeTransportFixture()
        fixture.port.exit(NativeExitStatus(exitCode = 0, detail = "engine loop returned"))
        assertThat(fixture.transport.state == NativeEngineTransportState.CRASHED)
        val exited = fixture.listener.terminations.single() as NativeTransportTermination.Exited
        assertThat(exited.status.exitCode == 0)
        assertThrows<NativeEngineStateException> { fixture.transport.send("uci") }
    }
    suite.test("explicit close is idempotent and reports requested termination") {
        val fixture = nativeTransportFixture()
        fixture.transport.send("uci")
        fixture.transport.close()
        fixture.transport.close()
        assertThat(fixture.transport.state == NativeEngineTransportState.CLOSED)
        assertThat(fixture.port.closeCount == 1)
        assertThat(fixture.listener.terminations == listOf(NativeTransportTermination.Requested))
        assertThat(fixture.transport.pendingCommandCount == 0)
    }
    suite.test("reentrant exit during explicit close reports termination once") {
        val fixture = nativeTransportFixture()
        fixture.port.exitDuringClose = NativeExitStatus(exitCode = 0)
        fixture.transport.close()
        assertThat(fixture.transport.state == NativeEngineTransportState.CLOSED)
        assertThat(fixture.listener.terminations == listOf(NativeTransportTermination.Requested))
    }
    suite.test("native close failure is surfaced as a crash") {
        val fixture = nativeTransportFixture()
        fixture.port.closeFailure = IllegalStateException("release failed")
        fixture.transport.close()
        assertThat(fixture.transport.state == NativeEngineTransportState.CRASHED)
        assertThat((fixture.listener.terminations.single() as NativeTransportTermination.Failed).cause.message == "release failed")
    }
    suite.test("native open failure closes resources and notifies listener") {
        val port = FakeNativeEnginePort().apply { openFailure = IllegalStateException("load failed") }
        val listener = RecordingNativeListener()
        val transport = SerializedNativeUciTransport(port)
        assertThrows<IllegalStateException> { transport.start(listener) }
        assertThat(transport.state == NativeEngineTransportState.CRASHED)
        assertThat(port.closeCount == 1)
        assertThat((listener.terminations.single() as NativeTransportTermination.Failed).cause.message == "load failed")
    }
    suite.test("native manifest follows device ABI preference order") {
        val arm = nativeArtifact(AndroidNativeAbi.ARM64_V8A, "arm")
        val emulator = nativeArtifact(AndroidNativeAbi.X86_64, "emulator")
        val manifest = nativeManifest(listOf(arm, emulator))
        assertThat(manifest.selectArtifact(listOf("x86_64", "arm64-v8a"), 35) == emulator)
        assertThat(manifest.selectArtifact(listOf("unknown", "arm64-v8a"), 35) == arm)
    }
    suite.test("native artifact verifies both size and SHA-256") {
        val bytes = "drawless-fairy-build".toByteArray()
        val artifact = NativeEngineArtifact(
            AndroidNativeAbi.ARM64_V8A,
            "libdrawless_fairy.so",
            bytes.size.toLong(),
            sha256Of(bytes),
        )
        assertThat(artifact.verifies(bytes))
        assertThat(!artifact.verifies("drawless-fairy-builD".toByteArray()))
        assertThat(!artifact.copy(uncompressedSizeBytes = bytes.size + 1L).verifies(bytes))
    }
    suite.test("native manifest rejects unsupported device API and ABI") {
        val manifest = nativeManifest(listOf(nativeArtifact(AndroidNativeAbi.ARM64_V8A, "arm")))
        assertThrows<NativeEngineCompatibilityException> {
            manifest.selectArtifact(listOf("arm64-v8a"), 25)
        }
        assertThrows<NativeEngineCompatibilityException> {
            manifest.selectArtifact(listOf("x86_64"), 35)
        }
    }
    suite.test("native manifest rejects duplicate ABIs and unsafe library paths") {
        val first = nativeArtifact(AndroidNativeAbi.ARM64_V8A, "one")
        val second = nativeArtifact(AndroidNativeAbi.ARM64_V8A, "two")
        assertThrows<IllegalArgumentException> { nativeManifest(listOf(first, second)) }
        assertThrows<IllegalArgumentException> {
            NativeEngineArtifact(AndroidNativeAbi.ARM64_V8A, "../libbad.so", 1, "0".repeat(64))
        }
    }
    suite.test("native Fairy session composes byte transport and strict UCI protocol") {
        val port = FakeNativeEnginePort().apply { completeWritesSynchronously = true }
        val scheduler = RecordingNativeTimeoutScheduler()
        val session = NativeFairyEngineSession(
            port = port,
            timeoutScheduler = scheduler,
            build = FairyEngineBuild("native-test", 1),
        )
        assertThat(port.writes.isEmpty())
        port.started()
        assertThat(port.writes == listOf("uci\n"))
        port.stdout(nativeHandshake().toByteArray())
        assertThat(port.writes.last() == "isready\n")
        port.stdout("readyok\n".toByteArray())

        var response: EngineResponse? = null
        session.analyze(nativeRequest()) { response = it.getOrThrow() }
        assertThat(port.writes.any { it == "setoption name UCI_Variant value drawless\n" })
        assertThat(port.writes.last() == "isready\n")
        port.stdout("readyok\n".toByteArray())
        assertThat(port.writes.takeLast(2) == listOf("position startpos\n", "go movetime 100\n"))
        port.stdout("info depth 3 score cp 18 nodes 20 pv e2e4\nbestmove e2e4\n".toByteArray())
        assertThat(response?.bestMove == UciMove("e2e4"))
        assertThat(session.protocolState == com.drawlesschess.core.engine.UciSessionState.IDLE)
        session.close()
        assertThat(session.transportState == NativeEngineTransportState.CLOSED)
    }
    suite.test("native Fairy session converts an endpoint crash into analysis failure") {
        val port = FakeNativeEnginePort().apply { completeWritesSynchronously = true }
        val session = NativeFairyEngineSession(
            port = port,
            timeoutScheduler = RecordingNativeTimeoutScheduler(),
            build = FairyEngineBuild("native-test", 1),
            uciPolicy = UciSessionPolicy(requiredDrawlessPatchVersion = 1),
        )
        port.started()
        port.stdout(nativeHandshake().toByteArray())
        port.stdout("readyok\n".toByteArray())
        var failure: Throwable? = null
        session.analyze(nativeRequest()) { failure = it.exceptionOrNull() }
        port.exit(NativeExitStatus(signal = 9, detail = "test crash"))
        assertThat(failure is NativeEngineTerminatedException)
        assertThat(session.protocolState == com.drawlesschess.core.engine.UciSessionState.FAILED)
    }
}

private class RecordingNativeTimeoutScheduler : UciTimeoutScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation = EngineCancellation {}
}

private fun nativeHandshake() = """
    id name Fairy-Stockfish Drawless
    id author Fairy-Stockfish developers
    option name UCI_Variant type combo default chess var chess var drawless var escape
    option name MultiPV type spin default 1 min 1 max 500
    option name Skill Level type spin default 20 min -20 max 20
    option name UCI_LimitStrength type check default false
    option name UCI_Elo type spin default 1350 min 500 max 2850
    option name Drawless Patch Version type spin default 1 min 1 max 1
    uciok
""".trimIndent() + "\n"

private fun nativeRequest() = EngineRequest(
    requestId = "native-request",
    gameId = "native-game",
    positionId = "native-position",
    initialFen = com.drawlesschess.core.chess.ChessPosition.START_FEN,
    moves = emptyList(),
    rules = RulesContractV1.drawless(),
    strength = EngineStrength.ApproximateElo(1_500),
    limits = EngineLimits(100),
)

private fun nativeArtifact(abi: AndroidNativeAbi, seed: String): NativeEngineArtifact {
    val bytes = seed.toByteArray()
    return NativeEngineArtifact(abi, "libdrawless_fairy.so", bytes.size.toLong(), sha256Of(bytes))
}

private fun nativeManifest(artifacts: List<NativeEngineArtifact>) = NativeEngineManifest(
    engineId = "fairy-stockfish",
    buildId = "drawless-test-build",
    drawlessPatchVersion = 1,
    minimumAndroidApi = 26,
    artifacts = artifacts,
)
