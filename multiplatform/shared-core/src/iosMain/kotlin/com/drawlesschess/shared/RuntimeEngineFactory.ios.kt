@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.drawlesschess.shared

import com.drawlesschess.core.EngineCancellation
import com.drawlesschess.core.EngineRequest
import com.drawlesschess.core.EngineResponse
import com.drawlesschess.core.engine.FairyEngineBuild
import com.drawlesschess.core.engine.FairyUciEngine
import com.drawlesschess.core.engine.UciSessionPolicy
import com.drawlesschess.core.engine.UciTimeoutScheduler
import com.drawlesschess.core.engine.UciTransport
import com.drawlesschess.fairy.c.drawless_fairy_close
import com.drawlesschess.fairy.c.drawless_fairy_create
import com.drawlesschess.fairy.c.drawless_fairy_patch_version
import com.drawlesschess.fairy.c.drawless_fairy_patched_tree
import com.drawlesschess.fairy.c.drawless_fairy_read
import com.drawlesschess.fairy.c.drawless_fairy_read_error
import com.drawlesschess.fairy.c.drawless_fairy_start
import com.drawlesschess.fairy.c.drawless_fairy_timeout_cancel
import com.drawlesschess.fairy.c.drawless_fairy_timeout_drain
import com.drawlesschess.fairy.c.drawless_fairy_timeout_schedule
import com.drawlesschess.fairy.c.drawless_fairy_write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.Platform
import platform.Foundation.NSBundle
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_group_async_f
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_wait
import platform.posix.getenv
import platform.posix.usleep

internal actual fun createRuntimeEngine(): RuntimeChessEngine = AppleFifoRuntimeEngine(
    delegate = AppleFairyEngine(),
    scheduler = DarwinTimeoutScheduler,
)

private val appleReviewPreparationQueue = AppleSerialQueue(
    "com.drawlesschess.review-preparation",
)

private data class AppleReviewPreparationActivity(
    val queued: Int = 0,
    val holding: Int = 0,
    val preparing: Int = 0,
) {
    init {
        check(queued >= 0 && holding >= 0 && preparing >= 0) {
            "Review-preparation activity counts cannot be negative"
        }
    }
}

private val appleReviewPreparationActivity = AtomicReference(AppleReviewPreparationActivity())

private inline fun updateAppleReviewPreparationActivity(
    transform: (AppleReviewPreparationActivity) -> AppleReviewPreparationActivity,
) {
    while (true) {
        val current = appleReviewPreparationActivity.load()
        val updated = transform(current)
        if (appleReviewPreparationActivity.compareAndSet(current, updated)) return
    }
}

internal actual fun scheduleRuntimeReviewPreparation(action: () -> Unit) {
    updateAppleReviewPreparationActivity { current ->
        current.copy(queued = current.queued + 1)
    }
    appleReviewPreparationQueue.async {
        val holdMillis = if (Platform.isDebugBinary) {
            getenv("DRAWLESS_XCTEST_REVIEW_PREPARATION_HOLD_MILLIS")
                ?.toKString()
                ?.toLongOrNull()
                ?.coerceIn(0L, 10_000L)
                ?: 0L
        } else {
            0L
        }
        if (holdMillis > 0L) {
            updateAppleReviewPreparationActivity { current ->
                check(current.queued > 0) { "Review-preparation queue accounting underflow" }
                current.copy(queued = current.queued - 1, holding = current.holding + 1)
            }
            var remaining = holdMillis
            while (remaining > 0L) {
                val slice = remaining.coerceAtMost(10L)
                usleep((slice * 1_000L).toUInt())
                remaining -= slice
            }
            updateAppleReviewPreparationActivity { current ->
                check(current.holding > 0) { "Review-preparation hold accounting underflow" }
                current.copy(holding = current.holding - 1, preparing = current.preparing + 1)
            }
        } else {
            updateAppleReviewPreparationActivity { current ->
                check(current.queued > 0) { "Review-preparation queue accounting underflow" }
                current.copy(queued = current.queued - 1, preparing = current.preparing + 1)
            }
        }
        try {
            action()
        } finally {
            updateAppleReviewPreparationActivity { current ->
                check(current.preparing > 0) { "Review-preparation work accounting underflow" }
                current.copy(preparing = current.preparing - 1)
            }
        }
    }
}

internal actual fun runtimeReviewPreparationActivityForTesting(): String {
    val activity = appleReviewPreparationActivity.load()
    return when {
        activity.holding > 0 -> "HOLDING"
        activity.queued > 0 -> "QUEUED"
        activity.preparing > 0 -> "PREPARING"
        else -> "IDLE"
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class AppleFairyEngine : RuntimeChessEngine {
    override val reviewEvidenceBuildId: String =
        drawless_fairy_patched_tree()?.toKString() ?: "unknown-apple-build"
    override val reviewEvidencePatchVersion: Int = drawless_fairy_patch_version()
    private val closed = AtomicBoolean(false)
    private val readerGroup = dispatch_group_create()
    private val handle: ULong
    private val controller: FairyUciEngine

    init {
        val configPath = NSBundle.mainBundle.pathForResource("variants", ofType = "ini")
            ?: if (Platform.isDebugBinary) {
                getenv("DRAWLESS_VARIANTS_PATH")?.toKString()
            } else {
                null
            }
            ?: error("The pinned Drawless variant configuration is missing")
        handle = memScoped {
            val error = allocArray<ByteVar>(ERROR_CAPACITY)
            val created = drawless_fairy_create(configPath, error, ERROR_CAPACITY.convert())
            check(created != 0uL) { error.toKString().ifBlank { "Native engine creation failed" } }
            if (drawless_fairy_start(created, error, ERROR_CAPACITY.convert()) != 1) {
                val message = error.toKString().ifBlank { "Native engine startup failed" }
                drawless_fairy_close(created, error, ERROR_CAPACITY.convert())
                error(message)
            }
            created
        }
        controller = FairyUciEngine(
            transport = UciTransport(::send),
            timeoutScheduler = DarwinTimeoutScheduler,
            build = FairyEngineBuild(
                buildId = reviewEvidenceBuildId,
                drawlessPatchVersion = reviewEvidencePatchVersion,
            ),
            policy = appleSessionPolicy(),
            closeTransport = ::closeNative,
        )
        startReader(standardError = false)
        startReader(standardError = true)
    }

    override fun analyze(
        request: EngineRequest,
        onResult: (Result<EngineResponse>) -> Unit,
    ): EngineCancellation = controller.analyze(request, onResult)

    override fun close() {
        controller.close()
        DarwinTimeoutScheduler.drain()
        dispatch_group_wait(readerGroup, DISPATCH_TIME_FOREVER)
    }

    private fun send(command: String) {
        check(!closed.load()) { "Native engine session is closed" }
        val bytes = "$command\n".encodeToByteArray()
        memScoped {
            val error = allocArray<ByteVar>(ERROR_CAPACITY)
            val written = bytes.usePinned { pinned ->
                drawless_fairy_write(
                    handle,
                    pinned.addressOf(0).reinterpret(),
                    bytes.size.convert(),
                    error,
                    ERROR_CAPACITY.convert(),
                )
            }
            check(written == bytes.size.toLong()) {
                error.toKString().ifBlank { "Native engine input failed" }
            }
        }
    }

    private fun startReader(standardError: Boolean) {
        val context = NativeReader(handle, standardError) { line ->
            if (!standardError) controller.onLine(line)
        }
        context.failureHandler = controller::onTransportFailure
        val reference = StableRef.create(context)
        dispatch_group_async_f(
            readerGroup,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u),
            reference.asCPointer(),
            staticCFunction(::runNativeReader),
        )
    }

    private fun closeNative() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        memScoped {
            val error = allocArray<ByteVar>(ERROR_CAPACITY)
            check(drawless_fairy_close(handle, error, ERROR_CAPACITY.convert()) == 1) {
                error.toKString().ifBlank { "Native engine close failed" }
            }
        }
    }
}

private fun appleSessionPolicy(): UciSessionPolicy {
    if (!Platform.isDebugBinary) return UciSessionPolicy()

    val testGraceMillis = getenv("DRAWLESS_ENGINE_SEARCH_GRACE_MILLIS")
        ?.toKString()
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }
    return if (testGraceMillis == null) {
        UciSessionPolicy()
    } else {
        UciSessionPolicy(searchGraceMillis = testGraceMillis)
    }
}

private class NativeReader(
    private val handle: ULong,
    private val standardError: Boolean,
    private val lineHandler: (String) -> Unit,
) {
    lateinit var failureHandler: (Throwable) -> Unit

    fun run() {
        val pending = StringBuilder()
        val bytes = ByteArray(8_192)
        while (true) {
            val count = memScoped {
                val error = allocArray<ByteVar>(ERROR_CAPACITY)
                val read = bytes.usePinned { pinned ->
                    if (standardError) {
                        drawless_fairy_read_error(
                            handle,
                            pinned.addressOf(0).reinterpret(),
                            bytes.size.convert(),
                            error,
                            ERROR_CAPACITY.convert(),
                        )
                    } else {
                        drawless_fairy_read(
                            handle,
                            pinned.addressOf(0).reinterpret(),
                            bytes.size.convert(),
                            error,
                            ERROR_CAPACITY.convert(),
                        )
                    }
                }
                if (read == -2L) throw IllegalStateException(
                    error.toKString().ifBlank { "Native engine output failed" },
                )
                read
            }
            if (count < 0) break
            if (count == 0L) continue
            pending.append(bytes.decodeToString(endIndex = count.toInt()))
            while (true) {
                val boundary = pending.indexOf("\n")
                if (boundary < 0) break
                val line = pending.substring(0, boundary).trimEnd('\r')
                pending.deleteRange(0, boundary + 1)
                if (line.isNotEmpty()) lineHandler(line)
            }
        }
        if (pending.isNotEmpty()) lineHandler(pending.toString())
    }
}

private fun runNativeReader(context: COpaquePointer?) {
    val reference = requireNotNull(context).asStableRef<NativeReader>()
    try {
        reference.get().run()
    } catch (error: Throwable) {
        reference.get().failureHandler(error)
    } finally {
        reference.dispose()
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal object DarwinTimeoutScheduler : UciTimeoutScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): EngineCancellation {
        val ticket = TimeoutTicket(action)
        val reference = StableRef.create(ticket)
        val handle = drawless_fairy_timeout_schedule(
            delayMillis.convert(),
            staticCFunction(::runTimeout),
            reference.asCPointer(),
        )
        check(handle != 0uL) { "Native timeout scheduling failed" }
        ticket.handle = handle
        return EngineCancellation { ticket.cancel() }
    }

    fun drain() = drawless_fairy_timeout_drain()
}

@OptIn(ExperimentalAtomicApi::class)
private class TimeoutTicket(val action: () -> Unit) {
    val cancelled = AtomicBoolean(false)
    var handle: ULong = 0uL

    fun cancel() {
        if (!cancelled.compareAndSet(expectedValue = false, newValue = true)) return
        drawless_fairy_timeout_cancel(handle)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun runTimeout(context: COpaquePointer?, fired: Int) {
    val reference = requireNotNull(context).asStableRef<TimeoutTicket>()
    try {
        val ticket = reference.get()
        if (fired != 0 && !ticket.cancelled.load()) ticket.action()
    } finally {
        reference.dispose()
    }
}

private const val ERROR_CAPACITY = 512
