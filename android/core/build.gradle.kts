plugins {
    id("com.android.library")
}

// The JVM checks in src/test are executed by scripts/test-kotlin.sh as one
// dependency-free suite with the engine-module checks. Gradle still compiles
// the core test sources, but there are intentionally no JUnit-discoverable tests.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    failOnNoDiscoveredTests = false
}

android {
    namespace = "com.drawlesschess.core"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
