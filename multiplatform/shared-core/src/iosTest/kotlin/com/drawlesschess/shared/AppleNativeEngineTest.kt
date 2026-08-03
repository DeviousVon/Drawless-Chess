@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.drawlesschess.shared

import com.drawlesschess.fairy.c.drawless_fairy_bridge_abi_version
import com.drawlesschess.fairy.c.drawless_fairy_close
import com.drawlesschess.fairy.c.drawless_fairy_create
import com.drawlesschess.fairy.c.drawless_fairy_patch_version
import com.drawlesschess.fairy.c.drawless_fairy_read
import com.drawlesschess.fairy.c.drawless_fairy_start
import com.drawlesschess.fairy.c.drawless_fairy_upstream_revision
import com.drawlesschess.fairy.c.drawless_fairy_write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppleNativeEngineTest {
    @Test
    fun pinnedAppleBridgeRunsTheDrawlessUciVariant() = memScoped {
        assertEquals(1, drawless_fairy_bridge_abi_version())
        assertEquals(2, drawless_fairy_patch_version())
        assertEquals(
            "fb78cb561aa01708338e35b3dc3b65a42149a3c4",
            drawless_fairy_upstream_revision()?.toKString(),
        )

        val currentDirectory = NSFileManager.defaultManager.currentDirectoryPath
        val configPath = listOf(
            getenv("DRAWLESS_VARIANTS_PATH")?.toKString(),
            "$currentDirectory/engine/variants.ini",
            "$currentDirectory/../engine/variants.ini",
            "$currentDirectory/../../engine/variants.ini",
        ).filterNotNull().firstOrNull(NSFileManager.defaultManager::fileExistsAtPath)
        requireNotNull(configPath) {
            "The pinned Drawless variant configuration is absent near $currentDirectory"
        }
        val error = allocArray<ByteVar>(512)
        val handle = drawless_fairy_create(configPath, error, 512.convert())
        assertNotEquals(0uL, handle, error.toKString())
        assertEquals(1, drawless_fairy_start(handle, error, 512.convert()), error.toKString())

        try {
            write(handle, "uci\n", error)
            val handshake = readUntil(handle, "uciok", error)
            assertTrue(handshake.contains("option name UCI_Variant"))
            assertTrue(handshake.contains("var drawless"))
            assertTrue(handshake.contains("option name Drawless Patch Version"))

            write(handle, "setoption name UCI_Variant value drawless\n", error)
            write(handle, "setoption name UCI_LimitStrength value true\n", error)
            write(handle, "setoption name UCI_Elo value 650\n", error)
            write(handle, "isready\n", error)
            readUntil(handle, "readyok", error)
            write(handle, "ucinewgame\n", error)
            write(handle, "position startpos\n", error)
            write(handle, "go movetime 40\n", error)
            val search = readUntil(handle, "bestmove ", error)
            assertTrue(Regex("bestmove [a-h][1-8][a-h][1-8][qrbn]?").containsMatchIn(search))
        } finally {
            assertEquals(1, drawless_fairy_close(handle, error, 512.convert()), error.toKString())
        }
    }

    private fun write(handle: ULong, command: String, error: kotlinx.cinterop.CPointer<ByteVar>) {
        val bytes = command.encodeToByteArray()
        val count = bytes.usePinned { pinned ->
            drawless_fairy_write(
                handle,
                pinned.addressOf(0).reinterpret(),
                bytes.size.convert(),
                error,
                512.convert(),
            )
        }
        assertEquals(bytes.size.toLong(), count, error.toKString())
    }

    private fun readUntil(
        handle: ULong,
        marker: String,
        error: kotlinx.cinterop.CPointer<ByteVar>,
    ): String {
        val result = StringBuilder()
        val buffer = ByteArray(8_192)
        repeat(256) {
            val count = buffer.usePinned { pinned ->
                drawless_fairy_read(
                    handle,
                    pinned.addressOf(0).reinterpret(),
                    buffer.size.convert(),
                    error,
                    512.convert(),
                )
            }
            assertTrue(count > 0, "Native engine output ended before '$marker': ${error.toKString()}")
            result.append(buffer.decodeToString(endIndex = count.toInt()))
            if (marker in result) return result.toString()
        }
        error("Native engine did not emit '$marker'")
    }
}
