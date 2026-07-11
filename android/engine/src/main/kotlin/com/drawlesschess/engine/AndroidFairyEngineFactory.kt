package com.drawlesschess.engine

import android.content.Context
import com.drawlesschess.core.ChessEngine
import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.engine.FairyEngineBuild
import com.drawlesschess.core.engine.UciSessionPolicy
import com.drawlesschess.core.engine.UciSessionState
import com.drawlesschess.core.engine.nativebridge.NativeEngineTransportPolicy
import com.drawlesschess.core.engine.nativebridge.NativeEngineTransportState
import com.drawlesschess.core.engine.nativebridge.NativeFairyEngineSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates independently owned, offline Fairy-Stockfish sessions.
 *
 * Fairy uses process-global engine state, so only one returned session may be live at a time.
 * Native creation rejects overlap; close the prior session before creating its replacement.
 */
class AndroidFairyEngineFactory(
    context: Context,
    private val diagnosticSink: (String) -> Unit = {},
    private val uciPolicy: UciSessionPolicy = UciSessionPolicy(),
    private val transportPolicy: NativeEngineTransportPolicy = NativeEngineTransportPolicy(),
    private val portPolicy: JniFairyEnginePortPolicy = JniFairyEnginePortPolicy(),
) {
    private val applicationContext = context.applicationContext

    fun create(): AndroidFairyEngineSession {
        val variantFile = VariantConfigInstaller(
            applicationContext,
            BuildConfig.VARIANT_CONFIG_SHA256,
        ).install()
        val scheduler = AndroidUciTimeoutScheduler()
        return try {
            val delegate = NativeFairyEngineSession(
                port = JniFairyEnginePort(variantFile.absolutePath, portPolicy),
                timeoutScheduler = scheduler,
                build = FairyEngineBuild(
                    buildId = buildId(),
                    drawlessPatchVersion = BuildConfig.DRAWLESS_PATCH_VERSION,
                ),
                uciPolicy = uciPolicy,
                transportPolicy = transportPolicy,
                diagnosticSink = diagnosticSink,
            )
            AndroidFairyEngineSession(delegate, scheduler)
        } catch (error: Throwable) {
            scheduler.close()
            throw error
        }
    }

    private fun buildId(): String = buildString {
        append("fairy-")
        append(BuildConfig.FAIRY_UPSTREAM_REVISION.take(12))
        append("-tree-")
        append(BuildConfig.FAIRY_PATCHED_TREE.take(12))
        append("-patch-")
        append(BuildConfig.DRAWLESS_PATCH_VERSION)
        append("-bridge-")
        append(BuildConfig.NATIVE_BRIDGE_ABI_VERSION)
    }
}

/** A session owns its protocol adapter, JNI worker, reader threads, and timeout scheduler. */
class AndroidFairyEngineSession internal constructor(
    private val delegate: NativeFairyEngineSession,
    private val scheduler: AndroidUciTimeoutScheduler,
) : ChessEngine, AutoCloseable {
    private val closed = AtomicBoolean(false)

    val protocolState: UciSessionState get() = delegate.protocolState
    val transportState: NativeEngineTransportState get() = delegate.transportState

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = delegate.analyze(request, onResult)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            delegate.close()
        } finally {
            scheduler.close()
        }
    }
}
