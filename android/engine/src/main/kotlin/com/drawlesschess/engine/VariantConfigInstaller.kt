package com.drawlesschess.engine

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class VariantConfigInstallationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Installs the packaged, build-locked variant file where native code can open it by path. */
internal class VariantConfigInstaller(
    private val context: Context,
    private val expectedSha256: String,
) {
    fun install(): File = synchronized(installationLock) {
        require(expectedSha256.matches(SHA256_PATTERN)) { "Invalid locked variant SHA-256" }
        val root = File(context.noBackupFilesDir, INSTALL_DIRECTORY).canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw VariantConfigInstallationException("Could not create private engine directory")
        }
        if (!root.isDirectory) {
            throw VariantConfigInstallationException("Private engine path is not a directory")
        }

        val versionDirectory = File(root, expectedSha256).canonicalFile
        ensureChild(root, versionDirectory)
        if (!versionDirectory.exists() && !versionDirectory.mkdirs()) {
            throw VariantConfigInstallationException("Could not create versioned engine directory")
        }

        val target = File(versionDirectory, TARGET_NAME).canonicalFile
        ensureChild(versionDirectory, target)
        if (target.isFile && sha256(target.inputStream()) == expectedSha256) return target

        val temporary = File.createTempFile("$TARGET_NAME.", ".tmp", versionDirectory)
        try {
            context.assets.open(ASSET_PATH, AssetManager.ACCESS_STREAMING).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_VARIANT_BYTES) {
                            throw VariantConfigInstallationException(
                                "Packaged variant configuration exceeds $MAX_VARIANT_BYTES bytes",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            val actualHash = sha256(temporary.inputStream())
            if (actualHash != expectedSha256) {
                throw VariantConfigInstallationException(
                    "Packaged variant configuration hash mismatch: expected $expectedSha256, got $actualHash",
                )
            }
            if (target.exists() && !target.delete()) {
                throw VariantConfigInstallationException("Could not replace stale variant configuration")
            }
            if (!temporary.renameTo(target)) {
                throw VariantConfigInstallationException("Could not atomically install variant configuration")
            }
            if (!target.setReadable(true, true) || !target.setWritable(false, false)) {
                throw VariantConfigInstallationException("Could not protect installed variant configuration")
            }
            target
        } catch (error: VariantConfigInstallationException) {
            throw error
        } catch (error: Throwable) {
            throw VariantConfigInstallationException("Could not install variant configuration", error)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun ensureChild(parent: File, child: File) {
        val prefix = parent.path + File.separator
        if (!child.path.startsWith(prefix)) {
            throw VariantConfigInstallationException("Engine path escaped private storage")
        }
    }

    private fun sha256(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val ASSET_PATH = "engine/drawless-variants.ini"
        const val INSTALL_DIRECTORY = "drawless-engine/variants"
        const val TARGET_NAME = "drawless-variants.ini"
        const val COPY_BUFFER_BYTES = 8 * 1024
        const val MAX_VARIANT_BYTES = 1024L * 1024L
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val installationLock = Any()
    }
}
