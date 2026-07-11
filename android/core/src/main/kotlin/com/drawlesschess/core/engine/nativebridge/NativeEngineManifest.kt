package com.drawlesschess.core.engine.nativebridge

import java.security.MessageDigest

enum class AndroidNativeAbi(val androidName: String) {
    ARM64_V8A("arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a"),
    X86_64("x86_64"),
    X86("x86");

    companion object {
        fun fromAndroidName(value: String): AndroidNativeAbi? =
            entries.firstOrNull { it.androidName == value.trim().lowercase() }
    }
}

data class NativeEngineArtifact(
    val abi: AndroidNativeAbi,
    val libraryFileName: String,
    val uncompressedSizeBytes: Long,
    val sha256: String,
) {
    init {
        require(libraryFileName.matches(Regex("lib[A-Za-z0-9_.+-]+\\.so"))) {
            "Native library must be a safe .so basename"
        }
        require('/' !in libraryFileName && '\\' !in libraryFileName)
        require(uncompressedSizeBytes > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }

    fun verifies(bytes: ByteArray): Boolean =
        bytes.size.toLong() == uncompressedSizeBytes && sha256Of(bytes) == sha256
}

data class NativeEngineManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val engineId: String,
    val buildId: String,
    val drawlessPatchVersion: Int,
    val minimumAndroidApi: Int,
    val artifacts: List<NativeEngineArtifact>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported native engine manifest schema $schemaVersion"
        }
        require(engineId.isNotBlank())
        require(buildId.isNotBlank())
        require(drawlessPatchVersion >= 0)
        require(minimumAndroidApi >= 21)
        require(artifacts.isNotEmpty())
        require(artifacts.map { it.abi }.distinct().size == artifacts.size) {
            "A manifest may contain only one artifact per ABI"
        }
    }

    /** Selects the first packaged artifact in Android's device-preference order. */
    fun selectArtifact(deviceAbis: List<String>, deviceAndroidApi: Int): NativeEngineArtifact {
        require(deviceAbis.isNotEmpty()) { "Device ABI preference list must not be empty" }
        if (deviceAndroidApi < minimumAndroidApi) {
            throw NativeEngineCompatibilityException(
                "Engine requires Android API $minimumAndroidApi but the device is API $deviceAndroidApi",
            )
        }
        val byAbi = artifacts.associateBy { it.abi }
        for (deviceAbi in deviceAbis) {
            val parsed = AndroidNativeAbi.fromAndroidName(deviceAbi) ?: continue
            byAbi[parsed]?.let { return it }
        }
        throw NativeEngineCompatibilityException(
            "No packaged engine supports device ABIs ${deviceAbis.joinToString()} " +
                "(packaged: ${artifacts.joinToString { it.abi.androidName }})",
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class NativeEngineCompatibilityException(message: String) : IllegalStateException(message)

fun sha256Of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
