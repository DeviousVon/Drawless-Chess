import java.io.File
import java.util.Properties
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.library")
}

abstract class GenerateFairyLegalAssetsTask : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    override fun getDestinationDir(): File = outputDirectory.get().asFile

    override fun setDestinationDir(destinationDir: File) {
        outputDirectory.fileValue(destinationDir)
    }
}

val repositoryRoot = rootProject.projectDir.parentFile
val nativeRoot = repositoryRoot.resolve("engine/native")
val nativeLockFile = nativeRoot.resolve("upstream.properties")
val projectLicense = repositoryRoot.resolve("LICENSE")
val projectNotice = repositoryRoot.resolve("NOTICE")
val thirdPartyNotices = repositoryRoot.resolve("THIRD_PARTY_NOTICES.md")
val nativeLock = Properties().apply {
    nativeLockFile.inputStream().use(::load)
}

fun nativePin(name: String): String =
    nativeLock.getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw GradleException("Missing '$name' in ${nativeLockFile.absolutePath}")

val fairySource = nativeRoot.resolve(nativePin("sourceDirectory"))
val patchSeries = nativeRoot.resolve(nativePin("patchSeries"))
val drawlessVariants = repositoryRoot.resolve("engine/variants.ini")
val legalAssetsDirectory = layout.buildDirectory.dir("generated/fairy-legal-assets")

android {
    namespace = "com.drawlesschess.engine"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = nativePin("androidNdkVersion")

    defaultConfig {
        minSdk = nativePin("androidMinSdk").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField(
            "String",
            "FAIRY_UPSTREAM_REVISION",
            "\"${nativePin("revision")}\"",
        )
        buildConfigField(
            "String",
            "FAIRY_PATCHED_TREE",
            "\"${nativePin("patchedTree")}\"",
        )
        buildConfigField(
            "int",
            "DRAWLESS_PATCH_VERSION",
            nativePin("drawlessPatchVersion"),
        )
        buildConfigField(
            "int",
            "NATIVE_BRIDGE_ABI_VERSION",
            nativePin("nativeBridgeAbiVersion"),
        )
        buildConfigField(
            "String",
            "VARIANT_CONFIG_SHA256",
            "\"${nativePin("variantConfigSha256")}\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DFAIRY_SOURCE_DIR=${fairySource.absolutePath}",
                    "-DDRAWLESS_NATIVE_ROOT=${nativeRoot.absolutePath}",
                    "-DDRAWLESS_UPSTREAM_REVISION=${nativePin("revision")}",
                    "-DDRAWLESS_UPSTREAM_TREE=${nativePin("tree")}",
                    "-DDRAWLESS_PATCHED_TREE=${nativePin("patchedTree")}",
                    "-DDRAWLESS_PATCH_SERIES_SHA256=${nativePin("patchSeriesSha256")}",
                    "-DDRAWLESS_PATCH_VERSION=${nativePin("drawlessPatchVersion")}",
                    "-DDRAWLESS_BRIDGE_ABI_VERSION=${nativePin("nativeBridgeAbiVersion")}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = nativePin("cmakeVersion")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

}

dependencies {
    api(project(":core"))

    // Stable AndroidX Test releases listed by Android Developers; checked 2026-07-10.
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

val verifyPinnedFairySource by tasks.registering {
    group = "verification"
    description = "Fails unless the pinned and patched Fairy-Stockfish source is present."
    inputs.file(nativeLockFile)
    inputs.file(nativeRoot.resolve("source-manifest.txt"))
    inputs.file(patchSeries)
    inputs.file(drawlessVariants)
    inputs.dir(fairySource)

    doLast {
        if (!fairySource.isDirectory) {
            throw GradleException(
                "Pinned Fairy-Stockfish checkout is absent. Run scripts/native-fetch-fairy.sh.",
            )
        }

        val stateFile = fairySource.resolve(".drawless-source-state.properties")
        if (!stateFile.isFile) {
            throw GradleException(
                "Source-state marker is absent. Recreate the checkout with scripts/native-fetch-fairy.sh.",
            )
        }

        val state = Properties().apply { stateFile.inputStream().use(::load) }
        val expected = mapOf(
            "upstreamRevision" to nativePin("revision"),
            "upstreamTree" to nativePin("tree"),
            "patchedTree" to nativePin("patchedTree"),
            "patchVersion" to nativePin("drawlessPatchVersion"),
            "patchesApplied" to "true",
            "patchSeriesSha256" to nativePin("patchSeriesSha256"),
        )
        expected.forEach { (key, value) ->
            if (state.getProperty(key) != value) {
                throw GradleException(
                    "Fairy source state '$key' does not match the native lock; rerun the fetch script.",
                )
            }
        }

        fun gitOutput(vararg arguments: String): String {
            val process = ProcessBuilder(
                listOf("git", "-C", fairySource.absolutePath) + arguments,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() != 0) {
                throw GradleException("Git source verification failed: $output")
            }
            return output
        }

        if (gitOutput("rev-parse", "HEAD") != nativePin("revision")) {
            throw GradleException("Fairy checkout revision does not match the native lock")
        }
        if (gitOutput("rev-parse", "HEAD^{tree}") != nativePin("tree")) {
            throw GradleException("Fairy upstream tree does not match the native lock")
        }
        if (gitOutput("write-tree") != nativePin("patchedTree")) {
            throw GradleException("Fairy patched tree does not match the native lock")
        }

        val unstagedCheck = ProcessBuilder(
            "git", "-C", fairySource.absolutePath, "diff", "--quiet",
        ).start()
        if (unstagedCheck.waitFor() != 0) {
            throw GradleException("Fairy checkout has unstaged source modifications")
        }

        val required = buildList {
            add(fairySource.resolve("Copying.txt"))
            add(fairySource.resolve("AUTHORS"))
            nativeRoot.resolve("source-manifest.txt").forEachLine { line ->
                line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    ?.let { add(fairySource.resolve("src/$it")) }
            }
        }
        val missing = required.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Pinned Fairy source is incomplete: ${missing.joinToString()}")
        }

        val variantHash = MessageDigest.getInstance("SHA-256")
            .digest(drawlessVariants.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (variantHash != nativePin("variantConfigSha256")) {
            throw GradleException("Drawless native variant configuration does not match the lock")
        }
    }
}

val generateFairyLegalAssets by tasks.registering(GenerateFairyLegalAssetsTask::class) {
    group = "build"
    description = "Packages project and Fairy-Stockfish licenses, notices, identity, and patches."
    dependsOn(verifyPinnedFairySource)
    outputDirectory.set(legalAssetsDirectory)

    from(projectLicense) {
        into("legal/drawless-chess")
    }
    from(projectNotice) {
        into("legal/drawless-chess")
    }
    from(thirdPartyNotices) {
        into("legal/drawless-chess")
    }

    from(fairySource.resolve("Copying.txt")) {
        into("third_party/fairy-stockfish")
    }
    from(fairySource.resolve("AUTHORS")) {
        into("third_party/fairy-stockfish")
    }
    from(nativeRoot.resolve("SOURCE_NOTICE.txt")) {
        into("third_party/fairy-stockfish")
    }
    from(nativeLockFile) {
        into("third_party/fairy-stockfish")
    }
    from(nativeRoot.resolve("wasm-poc.properties")) {
        into("third_party/fairy-stockfish")
    }
    from(repositoryRoot.resolve("engine/patches")) {
        include("series", "*.patch", "*.diff", "*.json", "checksums.sha256", "README.md")
        into("third_party/fairy-stockfish/patches")
    }
    from(drawlessVariants) {
        rename { "drawless-variants.ini" }
        into("engine")
    }
}

androidComponents {
    onVariants { variant ->
        val assets = variant.sources.assets
            ?: throw GradleException("Android assets source API is unavailable for ${variant.name}")
        assets.addGeneratedSourceDirectory(
            generateFairyLegalAssets,
            GenerateFairyLegalAssetsTask::outputDirectory,
        )
    }
}

tasks.matching {
    it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake")
}.configureEach {
    dependsOn(verifyPinnedFairySource)
}
