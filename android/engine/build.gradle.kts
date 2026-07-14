import java.io.File
import java.util.Properties
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

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

abstract class GenerateReleaseIdentityTask : DefaultTask() {
    @get:Input
    abstract val sourceCommit: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(sourceCommit.get() + "\n", Charsets.UTF_8)
    }
}

val repositoryRoot = rootProject.projectDir.parentFile
val nativeRoot = repositoryRoot.resolve("engine/native")
val nativeLockFile = nativeRoot.resolve("upstream.properties")
val projectLicense = repositoryRoot.resolve("LICENSE")
val projectNotice = repositoryRoot.resolve("NOTICE")
val thirdPartyNotices = repositoryRoot.resolve("THIRD_PARTY_NOTICES.md")
val apacheLicense = repositoryRoot.resolve("APACHE-2.0.txt")
val releaseSbom = repositoryRoot.resolve("release/reports/release-sbom.cdx.json")
val nativeLock = Properties().apply {
    nativeLockFile.inputStream().use(::load)
}

fun nativePin(name: String): String =
    nativeLock.getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw GradleException("Missing '$name' in ${nativeLockFile.absolutePath}")

fun resolveSourceCommit(): String {
    val bundledCommit = repositoryRoot.resolve("SOURCE-COMMIT")
    val commit = if (bundledCommit.isFile) {
        bundledCommit.readText(Charsets.UTF_8).trim()
    } else {
        try {
            val process = ProcessBuilder(
                "git",
                "-C",
                repositoryRoot.absolutePath,
                "rev-parse",
                "--verify",
                "HEAD",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (process.waitFor() != 0) {
                throw GradleException("Could not resolve release source commit: $output")
            }
            output
        } catch (exception: GradleException) {
            throw exception
        } catch (exception: Exception) {
            throw GradleException("Could not run Git to resolve the release source commit", exception)
        }
    }
    if (!commit.matches(Regex("[0-9a-f]{40}"))) {
        throw GradleException("Release source commit is not a full lowercase Git object ID")
    }
    return commit
}

val fairySource = nativeRoot.resolve(nativePin("sourceDirectory"))
val patchSeries = nativeRoot.resolve(nativePin("patchSeries"))
val drawlessVariants = repositoryRoot.resolve("engine/variants.ini")
val archiveSourceManifest = nativeRoot.resolve("archive-fairy-source.sha256")
val legalAssetsDirectory = layout.buildDirectory.dir("generated/fairy-legal-assets")
val sourceCommitIdentity = resolveSourceCommit()
val generateReleaseIdentity by tasks.registering(GenerateReleaseIdentityTask::class) {
    sourceCommit.set(sourceCommitIdentity)
    outputFile.set(layout.buildDirectory.file("generated/release-identity/SOURCE-COMMIT"))
}

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
    if (archiveSourceManifest.isFile) {
        inputs.file(archiveSourceManifest)
    }

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

        if (fairySource.resolve(".git").isDirectory) {
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
        } else if (archiveSourceManifest.isFile) {
            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
            }

            val sourceRoot = fairySource.canonicalFile.toPath()
            val manifestPattern = Regex("""^([0-9a-f]{64})\s+\*?\./(.+)$""")
            val manifestPaths = linkedSetOf<String>()
            archiveSourceManifest.forEachLine { line ->
                val match = manifestPattern.matchEntire(line.trimEnd('\r'))
                    ?: throw GradleException("Invalid native archive source manifest row")
                val expectedHash = match.groupValues[1]
                val relativePath = match.groupValues[2]
                val pathParts = relativePath.split('/')
                if (relativePath.startsWith('/') || relativePath.contains('\\') ||
                    relativePath.contains(':') || pathParts.any { it == "." || it == ".." } ||
                    !manifestPaths.add(relativePath)
                ) {
                    throw GradleException("Unsafe or duplicate native archive source path")
                }
                val sourceFile = fairySource.resolve(relativePath).canonicalFile
                if (!sourceFile.toPath().startsWith(sourceRoot) || !sourceFile.isFile ||
                    sha256(sourceFile) != expectedHash
                ) {
                    throw GradleException(
                        "Native archive source differs from its manifest: $relativePath",
                    )
                }
            }
            val actualPaths = fairySource.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(fairySource).invariantSeparatorsPath }
                .toSet()
            if (manifestPaths.isEmpty() || actualPaths != manifestPaths) {
                throw GradleException("Native archive source file set differs from its manifest")
            }
        } else {
            throw GradleException(
                "Fairy source has neither pinned Git metadata nor an archive source manifest",
            )
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
    dependsOn(generateReleaseIdentity)
    outputDirectory.set(legalAssetsDirectory)

    doFirst {
        val missing = listOf(
            projectLicense,
            projectNotice,
            thirdPartyNotices,
            apacheLicense,
            releaseSbom,
        ).filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Required release legal assets are absent: ${missing.joinToString()}",
            )
        }
    }

    from(projectLicense) {
        into("legal/drawless-chess")
    }
    from(projectNotice) {
        into("legal/drawless-chess")
    }
    from(thirdPartyNotices) {
        into("legal/drawless-chess")
    }
    from(apacheLicense) {
        into("third_party/android-runtime")
    }
    from(releaseSbom) {
        into("third_party/android-runtime")
    }
    from(generateReleaseIdentity.flatMap { it.outputFile }) {
        into("release")
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
