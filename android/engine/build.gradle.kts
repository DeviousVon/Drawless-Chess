import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.Locale
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

// BEGIN PUBLIC SOURCE VALIDATOR (also exercised by test-android-release-source.py).
object DrawlessPublicSource {
    private val controls = setOf("SOURCE-MANIFEST.sha256", "SOURCE-MANIFEST.sha256.digest")
    private val generatedDirectories = setOf(
        "build", "android/.gradle", "android/.kotlin", "android/build",
        "android/app/build", "android/core/build", "android/engine/build",
        "android/app/.cxx", "android/engine/.cxx",
    )
    private val identityKeys = setOf(
        "schemaVersion", "platform", "applicationId", "version", "build", "publicTag",
        "archive", "nativeComponent", "nativeRevision", "nativeTree", "nativePatchedTree",
        "nativePatchSeriesSha256",
    )

    private fun fail(message: String): Nothing = throw GradleException(message)

    private fun text(file: File): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes())).toString()
    } catch (exception: Exception) {
        throw GradleException("Public source contains an unreadable UTF-8 control file", exception)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun generated(path: String): Boolean = generatedDirectories.any {
        path == it || path.startsWith("$it/")
    }

    private fun forbidden(path: String): Boolean {
        val normalized = path.lowercase(Locale.ROOT)
        return normalized.split('/').any {
            it in setOf(".git", ".forgejo", ".agents", ".codex", "agents.md", "source-commit", "signing.properties")
        } || normalized == "docs/internal" || normalized.startsWith("docs/internal/")
    }

    private fun safePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith('/') &&
            path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun validate(root: File): Map<String, String> {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        fun regular(relative: String): File {
            val path = rootPath.resolve(relative)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                fail("Required public source file is absent or not a regular file: $relative")
            }
            return path.toFile()
        }
        if (Files.exists(rootPath.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            fail("Release builds require the exact extracted public source archive, without Git metadata")
        }
        val identityFile = regular("SOURCE-IDENTITY")
        val manifestFile = regular("SOURCE-MANIFEST.sha256")
        val digestFile = regular("SOURCE-MANIFEST.sha256.digest")
        val digestText = text(digestFile)
        if (!Regex("[0-9a-f]{64}\\n").matches(digestText) ||
            sha256(manifestFile) != digestText.dropLast(1)
        ) {
            fail("Public source manifest digest does not match")
        }

        val manifestText = text(manifestFile)
        if (!manifestText.endsWith('\n') || manifestText.contains('\r')) {
            fail("Public source manifest must use newline-terminated UTF-8 rows")
        }
        val manifestPaths = linkedMapOf<String, String>()
        val portablePaths = mutableSetOf<String>()
        manifestText.dropLast(1).split('\n').forEach { line ->
            val match = Regex("([0-9a-f]{64})  (.+)").matchEntire(line)
                ?: fail("Invalid public source manifest row")
            val path = match.groupValues[2]
            val portable = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            if (!safePath(path) || path in controls || path == "android/local.properties" ||
                generated(path) || forbidden(path) || manifestPaths.put(path, match.groupValues[1]) != null ||
                !portablePaths.add(portable)
            ) {
                fail("Unsafe, excluded, or duplicate public source manifest path")
            }
        }

        val actualPaths = mutableSetOf<String>()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            private fun relative(path: Path): String = rootPath.relativize(path).toString().replace(File.separatorChar, '/')

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val path = relative(dir)
                if (generated(path)) return FileVisitResult.SKIP_SUBTREE
                if (forbidden(path)) fail("Public release source contains private metadata: $path")
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val path = relative(file)
                // These exact build directories may route generated output to external storage.
                if (path in generatedDirectories && attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                if (!attrs.isRegularFile || forbidden(path)) {
                    fail("Public release source contains a link, special file, or private metadata: $path")
                }
                if (path !in controls && path != "android/local.properties") actualPaths.add(path)
                return FileVisitResult.CONTINUE
            }
        })
        if (manifestPaths.isEmpty() || actualPaths != manifestPaths.keys) {
            fail("Public source file set differs from its manifest")
        }
        manifestPaths.forEach { (path, expectedHash) ->
            if (sha256(regular(path)) != expectedHash) {
                fail("Public source differs from its manifest: $path")
            }
        }

        val identityText = text(identityFile)
        if (!identityText.endsWith('\n') || identityText.contains('\r')) {
            fail("SOURCE-IDENTITY must use newline-terminated UTF-8 properties")
        }
        val identity = linkedMapOf<String, String>()
        identityText.dropLast(1).split('\n').forEach { line ->
            val match = Regex("([A-Za-z][A-Za-z0-9]*)=([^\\r\\n]*)").matchEntire(line)
                ?: fail("Invalid SOURCE-IDENTITY property")
            if (identity.put(match.groupValues[1], match.groupValues[2]) != null) {
                fail("Duplicate SOURCE-IDENTITY property")
            }
        }
        if (identity.keys != identityKeys) fail("SOURCE-IDENTITY has missing or unknown properties")
        val appSource = text(regular("android/app/build.gradle.kts"))
        fun appValue(name: String, pattern: String): String {
            val matches = Regex("(?m)^\\s*$name\\s*=\\s*$pattern\\s*$").findAll(appSource).toList()
            if (matches.size != 1) fail("Public source must declare exactly one literal $name")
            return matches.single().groupValues[1]
        }
        val version = appValue("versionName", "\"([0-9]+\\.[0-9]+\\.[0-9]+)\"")
        val build = appValue("versionCode", "([1-9][0-9]*)")
        val applicationId = appValue("applicationId", "\"(com\\.drawlesschess)\"")
        val lock = Properties().apply { regular("engine/native/upstream.properties").inputStream().use(::load) }
        val expected = mapOf(
            "schemaVersion" to "1", "platform" to "Android", "applicationId" to applicationId,
            "version" to version, "build" to build, "publicTag" to "v$version",
            "archive" to "drawless-chess-android-$version-build-$build-source.tar.gz",
            "nativeComponent" to "Fairy-Stockfish",
            "nativeRevision" to lock.getProperty("revision"),
            "nativeTree" to lock.getProperty("tree"),
            "nativePatchedTree" to lock.getProperty("patchedTree"),
            "nativePatchSeriesSha256" to lock.getProperty("patchSeriesSha256"),
        )
        expected.forEach { (key, value) ->
            if (value.isNullOrEmpty() || identity[key] != value) fail("SOURCE-IDENTITY '$key' does not match the public source")
        }
        listOf("nativeRevision", "nativeTree", "nativePatchedTree").forEach { key ->
            if (!Regex("[0-9a-f]{40}").matches(identity.getValue(key))) fail("Invalid SOURCE-IDENTITY '$key'")
        }
        if (!Regex("[0-9a-f]{64}").matches(identity.getValue("nativePatchSeriesSha256"))) {
            fail("Invalid SOURCE-IDENTITY native patch-series digest")
        }
        return identity
    }
}
// END PUBLIC SOURCE VALIDATOR.

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

// Private checkout builds retain a debugging commit; public archives never consult Git.
val publicSourceArchive = !repositoryRoot.resolve(".git").exists() ||
    repositoryRoot.resolve("SOURCE-IDENTITY").exists()

fun resolveDebugSourceCommit(): String {
    if (publicSourceArchive) {
        throw GradleException("Public source archives use SOURCE-IDENTITY, never SOURCE-COMMIT")
    }
    val commit = try {
        val process = ProcessBuilder(
            "git", "-C", repositoryRoot.absolutePath, "rev-parse", "--verify", "HEAD",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        if (process.waitFor() != 0) throw GradleException("Could not resolve the debug source commit")
        output
    } catch (exception: GradleException) {
        throw exception
    } catch (exception: Exception) {
        throw GradleException("Could not run Git to resolve the debug source commit", exception)
    }
    if (!commit.matches(Regex("[0-9a-f]{40}"))) {
        throw GradleException("Debug source commit is not a full lowercase Git object ID")
    }
    return commit
}

val verifyPublicReleaseSource by tasks.registering {
    group = "verification"
    description = "Verifies the exact extracted public source identity, manifest, and file set."
    outputs.upToDateWhen { false }
    doLast { DrawlessPublicSource.validate(repositoryRoot) }
}

// Resolve Maven reports without building; validate every release build before task actions.
// The explicit prefix list excludes report tasks such as drawlessReleaseSbomInventory.
val releaseBuildTask = Regex(
    "^(assemble|bundle|package|sign|compile|merge|process|generate|configureCMake|buildCMake|" +
        "externalNativeBuild|strip|extract|validate|check|prepare|pre|lint|test).*Release.*$",
)
gradle.taskGraph.whenReady {
    if (allTasks.any { task ->
            task.path == ":engine:verifyPublicReleaseSource" ||
                (task.project.path in setOf(":app", ":core", ":engine") &&
                    task.name != "generateReleaseIdentity" && releaseBuildTask.matches(task.name))
        }
    ) {
        DrawlessPublicSource.validate(repositoryRoot)
    }
}

val fairySource = nativeRoot.resolve(nativePin("sourceDirectory"))
val patchSeries = nativeRoot.resolve(nativePin("patchSeries"))
val drawlessVariants = repositoryRoot.resolve("engine/variants.ini")
val archiveSourceManifest = nativeRoot.resolve("archive-fairy-source.sha256")
val legalAssetsDirectory = layout.buildDirectory.dir("generated/fairy-legal-assets")
val generateReleaseIdentity by tasks.registering(GenerateReleaseIdentityTask::class) {
    sourceCommit.set(providers.provider { resolveDebugSourceCommit() })
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
    dependsOn(if (publicSourceArchive) verifyPublicReleaseSource else generateReleaseIdentity)
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
    if (publicSourceArchive) {
        from(listOf(repositoryRoot.resolve("SOURCE-IDENTITY"), repositoryRoot.resolve("SOURCE-MANIFEST.sha256.digest"))) {
            into("release")
        }
    } else {
        from(generateReleaseIdentity.flatMap { it.outputFile }) {
            into("release")
        }
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
