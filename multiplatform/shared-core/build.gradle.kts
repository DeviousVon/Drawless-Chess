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
            kotlin.srcDir("../../android/core/src/main/kotlin")
            kotlin.include(
                "com/drawlesschess/core/Model.kt",
                "com/drawlesschess/core/ConcurrentLock.kt",
                "com/drawlesschess/core/PositionHistory.kt",
                "com/drawlesschess/core/Rules.kt",
                "com/drawlesschess/core/GameSession.kt",
                "com/drawlesschess/core/EngineApi.kt",
                "com/drawlesschess/core/GameScoring.kt",
                "com/drawlesschess/core/SavedGame.kt",
                "com/drawlesschess/core/chess/**",
                "com/drawlesschess/core/coordinator/**",
                "com/drawlesschess/core/engine/AnalysisRequests.kt",
                "com/drawlesschess/core/engine/DifficultyAndRating.kt",
                "com/drawlesschess/core/engine/DrawlessRulesUci.kt",
                "com/drawlesschess/core/engine/FairyUciEngine.kt",
                "com/drawlesschess/core/engine/GameReview.kt",
                "com/drawlesschess/core/engine/UciProtocol.kt",
                "com/drawlesschess/core/presentation/BoardPresentation.kt",
                "com/drawlesschess/core/presentation/GameHistoryPresentation.kt",
                "com/drawlesschess/core/presentation/ResponsiveLayout.kt",
                "com/drawlesschess/core/presentation/Themes.kt",
                "com/drawlesschess/core/presentation/ThreatIndicators.kt",
                "com/drawlesschess/shared/**",
            )
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
