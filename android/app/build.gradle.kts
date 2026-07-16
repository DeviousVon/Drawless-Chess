import java.io.File
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeLockFile = rootProject.projectDir.parentFile.resolve("engine/native/upstream.properties")
val nativeLock = Properties().apply {
    nativeLockFile.inputStream().use(::load)
}

fun nativePin(name: String): String =
    nativeLock.getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw GradleException("Missing '$name' in ${nativeLockFile.absolutePath}")

val useDevelopmentEngine = providers.gradleProperty("drawless.useDevelopmentEngine")
    .orNull
    ?.let { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException(
                "drawless.useDevelopmentEngine must be either 'true' or 'false'",
            )
    }
    ?: false

val repositoryRoot = rootProject.projectDir.parentFile.canonicalFile
val defaultSigningPropertiesFile = rootProject.file("signing.properties").canonicalFile
val customSigningPropertiesFile = System.getenv("DRAWLESS_SIGNING_PROPERTIES")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(::File)
    ?.let { candidate ->
        if (candidate.isAbsolute) candidate else rootProject.file(candidate.path)
    }
    ?.canonicalFile
val signingPropertiesFile = customSigningPropertiesFile ?: defaultSigningPropertiesFile
val signingPropertiesLocationAllowed = customSigningPropertiesFile == null ||
    !signingPropertiesFile.toPath().startsWith(repositoryRoot.toPath())
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: signingProperties.getProperty(propertyName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

val releaseSigningValues = mapOf(
    "DRAWLESS_UPLOAD_STORE_FILE" to signingValue("DRAWLESS_UPLOAD_STORE_FILE", "storeFile"),
    "DRAWLESS_UPLOAD_STORE_PASSWORD" to
        signingValue("DRAWLESS_UPLOAD_STORE_PASSWORD", "storePassword"),
    "DRAWLESS_UPLOAD_KEY_ALIAS" to signingValue("DRAWLESS_UPLOAD_KEY_ALIAS", "keyAlias"),
    "DRAWLESS_UPLOAD_KEY_PASSWORD" to
        signingValue("DRAWLESS_UPLOAD_KEY_PASSWORD", "keyPassword"),
)
val missingReleaseSigningValues = releaseSigningValues
    .filterValues { it == null }
    .keys
    .sorted()
val releaseStoreFile = releaseSigningValues["DRAWLESS_UPLOAD_STORE_FILE"]?.let { configuredPath ->
    File(configuredPath).let { candidate ->
        if (candidate.isAbsolute) candidate else signingPropertiesFile.parentFile.resolve(candidate.path)
    }.canonicalFile
}
val releaseStoreIsOutsideRepository = releaseStoreFile
    ?.toPath()
    ?.startsWith(repositoryRoot.toPath())
    ?.not()
    ?: false
val releaseSigningReady = missingReleaseSigningValues.isEmpty() &&
    releaseStoreFile?.isFile == true &&
    releaseStoreIsOutsideRepository &&
    signingPropertiesLocationAllowed

fun requireReleaseSigning() {
    if (!signingPropertiesLocationAllowed) {
        throw GradleException(
            "DRAWLESS_SIGNING_PROPERTIES must point outside the repository. " +
                "Use the ignored android/signing.properties file for the supported " +
                "in-repository location.",
        )
    }
    if (missingReleaseSigningValues.isNotEmpty()) {
        throw GradleException(
            "Google Play release signing is not configured. bundleRelease will not create " +
                "an unsigned Play artifact. Set the following environment variables, or " +
                "copy signing.properties.example to the ignored " +
                "android/signing.properties file:\n" +
                missingReleaseSigningValues.joinToString(separator = "\n") { "  - $it" } +
                "\nNo secret values were logged.",
        )
    }
    if (releaseStoreFile?.isFile != true) {
        throw GradleException(
            "Google Play upload keystore does not exist: ${releaseStoreFile?.absolutePath}",
        )
    }
    if (!releaseStoreIsOutsideRepository) {
        throw GradleException(
            "Google Play upload keystore must be stored outside the repository: " +
                releaseStoreFile.absolutePath,
        )
    }
}

fun requireCleanReleaseSource() {
    val bundledCommit = repositoryRoot.resolve("SOURCE-COMMIT")
    if (bundledCommit.isFile) {
        val commit = bundledCommit.readText(Charsets.UTF_8).trim()
        if (!commit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("Bundled SOURCE-COMMIT is not a full lowercase Git object ID")
        }
        val manifestFile = repositoryRoot.resolve("SOURCE-MANIFEST.sha256")
        val manifestDigestFile = repositoryRoot.resolve("SOURCE-MANIFEST.sha256.digest")
        if (!manifestFile.isFile || !manifestDigestFile.isFile) {
            throw GradleException("Bundled source manifest or its digest is absent")
        }
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
        val expectedManifestHash = manifestDigestFile.readText(Charsets.UTF_8).trim()
        if (!expectedManifestHash.matches(Regex("[0-9a-f]{64}")) ||
            sha256(manifestFile) != expectedManifestHash
        ) {
            throw GradleException("Bundled source manifest digest does not match")
        }

        val rootPath = repositoryRoot.canonicalFile.toPath()
        val manifestPattern = Regex("""^([0-9a-f]{64})\s+\*?\./(.+)$""")
        val manifestPaths = linkedSetOf<String>()
        manifestFile.forEachLine { line ->
            val match = manifestPattern.matchEntire(line.trimEnd('\r'))
                ?: throw GradleException("Invalid bundled source manifest row")
            val expectedHash = match.groupValues[1]
            val relativePath = match.groupValues[2]
            val parts = relativePath.split('/')
            if (relativePath.startsWith('/') || relativePath.contains('\\') ||
                relativePath.contains(':') || parts.any { it == "." || it == ".." } ||
                !manifestPaths.add(relativePath)
            ) {
                throw GradleException("Unsafe or duplicate bundled source manifest path")
            }
            val sourceFile = repositoryRoot.resolve(relativePath).canonicalFile
            if (!sourceFile.toPath().startsWith(rootPath) || !sourceFile.isFile ||
                sha256(sourceFile) != expectedHash
            ) {
                throw GradleException("Bundled source differs from its manifest: $relativePath")
            }
        }
        val ignoredDirectoryNames = setOf(
            "build",
            ".gradle",
            ".kotlin",
            ".cxx",
            ".idea",
            ".vscode",
            "node_modules",
            ".pnpm-store",
            "pids",
            "captures",
            "__pycache__",
            ".agents",
            ".codex",
            ".git",
        )
        val allowedLocalFiles = setOf(
            "SOURCE-MANIFEST.sha256",
            "SOURCE-MANIFEST.sha256.digest",
            "android/local.properties",
            "android/signing.properties",
        )
        val actualPaths = repositoryRoot.walkTopDown()
            .filter(File::isFile)
            .map { it.relativeTo(repositoryRoot).invariantSeparatorsPath }
            .filterNot { relativePath ->
                relativePath in allowedLocalFiles ||
                    relativePath.split('/').any { it in ignoredDirectoryNames }
            }
            .toSet()
        if (manifestPaths.isEmpty() || actualPaths != manifestPaths) {
            throw GradleException("Bundled source file set differs from its manifest")
        }
        return
    }
    val output = try {
        val process = ProcessBuilder(
            "git",
            "-C",
            repositoryRoot.absolutePath,
            "status",
            "--porcelain",
            "--untracked-files=all",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        if (process.waitFor() != 0) {
            throw GradleException("Could not inspect the release source worktree")
        }
        text
    } catch (exception: GradleException) {
        throw exception
    } catch (exception: Exception) {
        throw GradleException("Could not run Git to inspect the release source worktree", exception)
    }
    if (output.isNotEmpty()) {
        throw GradleException(
            "bundleRelease requires a clean Git worktree so its embedded source commit " +
                "matches the exact corresponding-source archive.",
        )
    }
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless the external Google Play upload-key configuration is complete."
    outputs.upToDateWhen { false }

    doLast {
        requireReleaseSigning()
    }
}

android {
    namespace = "com.drawlesschess"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = nativePin("androidNdkVersion")

    defaultConfig {
        applicationId = "com.drawlesschess"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("releaseUpload") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("DRAWLESS_UPLOAD_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("DRAWLESS_UPLOAD_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("DRAWLESS_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isPseudoLocalesEnabled = true
            // This is an explicit developer choice, never an automatic native-failure fallback.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", useDevelopmentEngine.toString())
        }
        release {
            // A release build cannot opt into the development engine, even when the Gradle
            // property is present on the command line.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", "false")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

// A dependency of bundleRelease could otherwise write an unsigned bundle before
// verifyReleaseSigning executes. Task-graph validation runs before any task action.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":app:bundleRelease" }) {
        requireReleaseSigning()
        requireCleanReleaseSource()
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
