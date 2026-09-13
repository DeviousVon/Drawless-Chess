import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcFramework = XCFramework()
    val repositoryRoot = rootProject.projectDir.parentFile
    val appleEngineSlices = repositoryRoot.resolve("build/ios-engine/slices")
    val appleEngineHeader = repositoryRoot.resolve("ios-engine/include/drawless_fairy.h")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        val engineLibrary = when (target.name) {
            "iosX64" -> appleEngineSlices.resolve("libdrawless_fairy-simulator-x86_64.a")
            "iosArm64" -> appleEngineSlices.resolve("libdrawless_fairy-device-arm64.a")
            "iosSimulatorArm64" -> appleEngineSlices.resolve("libdrawless_fairy-simulator-arm64.a")
            else -> error("Unsupported Apple target ${target.name}")
        }
        target.compilations.getByName("main").cinterops.create("DrawlessFairy") {
            packageName("com.drawlesschess.fairy.c")
            header(appleEngineHeader)
            includeDirs(appleEngineHeader.parentFile)
            extraOpts(
                "-libraryPath", appleEngineSlices.absolutePath,
                "-staticLibrary", engineLibrary.name,
            )
        }
        target.binaries.framework {
            baseName = "DrawlessShared"
            isStatic = true
            linkerOpts("-lc++")
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
            kotlin.srcDir("../../shared/checkpoint-codec/src/main/kotlin")
            kotlin.srcDir("../../android/core/src/main/kotlin")
            // Share core sources by default. These are the complete, intentionally reviewed
            // Android/JVM adapter exceptions; keep them exact and never replace them with a
            // broad package exclusion. A new platform-specific source therefore enters every
            // KMP compilation and fails closed until it is either made portable or explicitly
            // reviewed and added here.
            kotlin.exclude(
                "com/drawlesschess/core/engine/BotMovePacingEngine.kt",
                "com/drawlesschess/core/engine/nativebridge/**",
                "com/drawlesschess/core/presentation/GameScreenController.kt",
            )
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
