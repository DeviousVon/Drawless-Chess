package com.drawlesschess.engine

/**
 * The deliberately small JNI ABI for the embedded Fairy-Stockfish worker.
 *
 * All potentially blocking calls are invoked by [JniFairyEnginePort] on managed worker threads.
 * Native code never calls back into Kotlin. The names and descriptors here are registered from
 * `JNI_OnLoad`; do not rename or overload them without incrementing the native bridge ABI.
 */
object FairyNativeBindings {
    init {
        System.loadLibrary("drawless_fairy")
    }

    /** Creates a worker configured from the already verified variant file. Zero is invalid. */
    @JvmStatic
    external fun nativeCreate(variantConfigPath: String): Long

    /** Starts the engine thread. Native failures are surfaced as Java exceptions. */
    @JvmStatic
    external fun nativeStart(handle: Long)

    /** Blocking stdin write. Returns bytes consumed, or throws on failure. */
    @JvmStatic
    external fun nativeWrite(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int

    /** Blocking stdout read. Returns bytes read, or -1 at end of stream. */
    @JvmStatic
    external fun nativeRead(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int

    /** Blocking stderr read. Returns bytes read, or -1 at end of stream. */
    @JvmStatic
    external fun nativeReadError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int

    /** Idempotently stops the worker and unblocks all active reads and writes. */
    @JvmStatic
    external fun nativeClose(handle: Long)
}

/** Injectable only inside this module so lifecycle tests never load the shared library. */
internal interface FairyNativeApi {
    fun create(variantConfigPath: String): Long
    fun start(handle: Long)
    fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun readError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun close(handle: Long)
}

internal object JniFairyNativeApi : FairyNativeApi {
    override fun create(variantConfigPath: String): Long =
        FairyNativeBindings.nativeCreate(variantConfigPath)

    override fun start(handle: Long) = FairyNativeBindings.nativeStart(handle)

    override fun write(
        handle: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = FairyNativeBindings.nativeWrite(handle, bytes, offset, length)

    override fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        FairyNativeBindings.nativeRead(handle, bytes, offset, length)

    override fun readError(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        FairyNativeBindings.nativeReadError(handle, bytes, offset, length)

    override fun close(handle: Long) = FairyNativeBindings.nativeClose(handle)
}
