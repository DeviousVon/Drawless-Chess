import java.io.File
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
                "Use the existing external signer or an external signing-properties file.",
        )
    }
    if (missingReleaseSigningValues.isNotEmpty()) {
        throw GradleException(
            "Google Play release signing is not configured. bundleRelease will not create " +
                "an unsigned Play artifact. Use the existing external signer to supply " +
                "the following environment variables, or configure an existing external " +
                "signing-properties file with DRAWLESS_SIGNING_PROPERTIES:\n" +
                missingReleaseSigningValues.joinToString(separator = "\n") { "  - $it" } +
                "\nNo secret values were logged.",
        )
    }
    if (releaseStoreFile?.isFile != true) {
        throw GradleException(
            "The configured Google Play upload keystore does not exist. " +
                "Its local path was intentionally redacted.",
        )
    }
    if (!releaseStoreIsOutsideRepository) {
        throw GradleException(
            "Google Play upload keystore must be stored outside the repository. " +
                "Its local path was intentionally redacted.",
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
        versionCode = 7
        versionName = "1.0.3"
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

    sourceSets.getByName("main").kotlin.directories.add(
        "../../shared/checkpoint-codec/src/main/kotlin",
    )

    androidResources {
        generateLocaleConfig = true
        // SoundPool needs seekable file descriptors; keep the runtime Vorbis assets un-deflated.
        noCompress += "ogg"
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
            applicationIdSuffix = providers.gradleProperty("drawless.debugApplicationIdSuffix")
                .getOrElse(".debug")
            versionNameSuffix = "-debug"
            isPseudoLocalesEnabled = true
            // This is an explicit developer choice, never an automatic native-failure fallback.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", useDevelopmentEngine.toString())
        }
        release {
            // A release build cannot opt into the development engine, even when the Gradle
            // property is present on the command line.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning, ":engine:verifyPublicReleaseSource")
}

// A dependency of bundleRelease could otherwise write an unsigned bundle before
// verifyReleaseSigning executes. Task-graph validation runs before any task action.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":app:bundleRelease" }) {
        requireReleaseSigning()
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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
