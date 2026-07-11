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

android {
    namespace = "com.drawlesschess"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = nativePin("androidNdkVersion")

    defaultConfig {
        applicationId = "com.drawlesschess"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    buildTypes {
        debug {
            // This is an explicit developer choice, never an automatic native-failure fallback.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", useDevelopmentEngine.toString())
        }
        release {
            // A release build cannot opt into the development engine, even when the Gradle
            // property is present on the command line.
            buildConfigField("boolean", "USE_DEVELOPMENT_ENGINE", "false")
        }
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
