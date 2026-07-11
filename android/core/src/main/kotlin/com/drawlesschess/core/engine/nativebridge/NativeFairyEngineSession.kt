package com.drawlesschess.core.engine.nativebridge

import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.engine.FairyEngineBuild
import com.drawlesschess.core.engine.FairyUciEngine
import com.drawlesschess.core.engine.UciSessionPolicy
import com.drawlesschess.core.engine.UciSessionState
import com.drawlesschess.core.engine.UciTimeoutScheduler

/**
 * Composes the byte-level native endpoint with the protocol-level Fairy engine.
 *
 * This remains Android-framework neutral: the app module may supply a JNI-backed or
 * controlled-process [NativeEnginePort] without changing game coordination or UCI logic.
 */
class NativeFairyEngineSession(
    port: NativeEnginePort,
    timeoutScheduler: UciTimeoutScheduler,
    build: FairyEngineBuild,
    uciPolicy: UciSessionPolicy = UciSessionPolicy(),
    transportPolicy: NativeEngineTransportPolicy = NativeEngineTransportPolicy(),
    private val diagnosticSink: (String) -> Unit = {},
) : ChessEngine, AutoCloseable {
    private val transport = SerializedNativeUciTransport(port, transportPolicy)
    private val protocol = FairyUciEngine(
        transport = transport,
        timeoutScheduler = timeoutScheduler,
        build = build,
        policy = uciPolicy,
        closeTransport = transport::close,
    )

    val protocolState: UciSessionState get() = protocol.state
    val transportState: NativeEngineTransportState get() = transport.state

    init {
        transport.start(object : NativeUciTransportListener {
            override fun onLine(line: String) = protocol.onLine(line)

            override fun onDiagnostic(line: String) = diagnosticSink(line)

            override fun onTerminated(termination: NativeTransportTermination) {
                if (termination != NativeTransportTermination.Requested) {
                    val error = termination.asException()
                    val detail = error.message?.takeIf(String::isNotBlank)
                        ?: error::class.simpleName
                        ?: "unknown failure"
                    diagnosticSink("Native engine transport failure: $detail")
                    protocol.onTransportFailure(error)
                }
            }
        })
        // Starting immediately makes binary/patch incompatibility visible before the first move.
        // The serialized transport safely queues this while an asynchronous port is opening.
        protocol.start()
    }

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = protocol.analyze(request, onResult)

    override fun close() = protocol.close()
}
