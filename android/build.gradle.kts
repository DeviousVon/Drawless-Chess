plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("androidx.room") version "2.8.4" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}

val bundletoolCli by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundletoolCli("com.android.tools.build:bundletool:1.18.3")
}

tasks.register<JavaExec>("bundletool") {
    group = "verification"
    description = "Runs the pinned bundletool CLI used by Play release verification."
    classpath = bundletoolCli
    mainClass = "com.android.tools.build.bundletool.BundleToolMain"
}
