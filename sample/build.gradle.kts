// A tiny desktop app that shows how another app shares devices through Claude Device Manager.
// Run it while CDM is open: ./gradlew :sample:run
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.swing)
            implementation(libs.jewel.decorated.window) // the custom title bar
        }
    }
}

// Jewel pulls in IntelliJ's coroutines fork, which can crash on cancel. Use the real coroutines instead.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm"))
            .using(module("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${libs.versions.coroutines.get()}"))
    }
}

compose.desktop {
    application {
        mainClass = "com.programmersbox.cdm.sample.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // Jewel's title bar needs JetBrains Runtime 25. Gradle downloads it the first time you run.
        // Only looked up for `run`, so building and compiling stay on your normal JDK.
        if (gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "run" }) {
            javaHome = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.JETBRAINS)
            }.get().metadata.installationPath.asFile.absolutePath
        }
    }
}
